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
 * Copyright (C) 2008 JRuby project
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

package org.jruby.ext.ffi.jna;

import com.sun.jna.Function;
import org.jruby.RubyModule;
import org.jruby.internal.runtime.methods.CallConfiguration;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * A DynamicMethod that has a callback argument as the last parameter.  It will
 * treat any block as the last argument.
 */
final class CallbackMethodWithBlock extends DynamicMethod {
    private final Marshaller[] marshallers;
    private final Function function;
    private final FunctionInvoker functionInvoker;
    private final int cbindex;
    
    public CallbackMethodWithBlock(RubyModule implementationClass, Function function, 
            FunctionInvoker functionInvoker, Marshaller[] marshallers, int cbindex) {
        super(implementationClass, Visibility.PUBLIC, CallConfiguration.FRAME_AND_SCOPE);
        this.function = function;
        this.functionInvoker = functionInvoker;
        this.marshallers = marshallers;
        this.cbindex = cbindex;
    }

    @Override
    public IRubyObject call(ThreadContext context, IRubyObject self,
            RubyModule clazz, String name, IRubyObject[] args, Block block) {
        boolean blockGiven = block.isGiven();
        Arity.checkArgumentCount(context.getRuntime(), args,
                marshallers.length - (blockGiven ? 1 : 0), marshallers.length);
        
        Invocation invocation = new Invocation(context);
        Object[] nativeArgs = new Object[marshallers.length];
        if (!blockGiven) {
            for (int i = 0; i < args.length; ++i) {
                nativeArgs[i] = marshallers[i].marshal(invocation, args[i]);
            }
        } else {
            for (int i = 0; i < cbindex; ++i) {
                nativeArgs[i] = marshallers[i].marshal(invocation, args[i]);
            }
            nativeArgs[cbindex] = ((CallbackMarshaller) marshallers[cbindex]).marshal(context.getRuntime(), block);
            for (int i = cbindex + 1; i < marshallers.length; ++i) {
                nativeArgs[i] = marshallers[i].marshal(invocation, args[i - 1]);
            }
        }
        IRubyObject retVal = functionInvoker.invoke(context.getRuntime(), function, nativeArgs);
        invocation.finish();
        return retVal;
    }
    @Override
    public DynamicMethod dup() {
        return this;
    }
    @Override
    public Arity getArity() {
        return Arity.fixed(marshallers.length);
    }
    @Override
    public boolean isNative() {
        return true;
    }
}
