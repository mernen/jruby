/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Chad Fowler <chadfowler@chadfowler.com>
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001 Ed Sinjiashvili <slorcim@users.sourceforge.net>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2006 Miguel Covarrubias <mlcovarrubias@gmail.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import static org.jruby.RubyEnumerator.enumeratorize;

import java.io.IOException;
import java.util.List;

import org.jcodings.Encoding;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.JumpException;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.util.RuntimeHelpers;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.BlockCallback;
import org.jruby.runtime.CallBlock;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ObjectMarshal;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.builtin.Variable;
import org.jruby.runtime.component.VariableEntry;
import org.jruby.runtime.marshal.MarshalStream;
import org.jruby.runtime.marshal.UnmarshalStream;
import org.jruby.util.ByteList;
import org.jruby.util.TypeConverter;

/**
 * @author jpetersen
 */
@JRubyClass(name="Range", include="Enumerable")
public class RubyRange extends RubyObject {
    private IRubyObject begin;
    private IRubyObject end;
    private boolean isExclusive;

    public static RubyClass createRangeClass(Ruby runtime) {
        RubyClass result = runtime.defineClass("Range", runtime.getObject(), RANGE_ALLOCATOR);
        runtime.setRange(result);
        result.kindOf = new RubyModule.KindOf() {
            public boolean isKindOf(IRubyObject obj, RubyModule type) {
                return obj instanceof RubyRange;
            }
        };
        
        result.setMarshal(RANGE_MARSHAL);
        result.includeModule(runtime.getEnumerable());

        // We override Enumerable#member? since ranges in 1.8.1 are continuous.
        //        result.defineMethod("member?", callbackFactory.getMethod("include_p", RubyKernel.IRUBY_OBJECT));
        //        result.defineMethod("===", callbackFactory.getMethod("include_p", RubyKernel.IRUBY_OBJECT));

        result.defineAnnotatedMethods(RubyRange.class);
        return result;
    }

    private static final ObjectAllocator RANGE_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyRange(runtime, klass);
        }
    };    

    private RubyRange(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
        begin = end = runtime.getNil();
    }

    public static RubyRange newRange(Ruby runtime, ThreadContext context, IRubyObject begin, IRubyObject end, boolean isExclusive) {
        RubyRange range = new RubyRange(runtime, runtime.getRange());
        range.init(context, begin, end, isExclusive);
        return range;
    }

    public static RubyRange newExclusiveRange(Ruby runtime, ThreadContext context, IRubyObject begin, IRubyObject end) {
        RubyRange range = new RubyRange(runtime, runtime.getRange());
        range.init(context, begin, end, true);
        return range;
    }

    public static RubyRange newInclusiveRange(Ruby runtime, ThreadContext context, IRubyObject begin, IRubyObject end) {
        RubyRange range = new RubyRange(runtime, runtime.getRange());
        range.init(context, begin, end, false);
        return range;
    }

    @Override
    public void copySpecialInstanceVariables(IRubyObject clone) {
        RubyRange range = (RubyRange)clone;
        range.begin = begin;
        range.end = end;
        range.isExclusive = isExclusive;
    }

    final long[] begLen(long len, int err){
        long beg = RubyNumeric.num2long(this.begin);
        long end = RubyNumeric.num2long(this.end);

        if (beg < 0) {
            beg += len;
            if (beg < 0) {
                if (err != 0) throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                return null;
            }
        }

        if (err == 0 || err == 2) {
            if (beg > len) {
                if (err != 0) throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                return null;
            }
            if (end > len) end = len;
        }

        if (end < 0) end += len;
        if (!isExclusive) end++;
        len = end - beg;
        if (len < 0) len = 0;

        return new long[]{beg, len};
    }

    final int[] begLenInt(int len, int err){
        int beg = RubyNumeric.num2int(this.begin);
        int end = RubyNumeric.num2int(this.end);

        if (beg < 0) {
            beg += len;
            if (beg < 0) {
                if (err != 0) throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                return null;
            }
        }

        if (err == 0 || err == 2) {
            if (beg > len) {
                if (err != 0) throw getRuntime().newRangeError(beg + ".." + (isExclusive ? "." : "") + end + " out of range");
                return null;
            }
            if (end > len) end = len;
        }

        if (end < 0) end += len;
        if (!isExclusive) end++;
        len = end - beg;
        if (len < 0) len = 0;

        return new int[]{beg, len};
    }

    private void init(ThreadContext context, IRubyObject begin, IRubyObject end, boolean isExclusive) {
        if (!(begin instanceof RubyFixnum && end instanceof RubyFixnum)) {
            try {
                IRubyObject result = begin.callMethod(context, "<=>", end);
                if (result.isNil()) throw getRuntime().newArgumentError("bad value for range");
            } catch (RaiseException re) {
                throw getRuntime().newArgumentError("bad value for range");
            }
        }

        this.begin = begin;
        this.end = end;
        this.isExclusive = isExclusive;
    }
    
    @JRubyMethod(name = "initialize", required = 2, optional = 1, frame = true)
    public IRubyObject initialize(ThreadContext context, IRubyObject[] args, Block unusedBlock) {
        init(context, args[0], args[1], args.length > 2 && args[2].isTrue());
        return getRuntime().getNil();
    }

    @JRubyMethod(name = {"first", "begin"})
    public IRubyObject first() {
        return begin;
    }

    @JRubyMethod(name = {"last", "end"})
    public IRubyObject last() {
        return end;
    }
    
    @JRubyMethod(name = "hash")
    public RubyFixnum hash(ThreadContext context) {
        long hash = isExclusive ? 1 : 0;
        long h = hash;
        
        long v = begin.callMethod(context, "hash").convertToInteger().getLongValue();
        hash ^= v << 1;
        v = end.callMethod(context, "hash").convertToInteger().getLongValue();
        hash ^= v << 9;
        hash ^= h << 24;
        return getRuntime().newFixnum(hash);
    }
    
    private static byte[] DOTDOTDOT = "...".getBytes();
    private static byte[] DOTDOT = "..".getBytes();

    @JRubyMethod(name = "inspect")
    public IRubyObject inspect(ThreadContext context) {
        RubyString str = inspect(context, begin).strDup(context.getRuntime());
        RubyString str2 = inspect(context, end);

        str.cat(isExclusive ? DOTDOTDOT : DOTDOT);
        str.concat(str2);
        str.infectBy(str2);
        return str;
    }
    
    @JRubyMethod(name = "to_s")
    public IRubyObject to_s(ThreadContext context) {
        RubyString str = RubyString.objAsString(context, begin).strDup(context.getRuntime());
        RubyString str2 = RubyString.objAsString(context, end);

        str.cat(isExclusive ? DOTDOTDOT : DOTDOT);
        str.concat(str2);
        str.infectBy(str2);
        return str;

    }

    @JRubyMethod(name = "exclude_end?")
    public RubyBoolean exclude_end_p() {
        return getRuntime().newBoolean(isExclusive);
    }

    @JRubyMethod(name = "==", required = 1)
    public IRubyObject op_equal(ThreadContext context, IRubyObject other) {
        if (this == other) return getRuntime().getTrue();
        if (!(other instanceof RubyRange)) return getRuntime().getFalse();
        RubyRange otherRange = (RubyRange) other;

        if (equalInternal(context, begin, otherRange.begin) &&
            equalInternal(context, end, otherRange.end) &&
            isExclusive == otherRange.isExclusive) return getRuntime().getTrue();

        return getRuntime().getFalse();
    }

    @JRubyMethod(name = "eql?", required = 1)
    public IRubyObject eql_p(ThreadContext context, IRubyObject other) {
        if (this == other) return getRuntime().getTrue();
        if (!(other instanceof RubyRange)) return getRuntime().getFalse();
        RubyRange otherRange = (RubyRange)other;

        if (eqlInternal(context, begin, otherRange.begin) &&
            eqlInternal(context, end, otherRange.end) &&
            isExclusive == otherRange.isExclusive) return getRuntime().getTrue();

        return getRuntime().getFalse();
    }

    private static abstract class RangeCallBack {
        abstract void call(ThreadContext context, IRubyObject arg);
    }

    private static final class StepBlockCallBack extends RangeCallBack implements BlockCallback {
        final Block block;
        IRubyObject iter;
        final IRubyObject step;

        StepBlockCallBack(Block block, IRubyObject iter, IRubyObject step) {
            this.block = block;
            this.iter = iter;
            this.step = step;
        }

        public IRubyObject call(ThreadContext context, IRubyObject[] args, Block originalBlock) {
            call(context, args[0]);
            return context.getRuntime().getNil();
        }

        void call(ThreadContext context, IRubyObject arg) {
            if (iter instanceof RubyFixnum) {
                iter = RubyFixnum.newFixnum(context.getRuntime(), ((RubyFixnum)iter).getLongValue() - 1);                
            } else {
                iter = iter.callMethod(context, "-", RubyFixnum.one(context.getRuntime()));
            }
            if (iter == RubyFixnum.zero(context.getRuntime())) {
                block.yield(context, arg);
                iter = step;
            }
        }
    }

    private IRubyObject rangeLt(ThreadContext context, IRubyObject a, IRubyObject b) {
        IRubyObject result = a.callMethod(context, "<=>", b);
        if (result.isNil()) return null;
        return RubyComparable.cmpint(context, result, a, b) < 0 ? getRuntime().getTrue() : null;
    }

    private IRubyObject rangeLe(ThreadContext context, IRubyObject a, IRubyObject b) {
        IRubyObject result = a.callMethod(context, "<=>", b);
        if (result.isNil()) return null;
        int c = RubyComparable.cmpint(context, result, a, b);
        if (c == 0) return RubyFixnum.zero(getRuntime());
        return c < 0 ? getRuntime().getTrue() : null;
    }    

    private void rangeEach(ThreadContext context, RangeCallBack callback) {
        IRubyObject v = begin;
        if (isExclusive) {
            while (rangeLt(context, v, end) != null) {
                callback.call(context, v);
                v = v.callMethod(context, "succ");
            }
        } else {
            IRubyObject c;
            while ((c = rangeLe(context, v, end)) != null && c.isTrue()) {
                callback.call(context, v);
                if (c == RubyFixnum.zero(getRuntime())) break;
                v = v.callMethod(context, "succ");
            }
        }
    }

    @JRubyMethod(name = "to_a", frame = true)
    public IRubyObject to_a(ThreadContext context, final Block block) {
        final Ruby runtime = context.getRuntime();

        if (begin instanceof RubyFixnum && end instanceof RubyFixnum) {
            long lim = ((RubyFixnum) end).getLongValue();
            if (!isExclusive) lim++;

            long base = ((RubyFixnum) begin).getLongValue();
            long size = lim - base;
            if (size > Integer.MAX_VALUE) {
                throw runtime.newRangeError("Range size too large for to_a");
            }
            IRubyObject[] array = new IRubyObject[(int)size];
            for (int i = 0; i < size; i++) {
                array[i] = RubyFixnum.newFixnum(runtime, base + i);
            }
            return RubyArray.newArrayNoCopy(runtime, array);
        } else {
            return RubyEnumerable.to_a(context, this);
        }
    }

    @JRubyMethod(name = "each", frame = true)
    public IRubyObject each(ThreadContext context, final Block block) {
        final Ruby runtime = context.getRuntime();

        if (begin instanceof RubyFixnum && end instanceof RubyFixnum) {
            long lim = ((RubyFixnum) end).getLongValue();
            if (!isExclusive) lim++;

            for (long i = ((RubyFixnum) begin).getLongValue(); i < lim; i++) {
                block.yield(context, RubyFixnum.newFixnum(runtime, i));
            }
        } else if (begin instanceof RubyString) {
            ((RubyString) begin).upto(context, end, isExclusive, block);
        } else {
            if (!begin.respondsTo("succ")) throw getRuntime().newTypeError(
                    "can't iterate from " + begin.getMetaClass().getName());
            rangeEach(context, new RangeCallBack() {
                @Override
                void call(ThreadContext context, IRubyObject arg) {
                    block.yield(context, arg);
                }
            });
        }
        return this;
    }

    @JRubyMethod(name = "each", frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject each19(final ThreadContext context, final Block block) {
        return block.isGiven() ? each(context, block) : enumeratorize(context.getRuntime(), this, "each");
    }

    @JRubyMethod(name = "step", frame = true)
    public IRubyObject step(ThreadContext context, IRubyObject step, Block block) {
        return stepCommon(context, step, block);
    }

    @JRubyMethod(name = "step", frame = true)
    public IRubyObject step(ThreadContext context, Block block) {
        return stepCommon(context, RubyFixnum.one(context.getRuntime()), block);
    }

    private IRubyObject stepCommon(ThreadContext context, IRubyObject step, Block block) {
        final Ruby runtime = context.getRuntime();
        long unit = RubyNumeric.num2long(step);
        if (unit < 0) throw runtime.newArgumentError("step can't be negative");

        if (begin instanceof RubyFixnum && end instanceof RubyFixnum) {
            if (unit == 0) throw runtime.newArgumentError("step can't be 0");

            long e = ((RubyFixnum)end).getLongValue();
            if (!isExclusive) e++;
            
            for (long i = ((RubyFixnum)begin).getLongValue(); i < e; i += unit) {
                block.yield(context, RubyFixnum.newFixnum(runtime, i));
            }
        } else {
            IRubyObject tmp = begin.checkStringType();
            if (!tmp.isNil()) {
                if (unit == 0) throw runtime.newArgumentError("step can't be 0");
                // rb_iterate((VALUE(*)_((VALUE)))str_step, (VALUE)args, step_i, (VALUE)iter);
                StepBlockCallBack callback = new StepBlockCallBack(block, RubyFixnum.one(runtime), step);
                Block blockCallback = CallBlock.newCallClosure(this, runtime.getRange(), Arity.singleArgument(), callback, context);
                ((RubyString)tmp).upto(context, end, isExclusive, blockCallback);
            } else if (begin instanceof RubyNumeric) {
                if (equalInternal(context, step, RubyFixnum.zero(runtime))) {
                    throw runtime.newArgumentError("step can't be 0");
                }
                final String method;
                if (isExclusive) {
                    method = "<";
                } else {
                    method = "<=";
                }
                IRubyObject beg = begin;
                while (beg.callMethod(context, method, end).isTrue()) {
                    block.yield(context, beg);
                    beg = beg.callMethod(context, "+", step);
                }
            } else {
                if (unit == 0) throw runtime.newArgumentError("step can't be 0");
                if (!begin.respondsTo("succ")) throw runtime.newTypeError(
                        "can't iterate from " + begin.getMetaClass().getName());
                // range_each_func(range, step_i, b, e, args);
                rangeEach(context, new StepBlockCallBack(block, RubyFixnum.one(runtime), step));
            }
        }
        return this;
    }

    @JRubyMethod(name = "step", frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject step19(final ThreadContext context, final Block block) {
        return block.isGiven() ? step(context, block) : enumeratorize(context.getRuntime(), this, "step");
    }

    @JRubyMethod(name = "step", frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject step19(final ThreadContext context, IRubyObject step, final Block block) {
        return block.isGiven() ? step(context, step, block) : enumeratorize(context.getRuntime(), this, "step", step);
    }

    @JRubyMethod(name = {"include?", "member?", "==="}, required = 1, compat = CompatVersion.RUBY1_8)
    public RubyBoolean include_p(ThreadContext context, IRubyObject obj) {
        if (rangeLe(context, begin, obj) != null) {
            if (isExclusive) {
                if (rangeLt(context, obj, end) != null) return context.getRuntime().getTrue();
            } else {
                if (rangeLe(context, obj, end) != null) return context.getRuntime().getTrue();
            }
        }
        return context.getRuntime().getFalse();
    }

    @JRubyMethod(name = {"include?", "member?"}, frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject include_p19(ThreadContext context, IRubyObject obj) {
        Ruby runtime = context.getRuntime();
        if (begin instanceof RubyNumeric || end instanceof RubyNumeric ||
                !TypeConverter.convertToTypeWithCheck(begin, runtime.getInteger(), "to_int").isNil() ||
                !TypeConverter.convertToTypeWithCheck(end, runtime.getInteger(), "to_int").isNil()) {
            if (rangeLe(context, begin, obj) != null) {
                if (isExclusive) {
                    if (rangeLt(context, obj, end) != null) return runtime.getTrue();
                } else {
                    if (rangeLe(context, obj, end) != null) return runtime.getTrue();
                }
            }
            return runtime.getFalse();
        } else if (begin instanceof RubyString && end instanceof RubyString &&
                ((RubyString)begin).getByteList().realSize == 1 &&
                ((RubyString)end).getByteList().realSize == 1) {
            if (obj.isNil()) return runtime.getFalse();
            if (obj instanceof RubyString) {
                ByteList Vbytes = ((RubyString)obj).getByteList();
                if (Vbytes.realSize != 1) return runtime.getFalse();
                int v = Vbytes.bytes[Vbytes.begin] & 0xff;
                ByteList Bbytes = ((RubyString)begin).getByteList();
                int b = Bbytes.bytes[Bbytes.begin] & 0xff;
                ByteList Ebytes = ((RubyString)end).getByteList();
                int e = Ebytes.bytes[Ebytes.begin] & 0xff;
                if (Encoding.isAscii(v) && Encoding.isAscii(b) && Encoding.isAscii(e)) {
                    if ((b <= v && v < e) || (!isExclusive && v == e)) return runtime.getTrue();
                    return runtime.getFalse();
                }
            }
        }
        return RuntimeHelpers.invokeSuper(context, this, obj, Block.NULL_BLOCK);
    }

    @JRubyMethod(name = "===", compat = CompatVersion.RUBY1_9)
    public IRubyObject eqq_p19(ThreadContext context, IRubyObject obj) {
        return callMethod(context, "include?", obj);
    }

    @JRubyMethod(name = "cover?", compat = CompatVersion.RUBY1_9)
    public IRubyObject cover_p(ThreadContext context, IRubyObject obj) {
        return include_p(context, obj); // 1.8 "include?"
    }

    @JRubyMethod(name = "min", frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject min(ThreadContext context, Block block) {
        if (block.isGiven()) {
            return RuntimeHelpers.invokeSuper(context, this, block);
        } else {
            int c = RubyComparable.cmpint(context, begin.callMethod(context, "<=>", end), begin, end);
            if (c > 0 || (c == 0 && isExclusive)) return context.getRuntime().getNil();
            return begin;
        }
    }

    @JRubyMethod(name = "max", frame = true, compat = CompatVersion.RUBY1_9)
    public IRubyObject max(ThreadContext context, Block block) {
        if (block.isGiven() || isExclusive && !(end instanceof RubyInteger)) {
            return RuntimeHelpers.invokeSuper(context, this, block);
        } else {
            int c = RubyComparable.cmpint(context, begin.callMethod(context, "<=>", end), begin, end);
            Ruby runtime = context.getRuntime();
            if (isExclusive) {
                if (c == 0) return runtime.getNil();
                if (end instanceof RubyFixnum) return RubyFixnum.newFixnum(runtime, ((RubyFixnum)end).getLongValue() - 1);
                return end.callMethod(context, "-", RubyFixnum.one(runtime));
            }
            return end;
        }
    }

    @JRubyMethod(name = "first", compat = CompatVersion.RUBY1_9)
    public IRubyObject first(ThreadContext context) {
        return begin;
    }

    @JRubyMethod(name = "first", compat = CompatVersion.RUBY1_9)
    public IRubyObject first(ThreadContext context, IRubyObject arg) {
        final Ruby runtime = context.getRuntime();
        final int num = RubyNumeric.num2int(arg);
        final RubyArray result = runtime.newArray(num);
        try {
            RubyEnumerable.callEach(runtime, context, this, new BlockCallback() {
                int n = num;
                public IRubyObject call(ThreadContext ctx, IRubyObject[] largs, Block blk) {
                    if (n-- <= 0) throw JumpException.SPECIAL_JUMP;
                    result.append(largs[0]);
                    return runtime.getNil();
                }
            });
        } catch (JumpException.SpecialJump sj) {}
        return result;
    }

    @JRubyMethod(name = "last", compat = CompatVersion.RUBY1_9)
    public IRubyObject last(ThreadContext context) {
        return end;
    }

    @JRubyMethod(name = "last", compat = CompatVersion.RUBY1_9)
    public IRubyObject last(ThreadContext context, IRubyObject arg) {
        return ((RubyArray)RubyKernel.new_array(context, this, this)).last(arg);
    }

    private static final ObjectMarshal RANGE_MARSHAL = new ObjectMarshal() {
        public void marshalTo(Ruby runtime, Object obj, RubyClass type,
                              MarshalStream marshalStream) throws IOException {
            RubyRange range = (RubyRange)obj;

            marshalStream.registerLinkTarget(range);
            List<Variable<IRubyObject>> attrs = range.getVariableList();

            attrs.add(new VariableEntry<IRubyObject>("begin", range.begin));
            attrs.add(new VariableEntry<IRubyObject>("end", range.end));
            attrs.add(new VariableEntry<IRubyObject>("excl", range.isExclusive ? runtime.getTrue() : runtime.getFalse()));

            marshalStream.dumpVariables(attrs);
        }

        public Object unmarshalFrom(Ruby runtime, RubyClass type,
                                    UnmarshalStream unmarshalStream) throws IOException {
            RubyRange range = (RubyRange)type.allocate();
            
            unmarshalStream.registerLinkTarget(range);

            unmarshalStream.defaultVariablesUnmarshal(range);
            
            range.begin = range.removeInternalVariable("begin");
            range.end = range.removeInternalVariable("end");
            range.isExclusive = range.removeInternalVariable("excl").isTrue();

            return range;
        }
    };
}
