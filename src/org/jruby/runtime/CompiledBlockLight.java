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

import org.jruby.RubyModule;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * A Block implemented using a Java-based BlockCallback implementation
 * rather than with an ICallable. For lightweight block logic within
 * Java code.
 */
public class CompiledBlockLight extends CompiledBlock {
    public static Block newCompiledClosureLight(IRubyObject self, Frame frame, Visibility visibility, RubyModule klass,
        DynamicScope dynamicScope, Arity arity, StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        Binding binding = new Binding(self, frame, visibility, klass, dynamicScope);
        BlockBody body = new CompiledBlockLight(arity, scope, callback, hasMultipleArgsHead, argumentType);
        
        return new Block(body, binding);
    }
    
    public static Block newCompiledClosureLight(ThreadContext context, IRubyObject self, Arity arity,
            StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        return newCompiledClosureLight(
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
    
    public static BlockBody newCompiledBlockLight(Arity arity,
            StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        return new CompiledBlockLight(arity, scope, callback, hasMultipleArgsHead, argumentType);
    }

    protected CompiledBlockLight(Arity arity, StaticScope scope, CompiledBlockCallback callback, boolean hasMultipleArgsHead, int argumentType) {
        super(arity, scope, callback, hasMultipleArgsHead, argumentType);
    }
    
    @Override
    protected Frame pre(ThreadContext context, RubyModule klass, Binding binding) {
        return context.preYieldLightBlock(binding, DynamicScope.newDummyScope(scope, binding.getDynamicScope()), klass);
    }
    
    @Override
    protected final void post(ThreadContext context, Binding binding, Visibility vis, Frame lastFrame) {
        binding.getFrame().setVisibility(vis);
        context.postYieldLight(binding, lastFrame);
    }
}
