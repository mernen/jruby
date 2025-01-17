package org.jruby;

import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.jcodings.Encoding;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.common.IRubyWarnings.ID;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/**
 * @author kscott
 *
 */
@JRubyClass(name="StringScanner")
public class RubyStringScanner extends RubyObject {

    private RubyString str;
    private int pos = 0;
    private int lastPos = -1;

    private Region regs;
    private int beg = -1;
    private int end = -1;
    // not to be confused with RubyObject's flags
    private int scannerFlags;

    private static final int MATCHED_STR_SCN_F = 1 << 11;     
    
    private static ObjectAllocator STRINGSCANNER_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyStringScanner(runtime, klass);
        }
    };

    public static RubyClass createScannerClass(final Ruby runtime) {
        RubyClass scannerClass = runtime.defineClass("StringScanner", runtime.getObject(), STRINGSCANNER_ALLOCATOR);
        scannerClass.defineAnnotatedMethods(RubyStringScanner.class);
        ThreadContext context = runtime.getCurrentContext();
        scannerClass.setConstant("Version", runtime.newString("0.7.0").freeze(context));
        scannerClass.setConstant("Id", runtime.newString("$Id: strscan.c 13506 2007-09-24 08:56:24Z nobu $").freeze(context));

        RubyClass standardError = runtime.getStandardError();
        RubyClass error = scannerClass.defineClassUnder(
                "Error", standardError, standardError.getAllocator());

        RubyClass objClass = runtime.getObject();
        if (!objClass.isConstantDefined("ScanError")) {
            objClass.defineConstant("ScanError", error);
        }

        return scannerClass;
    }

    private void clearMatched() {
        scannerFlags &= ~MATCHED_STR_SCN_F;
    }

    private void setMatched() {
        scannerFlags |= MATCHED_STR_SCN_F;
    }

    private boolean isMatched() {
        return (scannerFlags & MATCHED_STR_SCN_F) != 0;
    }
    
    private void check() {
        if (str == null) throw getRuntime().newArgumentError("uninitialized StringScanner object");
    }

    protected RubyStringScanner(Ruby runtime, RubyClass type) {
        super(runtime, type);
    }

    // second argument is allowed, but ignored (MRI)
    @JRubyMethod(name = "initialize", required = 1, optional = 1, frame = true, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(IRubyObject[] args, Block unusedBlock) {
        str = args[0].convertToString();        
        return this;
    }
    
    @JRubyMethod(name = "initialize_copy", frame=true, visibility = Visibility.PRIVATE)
    @Override
    public IRubyObject initialize_copy(IRubyObject other) {
        if (this == other) return this;
        if (!(other instanceof RubyStringScanner)) {
            throw getRuntime().newTypeError("wrong argument type "
                    + other.getMetaClass() + " (expected StringScanner)");
        }

        RubyStringScanner otherScanner = (RubyStringScanner)other;
        str = otherScanner.str;
        pos = otherScanner.pos;
        lastPos = otherScanner.lastPos;
        scannerFlags = otherScanner.scannerFlags;

        regs = otherScanner.regs != null ? otherScanner.regs.clone() : null;
        beg = otherScanner.beg;
        end = otherScanner.end;

        return this;
    }
    
    @JRubyMethod(name = "reset")
    public IRubyObject reset() {
        check();
        pos = 0;
        clearMatched();
        return this;
    }
    
    @JRubyMethod(name = "terminate")
    public IRubyObject terminate() {
        check();
        pos = str.getByteList().realSize;
        clearMatched();
        return this;
    }
    
    @JRubyMethod(name = "clear")
    public IRubyObject clear() {
        check();
        getRuntime().getWarnings().warning(ID.DEPRECATED_METHOD, "StringScanner#clear is obsolete; use #terminate instead", "StringScanner#clear", "#terminate");
        return terminate();
    }
    
    @JRubyMethod(name = "string")
    public RubyString string() {
        return str;
    }

    @JRubyMethod(name = "string=", required = 1)
    public IRubyObject set_string(ThreadContext context, IRubyObject str) {
        this.str = (RubyString) str.convertToString().strDup(context.getRuntime()).freeze(context);
        pos = 0;
        clearMatched();
        return str;
    }

    @JRubyMethod(name = {"concat", "<<"}, required = 1)
    public IRubyObject concat(IRubyObject obj) {
        check();
        str.append(obj); // append will call convertToString()
        return this;
    }
    
    @JRubyMethod(name = {"pos", "pointer"})
    public RubyFixnum pos() {
        check();
        return RubyFixnum.newFixnum(getRuntime(), pos);
    }

    @JRubyMethod(name = {"pos=", "pointer="})
    public IRubyObject set_pos(IRubyObject pos) {
        check();
        int i = RubyNumeric.num2int(pos);
        int size = str.getByteList().realSize;
        if (i < 0) i += size;
        if (i < 0 || i > size) throw getRuntime().newRangeError("index out of range.");
        this.pos = i;
        return RubyFixnum.newFixnum(getRuntime(), i);
    }

    private IRubyObject extractRange(Ruby runtime, int beg, int end) {
        int size = str.getByteList().realSize;
        if (beg > size) return getRuntime().getNil();
        if (end > size) end = size;
        return str.makeShared(runtime, beg, end - beg);
    }
    
    private IRubyObject extractBegLen(Ruby runtime, int beg, int len) {
        assert len >= 0;
        int size = str.getByteList().realSize;
        if (beg > size) return getRuntime().getNil();
        if (beg + len > size) len = size - beg;
        return str.makeShared(runtime, beg, len);
    }
    
    private IRubyObject scan(IRubyObject regex, boolean succptr, boolean getstr, boolean headonly) {
        if (!(regex instanceof RubyRegexp)) throw getRuntime().newTypeError("wrong argument type " + regex.getMetaClass() + " (expected Regexp)");
        check();
        
        Regex pattern = ((RubyRegexp)regex).getPattern();

        clearMatched();
        int rest = str.getByteList().realSize - pos;
        if (rest < 0) return getRuntime().getNil();

        ByteList value = str.getByteList();
        Matcher matcher = pattern.matcher(value.bytes, value.begin + pos, value.begin + value.realSize);

        final int ret;
        if (headonly) {
            ret = matcher.match(value.begin + pos, value.begin + value.realSize, Option.NONE);            
        } else {
            ret = matcher.search(value.begin + pos, value.begin + value.realSize, Option.NONE);
        }

        regs = matcher.getRegion(); 
        if (regs == null) {
            beg = matcher.getBegin();
            end = matcher.getEnd();
        } else {
            beg = regs.beg[0];
            end = regs.end[0];
        }

        if (ret < 0) return getRuntime().getNil();
        setMatched();

        lastPos = pos;
        if (succptr) pos += end;
        return  getstr ? extractBegLen(getRuntime(), lastPos, end) : RubyFixnum.newFixnum(getRuntime(), end);
    }
    
    @JRubyMethod(name = "scan", required = 1)
    public IRubyObject scan(IRubyObject regex) {
        return scan(regex, true, true, true);
    }
    
    @JRubyMethod(name = "match?", required = 1)
    public IRubyObject match_p(IRubyObject regex) {
        return scan(regex, false, false, true);
    }
    
    @JRubyMethod(name = "skip", required = 1)
    public IRubyObject skip(IRubyObject regex) {
        return scan(regex, true, false, true);
    }
    
    @JRubyMethod(name = "check", required = 1)
    public IRubyObject check(IRubyObject regex) {
        return scan(regex, false, true, true);
    }
    
    @JRubyMethod(name = "scan_full", required = 3)
    public IRubyObject scan_full(IRubyObject regex, IRubyObject s, IRubyObject f) {
        return scan(regex, s.isTrue(), f.isTrue(), true);
    }

    @JRubyMethod(name = "scan_until", required = 1)
    public IRubyObject scan_until(IRubyObject regex) {
        return scan(regex, true, true, false);
    }
    
    @JRubyMethod(name = "exist?", required = 1)
    public IRubyObject exist_p(IRubyObject regex) {
        return scan(regex, false, false, false);        
    }
    
    @JRubyMethod(name = "skip_until", required = 1)
    public IRubyObject skip_until(IRubyObject regex) {
        return scan(regex, true, false, false);
    }

    @JRubyMethod(name = "check_until", required = 1)
    public IRubyObject check_until(IRubyObject regex) {
        return scan(regex, false, true, false);        
    }
    
    @JRubyMethod(name = "search_full", required = 3)
    public IRubyObject search_full(IRubyObject regex, IRubyObject s, IRubyObject f) {
        return scan(regex, s.isTrue(), f.isTrue(), false);
    }

    private void adjustRegisters() {
        beg = 0;
        end = pos - lastPos;
        regs = null;
    }
    
    @JRubyMethod(name = "getch")
    public IRubyObject getch(ThreadContext context) {
        check();
        clearMatched();
        
        Ruby runtime = context.getRuntime();
        ByteList value = str.getByteList();
        
        if (pos >= value.realSize) return runtime.getNil();

        Encoding enc = runtime.getKCode().getEncoding();
        
        int len;
        if (enc.isSingleByte()) {
            len = 1;
        } else {
            len = enc.length(value.bytes, value.begin + pos, value.begin + value.realSize);
        }
        
        if (pos + len > value.realSize) len = value.realSize - pos;
        lastPos = pos;
        pos += len;
        
        setMatched();
        adjustRegisters();
        
        return extractRange(runtime, lastPos + beg, lastPos + end);
    }
    
    @JRubyMethod(name = "get_byte")
    public IRubyObject get_byte(ThreadContext context) {
        check();
        clearMatched();
        if (pos >= str.getByteList().realSize) return getRuntime().getNil();
        
        lastPos = pos;
        pos++;
        
        setMatched();
        adjustRegisters();
        
        return extractRange(context.getRuntime(), lastPos + beg, lastPos + end);
    }
    
    @JRubyMethod(name = "getbyte")
    public IRubyObject getbyte(ThreadContext context) {
        context.getRuntime().getWarnings().warning(ID.DEPRECATED_METHOD,
                "StringScanner#getbyte is obsolete; use #get_byte instead",
                "StringScanner#getbyte", "#get_byte");
        return get_byte(context);
    }

    @JRubyMethod(name = "peek", required = 1)
    public IRubyObject peek(ThreadContext context, IRubyObject length) {
        check();

        int len = RubyNumeric.num2int(length);
        if (len < 0) {
            throw context.getRuntime().newArgumentError("negative string size (or size too big)");
        }

        ByteList value = str.getByteList();
        if (pos >= value.realSize) return RubyString.newEmptyString(getRuntime()).infectBy(str);
        if (pos + len > value.realSize) len = value.realSize - pos;

        return extractBegLen(context.getRuntime(), pos, len);
    }

    @JRubyMethod(name = "peep", required = 1)
    public IRubyObject peep(ThreadContext context, IRubyObject length) {
        getRuntime().getWarnings().warning(
                ID.DEPRECATED_METHOD, "StringScanner#peep is obsolete; use #peek instead",
                "StringScanner#peep", "#peek");
        return peek(context, length);
    }
    
    @JRubyMethod(name = "unscan")
    public IRubyObject unscan() {
        check();
        Ruby runtime = getRuntime();

        if (!isMatched()) {
            RubyClass errorClass = runtime.fastGetClass("StringScanner").fastGetClass("Error");
            throw new RaiseException(RubyException.newException(
                    runtime, errorClass, "unscan failed: previous match had failed"));
        }
        pos = lastPos;
        clearMatched();
        return this;
    }
    
    @JRubyMethod(name = "beginning_of_line?", alias = "bol?")
    public IRubyObject bol_p() {
        check();
        ByteList value = str.getByteList();
        if (pos > value.realSize) return getRuntime().getNil();
        if (pos == 0) return getRuntime().getTrue();
        return value.bytes[(value.begin + pos) - 1] == (byte)'\n' ? getRuntime().getTrue() : getRuntime().getFalse();        
    }
    
    @JRubyMethod(name = "eos?")
    public RubyBoolean eos_p(ThreadContext context) {
        check();
        return pos >= str.getByteList().realSize ? context.getRuntime().getTrue() : context.getRuntime().getFalse();
    }
    
    @JRubyMethod(name = "empty?")
    public RubyBoolean empty_p(ThreadContext context) {
        getRuntime().getWarnings().warning(ID.DEPRECATED_METHOD, "StringScanner#empty? is obsolete; use #eos? instead", "StringScanner#empty?", "#eos?");
        return eos_p(context);
    }
    
    @JRubyMethod(name = "rest?")
    public RubyBoolean rest_p(ThreadContext context) {
        check();
        return pos >= str.getByteList().realSize ? context.getRuntime().getFalse() : context.getRuntime().getTrue();
    }

    @JRubyMethod(name = "matched?")
    public RubyBoolean matched_p(ThreadContext context) {
        check();
        return isMatched() ? context.getRuntime().getTrue() : context.getRuntime().getFalse();
    }

    @JRubyMethod(name = "matched")
    public IRubyObject matched(ThreadContext context) {
        check();
        if (!isMatched()) return getRuntime().getNil(); 
        return extractRange(context.getRuntime(), lastPos + beg, lastPos + end);
    }
    
    @JRubyMethod(name = "matched_size")
    public IRubyObject matched_size() {
        check();
        if (!isMatched()) return getRuntime().getNil();
        return RubyFixnum.newFixnum(getRuntime(), end - beg);
    }

    @JRubyMethod(name = "matchedsize")
    public IRubyObject matchedsize() {
        getRuntime().getWarnings().warning(ID.DEPRECATED_METHOD, "StringScanner#matchedsize is obsolete; use #matched_size instead", 
                "StringScanner#matchedize", "#matched_size");
        return matched_size();        
    }

    @JRubyMethod(name = "[]", required = 1)
    public IRubyObject op_aref(ThreadContext context, IRubyObject idx) {
        check();
        if (!isMatched()) return context.getRuntime().getNil();
        int i = RubyNumeric.num2int(idx);
        
        int numRegs = regs == null ? 1 : regs.numRegs;
        if (i < 0) i += numRegs;
        if (i < 0 || i >= numRegs) return context.getRuntime().getNil();
        
        if (regs == null) {
            assert i == 0;
            if (beg == -1) return getRuntime().getNil();
            return extractRange(context.getRuntime(), lastPos + beg, lastPos + end);
        } else {
            if (regs.beg[i] == -1) return getRuntime().getNil();
            return extractRange(context.getRuntime(), lastPos + regs.beg[i], lastPos + regs.end[i]);
        }
    }

    @JRubyMethod(name = "pre_match")
    public IRubyObject pre_match(ThreadContext context) {
        check();
        if (!isMatched()) return context.getRuntime().getNil();
        return extractRange(context.getRuntime(), 0, lastPos + beg);
    }
    
    @JRubyMethod(name = "post_match")
    public IRubyObject post_match(ThreadContext context) {
        check();
        if (!isMatched()) return context.getRuntime().getNil();
        return extractRange(context.getRuntime(), lastPos + end, str.getByteList().realSize);
    }
    
    @JRubyMethod(name = "rest")
    public IRubyObject rest(ThreadContext context) {
        check();
        ByteList value = str.getByteList();
        if (pos >= value.realSize) return RubyString.newEmptyString(context.getRuntime()).infectBy(str);
        return extractRange(context.getRuntime(), pos, value.realSize);
    }
    
    @JRubyMethod(name = "rest_size")
    public RubyFixnum rest_size() {
        check();
        ByteList value = str.getByteList();
        if (pos >= value.realSize) return RubyFixnum.zero(getRuntime());
        return RubyFixnum.newFixnum(getRuntime(), value.realSize - pos);
    }

    @JRubyMethod(name = "restsize")
    public RubyFixnum restsize() {
        getRuntime().getWarnings().warning(ID.DEPRECATED_METHOD, "StringScanner#restsize is obsolete; use #rest_size instead", "StringScanner#restsize", "#rest_size");
        return rest_size();
    }
    
    @JRubyMethod(name = "inspect")
    @Override
    public IRubyObject inspect() {
        if (str == null) return inspect("(uninitialized)");
        if (pos >= str.getByteList().realSize) return inspect("fin");
        if (pos == 0) return inspect(pos + "/" + str.getByteList().realSize + " @ " + inspect2());
        return inspect(pos + "/" + str.getByteList().realSize + " " + inspect1() + " @ " + inspect2()); 
    }
    
    private IRubyObject inspect(String msg) {
        IRubyObject result = getRuntime().newString("#<" + getMetaClass() + " " + msg + ">"); 
        if (str != null) result.infectBy(str);
        return result;
    }
    
    private static final int INSPECT_LENGTH = 5;
    
    private IRubyObject inspect1() {
        if (pos == 0) return RubyString.newEmptyString(getRuntime());
        if (pos > INSPECT_LENGTH) {
            return RubyString.newStringNoCopy(getRuntime(), "...".getBytes()).
            append(str.substr(getRuntime(), pos - INSPECT_LENGTH, INSPECT_LENGTH)).inspect();
        } else {
            return str.substr(getRuntime(), 0, pos).inspect();
        }
    }
    
    private IRubyObject inspect2() {
        if (pos >= str.getByteList().realSize) return RubyString.newEmptyString(getRuntime());
        int len = str.getByteList().realSize - pos;
        if (len > INSPECT_LENGTH) {
            return ((RubyString)str.substr(getRuntime(), pos, INSPECT_LENGTH)).cat("...".getBytes()).inspect();
        } else {
            return str.substr(getRuntime(), pos, len).inspect();
        }
    }

    @JRubyMethod(name = "must_C_version", meta = true)
    public static IRubyObject mustCversion(IRubyObject recv) {
        return recv;
    }
}
