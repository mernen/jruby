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
 * Copyright (C) 2007 Thomas E Enebo <enebo@acm.org>
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
import org.jruby.ast.IterNode;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Represents the live state of a for or END construct in Ruby.  This is different from an
 * ordinary block in that it does not have its own scoped variables.  It leeches those from
 * the next outer scope.  Because of this we do not set up, clone, nor tear down scope-related
 * stuff.  Also because of this we do not need to clone the block since it state does not change.
 * 
 */
public class SharedScopeBlock extends InterpretedBlock {
    protected SharedScopeBlock(IterNode iterNode) {
        super(iterNode, asArgumentType(getArgumentTypeWackyHack(iterNode)));
    }
    
    public static Block newInterpretedSharedScopeClosure(ThreadContext context, IterNode iterNode, DynamicScope dynamicScope, IRubyObject self) {
        Binding binding = new Binding(self,
                context.getCurrentFrame().duplicate(),
                context.getCurrentFrame().getVisibility(),
                context.getRubyClass(),
                dynamicScope);
        BlockBody body = new SharedScopeBlock(iterNode);

        return new Block(body, binding);
    }
    
    @Override
    protected Frame pre(ThreadContext context, RubyModule klass, Binding binding) {
        return context.preForBlock(binding, klass);
    }
    
    public IRubyObject call(ThreadContext context, IRubyObject[] args, IRubyObject replacementSelf, Binding binding, Block.Type type) {
        return yield(context, context.getRuntime().newArrayNoCopy(args), null, null, true, binding, type);
    }
    
    @Override
    public Block cloneBlock(Binding binding) {
        return new Block(this, binding);
    }
}
