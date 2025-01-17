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
 * Copyright (C) 2007 Charles O Nutter <headius@headius.com>
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
package org.jruby.runtime;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyModule;
import org.jruby.ast.util.ArgsUtil;
import org.jruby.common.IRubyWarnings.ID;
import org.jruby.exceptions.JumpException;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * A Block implemented using a Java-based BlockCallback implementation
 * rather than with an ICallable. For lightweight block logic within
 * Java code.
 */
public class CompiledBlock extends BlockBody {
    protected final CompiledBlockCallback callback;
    protected final boolean hasMultipleArgsHead;
    protected final Arity arity;
    protected final StaticScope scope;
    
    public static Block newCompiledClosure(IRubyObject self, Frame frame, Visibility visibility, RubyModule klass,
        DynamicScope dynamicScope, Arity arity, StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        Binding binding = new Binding(self, frame, visibility, klass, dynamicScope);
        BlockBody body = new CompiledBlock(arity, scope, callback, hasMultipleArgsHead, argumentType);
        
        return new Block(body, binding);
    }
    
    public static Block newCompiledClosure(ThreadContext context, IRubyObject self, Arity arity,
            StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        return newCompiledClosure(
                self,
                context.getCurrentFrame(),
                Visibility.PUBLIC,
                context.getRubyClass(),
                context.getCurrentScope(),
                arity,
                scope,
                callback,
                hasMultipleArgsHead,
                argumentType);
    }
    
    public static Block newCompiledClosure(ThreadContext context, IRubyObject self, BlockBody body) {
        Binding binding = new Binding(self, context.getCurrentFrame(), Visibility.PUBLIC, context.getRubyClass(), context.getCurrentScope());
        return new Block(body, binding);
    }
    
    public static BlockBody newCompiledBlock(Arity arity,
            StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        return new CompiledBlock(arity, scope, callback, hasMultipleArgsHead, argumentType);
    }

    protected CompiledBlock(Arity arity, StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        super(argumentType);
        this.arity = arity;
        this.scope = scope;
        this.callback = callback;
        this.hasMultipleArgsHead = hasMultipleArgsHead;
    }

    @Override
    public IRubyObject yield(ThreadContext context, IRubyObject value, Binding binding, Block.Type type) {
        IRubyObject self = prepareSelf(binding);

        IRubyObject realArg = setupBlockArg(context.getRuntime(), value, self); 
        Visibility oldVis = binding.getFrame().getVisibility();
        Frame lastFrame = pre(context, null, binding);
        
        try {
            return callback.call(context, self, realArg);
        } catch (JumpException.NextJump nj) {
            // A 'next' is like a local return from the block, ending this call or yield.
            return handleNextJump(context, nj, type);
        } finally {
            post(context, binding, oldVis, lastFrame);
        }
    }
    
    public IRubyObject yield(ThreadContext context, IRubyObject args, IRubyObject self, RubyModule klass, boolean aValue, Binding binding, Block.Type type) {
        if (klass == null) {
            self = prepareSelf(binding);
        }

        IRubyObject realArg = aValue ? 
                setupBlockArgs(context, args, self) : setupBlockArg(context.getRuntime(), args, self); 
        Visibility oldVis = binding.getFrame().getVisibility();
        Frame lastFrame = pre(context, klass, binding);
        
        try {
            return callback.call(context, self, realArg);
        } catch (JumpException.NextJump nj) {
            // A 'next' is like a local return from the block, ending this call or yield.
            return handleNextJump(context, nj, type);
        } finally {
            post(context, binding, oldVis, lastFrame);
        }
    }
    
    private IRubyObject prepareSelf(Binding binding) {
        IRubyObject self = binding.getSelf();
        binding.getFrame().setSelf(self);
        
        return self;
    }
    
    private IRubyObject handleNextJump(ThreadContext context, JumpException.NextJump nj, Block.Type type) {
        return type == Block.Type.LAMBDA ? context.getRuntime().getNil() : (IRubyObject)nj.getValue();
    }
    
    protected Frame pre(ThreadContext context, RubyModule klass, Binding binding) {
        return context.preYieldSpecificBlock(binding, scope, klass);
    }
    
    protected void post(ThreadContext context, Binding binding, Visibility vis, Frame lastFrame) {
        binding.getFrame().setVisibility(vis);
        context.postYield(binding, lastFrame);
    }

    protected IRubyObject setupBlockArgs(ThreadContext context, IRubyObject value, IRubyObject self) {
        switch (argumentType) {
        case ZERO_ARGS:
            return null;
        case MULTIPLE_ASSIGNMENT:
        case SINGLE_RESTARG:
            return value;
        default:
            return defaultArgsLogic(context.getRuntime(), value);
        }
    }
    
    private IRubyObject defaultArgsLogic(Ruby ruby, IRubyObject value) {
        int length = ArgsUtil.arrayLength(value);
        switch (length) {
        case 0:
            return ruby.getNil();
        case 1:
            return ((RubyArray)value).eltInternal(0);
        default:
            blockArgWarning(ruby, length);
        }
        return value;
    }
    
    private void blockArgWarning(Ruby ruby, int length) {
        ruby.getWarnings().warn(ID.MULTIPLE_VALUES_FOR_BLOCK, "multiple values for a block parameter (" +
                    length + " for 1)");
    }

    protected IRubyObject setupBlockArg(Ruby ruby, IRubyObject value, IRubyObject self) {
        switch (argumentType) {
        case ZERO_ARGS:
            return null;
        case MULTIPLE_ASSIGNMENT:
        case SINGLE_RESTARG:
            return ArgsUtil.convertToRubyArray(ruby, value, hasMultipleArgsHead);
        default:
            return defaultArgLogic(ruby, value);
        }
    }
    
    private IRubyObject defaultArgLogic(Ruby ruby, IRubyObject value) {
        if (value == null) {
            ruby.getWarnings().warn(ID.MULTIPLE_VALUES_FOR_BLOCK, "multiple values for a block parameter (0 for 1)");
            return ruby.getNil();
        }
        return value;
    }
    
    public StaticScope getStaticScope() {
        return scope;
    }

    public Block cloneBlock(Binding binding) {
        binding = new Binding(binding.getSelf(),
                binding.getFrame().duplicate(),
                binding.getVisibility(),
                binding.getKlass(),
                binding.getDynamicScope());
        
        return new Block(this, binding);
    }

    @Override
    public Arity arity() {
        return arity;
    }
}
