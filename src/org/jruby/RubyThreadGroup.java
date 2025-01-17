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
 * Copyright (C) 2004 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
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

import java.util.HashMap;
import java.util.Map;
import org.jruby.anno.JRubyMethod;
import org.jruby.anno.JRubyClass;

import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Implementation of Ruby's <code>ThreadGroup</code> class. This is currently
 * just a stub.
 * <p>
 *
 * @author Charles O Nutter (headius@headius.com)
 */
@JRubyClass(name="ThreadGroup")
public class RubyThreadGroup extends RubyObject {
    private Map<Integer, IRubyObject> rubyThreadList = new HashMap<Integer, IRubyObject>();
    private boolean enclosed = false;

    // ENEBO: Can these be fast?
    public static RubyClass createThreadGroupClass(Ruby runtime) {
        RubyClass threadGroupClass = runtime.defineClass("ThreadGroup", runtime.getObject(), ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR);
        runtime.setThreadGroup(threadGroupClass);
        
        threadGroupClass.defineAnnotatedMethods(RubyThreadGroup.class);
        
        // create the default thread group
        RubyThreadGroup defaultThreadGroup = new RubyThreadGroup(runtime, threadGroupClass);
        runtime.setDefaultThreadGroup(defaultThreadGroup);
        threadGroupClass.defineConstant("Default", defaultThreadGroup);

        return threadGroupClass;
    }
    
    @JRubyMethod(name = "new", frame = true, meta = true)
    public static IRubyObject newInstance(IRubyObject recv, Block block) {
        return new RubyThreadGroup(recv.getRuntime(), (RubyClass)recv);
    }

    @JRubyMethod(name = "add", required = 1, frame = true)
    public synchronized IRubyObject add(IRubyObject rubyThread, Block block) {
        if (!(rubyThread instanceof RubyThread)) throw getRuntime().newTypeError(rubyThread, getRuntime().getThread());
        
        // synchronize on the RubyThread for threadgroup updates
        if (isFrozen()) {
            throw getRuntime().newTypeError("can't add to frozen ThreadGroup");
        }

        RubyThread thread = (RubyThread)rubyThread;

        // we only add live threads
        if (thread.alive_p().isTrue()) {
            addDirectly(thread);
        }
        
        return this;
    }
    
    void addDirectly(RubyThread rubyThread) {
        synchronized (rubyThread) {
            IRubyObject oldGroup = rubyThread.group();
            if (oldGroup != getRuntime().getNil()) {
                RubyThreadGroup threadGroup = (RubyThreadGroup) oldGroup;
                threadGroup.rubyThreadList.remove(System.identityHashCode(rubyThread));
            }

            rubyThread.setThreadGroup(this);
            rubyThreadList.put(System.identityHashCode(rubyThread), rubyThread);
        }
    }
    
    public synchronized void remove(RubyThread rubyThread) {
        rubyThread.setThreadGroup(null);
        rubyThreadList.remove(System.identityHashCode(rubyThread));
    }
    
    @JRubyMethod(name = "enclose", frame = true)
    public IRubyObject enclose(Block block) {
        enclosed = true;

        return this;
    }
    
    @JRubyMethod(name = "enclosed?", frame = true)
    public IRubyObject enclosed_p(Block block) {
        return new RubyBoolean(getRuntime(), enclosed);
    }

    @JRubyMethod(name = "list", frame = true)
    public synchronized IRubyObject list(Block block) {
        return getRuntime().newArrayNoCopy((IRubyObject[]) rubyThreadList.values().toArray(new IRubyObject[rubyThreadList.size()]));
    }

    private RubyThreadGroup(Ruby runtime, RubyClass type) {
        super(runtime, type);
    }

}
