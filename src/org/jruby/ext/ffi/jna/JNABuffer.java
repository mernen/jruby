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

import org.jruby.ext.ffi.*;
import com.sun.jna.Pointer;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyString;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/**
 *
 */
@JRubyClass(name = FFIProvider.MODULE_NAME + "::" + JNABuffer.BUFFER_RUBY_CLASS, parent = FFIProvider.MODULE_NAME + "::" + AbstractMemoryPointer.className)
public class JNABuffer extends AbstractBuffer implements JNAMemory {
    public static final String BUFFER_RUBY_CLASS = "Buffer";
    private static final boolean CLEAR_DEFAULT = true;
    
    public static RubyClass createBufferClass(Ruby runtime, RubyModule module) {
        RubyClass result = module.defineClassUnder(BUFFER_RUBY_CLASS,
                module.getClass(AbstractBuffer.ABSTRACT_BUFFER_RUBY_CLASS),
                ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR);
        result.defineAnnotatedMethods(JNABuffer.class);
        result.defineAnnotatedConstants(JNABuffer.class);

        return result;
    }
    
    public JNABuffer(Ruby runtime, RubyClass klass) {
        super(runtime, klass, JNAMemoryIO.wrap(Pointer.NULL), 0, 0);
    }
    
    private JNABuffer(Ruby runtime, IRubyObject klass, JNABuffer ptr, long offset) {
        this(runtime, klass, ptr.io, ptr.offset + offset,
                ptr.size == Long.MAX_VALUE ? Long.MAX_VALUE : ptr.size - offset);
    }
    private JNABuffer(Ruby runtime, IRubyObject klass, MemoryIO io, long offset, long size) {
        super(runtime, (RubyClass) klass, io, offset, size);
    }
    private static JNABuffer allocate(ThreadContext context, IRubyObject recv, IRubyObject sizeArg, boolean clear) {
        int size = Util.int32Value(sizeArg);
        JNAMemoryIO io = size > 0 ? JNAMemoryIO.allocateDirect(size) : JNAMemoryIO.NULL;
        if (clear && size > 0) {
            io.setMemory(0, size, (byte) 0);
        }
        return new JNABuffer(context.getRuntime(), recv, io, 0, size);
    }
    @JRubyMethod(name = { "alloc_inout", "__alloc_inout", "__alloc_heap_inout", "__alloc_direct_inout" }, meta = true)
    public static JNABuffer allocateDirect(ThreadContext context, IRubyObject recv, IRubyObject sizeArg) {
        return allocate(context, recv, sizeArg, CLEAR_DEFAULT);
    }
    @JRubyMethod(name = { "alloc_inout", "__alloc_inout", "__alloc_heap_inout", "__alloc_direct_inout" }, meta = true)
    public static JNABuffer allocateDirect(ThreadContext context, IRubyObject recv, IRubyObject sizeArg, IRubyObject clearArg) {
        return allocate(context, recv, sizeArg, clearArg.isTrue());
    }
    @JRubyMethod(name = { "alloc_in", "__alloc_in", "__alloc_heap_in", "__alloc_direct_in" }, meta = true)
    public static JNABuffer allocateInput(ThreadContext context, IRubyObject recv, IRubyObject arg) {
        if (arg instanceof RubyString) {
            final RubyString s = (RubyString) arg;
            final int size = Util.int32Value(s.length());
            final ByteList bl = s.getByteList();
            final JNAMemoryIO io = JNAMemoryIO.allocateDirect(size);
            io.put(0, bl.unsafeBytes(), bl.begin(), bl.length());
            io.putByte(bl.length(), (byte) 0);
            return new JNABuffer(context.getRuntime(), recv, io, 0, size);
        } else {
            return allocate(context, recv, arg, CLEAR_DEFAULT);
        }
    }
    @JRubyMethod(name = { "alloc_in", "__alloc_in", "__alloc_heap_in", "__alloc_direct_in" }, meta = true)
    public static JNABuffer allocateInput(ThreadContext context, IRubyObject recv, IRubyObject sizeArg, IRubyObject clearArg) {
        return allocate(context, recv, sizeArg, clearArg.isTrue());
    }
    @JRubyMethod(name = { "alloc_out", "__alloc_out", "__alloc_heap_out", "__alloc_direct_out" }, meta = true)
    public static JNABuffer allocateOutput(ThreadContext context, IRubyObject recv, IRubyObject sizeArg) {
        return allocate(context, recv, sizeArg, CLEAR_DEFAULT);
    }
    @JRubyMethod(name = { "alloc_out", "__alloc_out", "__alloc_heap_out", "__alloc_direct_out" }, meta = true)
    public static JNABuffer allocateOutput(ThreadContext context, IRubyObject recv, IRubyObject sizeArg, IRubyObject clearArg) {
        return allocate(context, recv, sizeArg, clearArg.isTrue());
    }
    public Object getNativeMemory() {
        return ((JNAMemoryIO) getMemoryIO()).slice(offset).getMemory();
    }
    @JRubyMethod(name = "+", required = 1)
    public IRubyObject op_plus(ThreadContext context, IRubyObject value) {
        return new JNABuffer(context.getRuntime(),
                FFIProvider.getModule(context.getRuntime()).fastGetClass(BUFFER_RUBY_CLASS),
                this, RubyNumeric.fix2long(value));
    }
    @JRubyMethod(name = "put_pointer", required = 2)
    public IRubyObject put_pointer(ThreadContext context, IRubyObject offset, IRubyObject value) {
        Pointer ptr;
        if (value instanceof JNAMemoryPointer) {
            ptr = ((JNAMemoryPointer) value).getAddress();
        } else if (value.isNil()) {
            ptr = Pointer.NULL;
        } else {
            throw context.getRuntime().newArgumentError("Cannot convert argument to pointer");
        }
        ((JNAMemoryIO) getMemoryIO()).putPointer(getOffset(offset), ptr);
        return this;
    }
    
    protected AbstractMemoryPointer getPointer(Ruby runtime, long offset) {
        return new JNABasePointer(runtime,
                getMemoryIO().getMemoryIO(this.offset + offset), 0, Long.MAX_VALUE);
    }
}
