/*
 ***** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2006 Charles O Nutter <headius@headius.com>
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

package org.jruby.compiler.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.ast.executable.AbstractScript;
import org.jruby.compiler.ASTInspector;
import org.jruby.compiler.CacheCompiler;
import org.jruby.compiler.CompilerCallback;
import org.jruby.compiler.BodyCompiler;
import org.jruby.compiler.ScriptCompiler;
import org.jruby.javasupport.util.RuntimeHelpers;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import static org.jruby.util.CodegenUtils.*;
import org.jruby.util.JRubyClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 *
 * @author headius
 */
public class StandardASMCompiler implements ScriptCompiler, Opcodes {
    public static final String THREADCONTEXT = p(ThreadContext.class);
    public static final String RUBY = p(Ruby.class);
    public static final String IRUBYOBJECT = p(IRubyObject.class);

    public static final String[] METHOD_SIGNATURES = {
        sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, Block.class}),
        sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, IRubyObject.class, Block.class}),
        sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, IRubyObject.class, IRubyObject.class, Block.class}),
        sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, IRubyObject.class, IRubyObject.class, IRubyObject.class, Block.class}),
        sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, IRubyObject[].class, Block.class}),
    };
    public static final String CLOSURE_SIGNATURE = sig(IRubyObject.class, new Class[]{ThreadContext.class, IRubyObject.class, IRubyObject.class});

    public static final int THIS = 0;
    public static final int THREADCONTEXT_INDEX = 1;
    public static final int SELF_INDEX = 2;
    public static final int ARGS_INDEX = 3;
    
    public static final int CLOSURE_OFFSET = 0;
    public static final int DYNAMIC_SCOPE_OFFSET = 1;
    public static final int RUNTIME_OFFSET = 2;
    public static final int VARS_ARRAY_OFFSET = 3;
    public static final int NIL_OFFSET = 4;
    public static final int EXCEPTION_OFFSET = 5;
    public static final int PREVIOUS_EXCEPTION_OFFSET = 6;
    public static final int FIRST_TEMP_OFFSET = 7;
    
    private String classname;
    private String sourcename;

    private ClassWriter classWriter;
    private SkinnyMethodAdapter initMethod;
    private SkinnyMethodAdapter clinitMethod;
    private int methodIndex = 0;
    private int innerIndex = 0;
    int fieldIndex = 0;
    private int rescueNumber = 1;
    private int ensureNumber = 1;
    StaticScope topLevelScope;
    
    private CacheCompiler cacheCompiler;
    
    public static final Constructor invDynInvCompilerConstructor;
    public static final Method invDynSupportInstaller;

    static {
        Constructor compilerConstructor = null;
        Method installerMethod = null;
        try {
            // try to load java.dyn.Dynamic first
            Class.forName("java.dyn.Dynamic");
            
            // if that succeeds, the others should as well
            Class compiler =
                    Class.forName("org.jruby.compiler.impl.InvokeDynamicInvocationCompiler");
            Class support =
                    Class.forName("org.jruby.runtime.invokedynamic.InvokeDynamicSupport");
            compilerConstructor = compiler.getConstructor(BaseBodyCompiler.class, SkinnyMethodAdapter.class);
            installerMethod = support.getDeclaredMethod("installBytecode", MethodVisitor.class, String.class);
        } catch (Exception e) {
            // leave it null and fall back on our normal invocation logic
        }
        invDynInvCompilerConstructor = compilerConstructor;
        invDynSupportInstaller = installerMethod;
    }
    
    /** Creates a new instance of StandardCompilerContext */
    public StandardASMCompiler(String classname, String sourcename) {
        this.classname = classname;
        this.sourcename = sourcename;
    }

    public byte[] getClassByteArray() {
        return classWriter.toByteArray();
    }

    public Class<?> loadClass(JRubyClassLoader classLoader) throws ClassNotFoundException {
        classLoader.defineClass(c(getClassname()), classWriter.toByteArray());
        return classLoader.loadClass(c(getClassname()));
    }
    
    public void dumpClass(PrintStream out) {
        PrintWriter pw = new PrintWriter(out);
        TraceClassVisitor tcv = new TraceClassVisitor(pw);
        new ClassReader(classWriter.toByteArray()).accept(tcv, 0);

        try {
            tcv.print(pw);
        } finally {
            pw.close();
        }
    }

    public void writeClass(File destination) throws IOException {
        writeClass(getClassname(),destination, classWriter);
    }

    private void writeClass(String classname, File destination, ClassWriter writer) throws IOException {
        String fullname = classname + ".class";
        String filename = null;
        String path = null;
        
        // verify the class
        byte[] bytecode = writer.toByteArray();
        CheckClassAdapter.verify(new ClassReader(bytecode), false, new PrintWriter(System.err));
        
        if (fullname.lastIndexOf("/") == -1) {
            filename = fullname;
            path = "";
        } else {
            filename = fullname.substring(fullname.lastIndexOf("/") + 1);
            path = fullname.substring(0, fullname.lastIndexOf("/"));
        }
        // create dir if necessary
        File pathfile = new File(destination, path);
        pathfile.mkdirs();

        FileOutputStream out = new FileOutputStream(new File(pathfile, filename));

        out.write(bytecode);
        out.close();
    }

    public String getClassname() {
        return classname;
    }

    public String getSourcename() {
        return sourcename;
    }

    public ClassVisitor getClassVisitor() {
        return classWriter;
    }
    
    public void startScript(StaticScope scope) {
        classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        // Create the class with the appropriate class name and source file
        classWriter.visit(RubyInstanceConfig.JAVA_VERSION, ACC_PUBLIC + ACC_SUPER,getClassname(), null, p(AbstractScript.class), null);
        
        topLevelScope = scope;

        beginInit();
        beginClassInit();
        
        cacheCompiler = new InheritedCacheCompiler(this);

        String sourceNoPath;
        if (sourcename.indexOf("/") >= 0) {
            String[] pathElements = sourcename.split("/");
            sourceNoPath = pathElements[pathElements.length - 1];
        } else if (sourcename.indexOf("\\") >= 0) {
            String[] pathElements = sourcename.split("\\\\");
            sourceNoPath = pathElements[pathElements.length - 1];
        } else {
            sourceNoPath = sourcename;
        }
        
        StringBuffer smap = new StringBuffer();
        smap.append("SMAP\n")
                .append(sourceNoPath).append("\n")
                .append("Ruby\n")
                .append("*S Ruby\n")
                .append("*F\n")
                .append("+ 1 ").append(sourceNoPath).append("\n")
                .append(sourcename).append("\n")
                .append("*L\n")
                .append("1#1,999999:1,1\n")
                .append("*E\n");

        
        classWriter.visitSource(sourceNoPath, smap.toString());
    }

    public void endScript(boolean generateLoad, boolean generateMain) {
        // add Script#run impl, used for running this script with a specified threadcontext and self
        // root method of a script is always in __file__ method
        String methodName = "__file__";
        
        if (generateLoad || generateMain) {
            // the load method is used for loading as a top-level script, and prepares appropriate scoping around the code
            SkinnyMethodAdapter method = new SkinnyMethodAdapter(getClassVisitor().visitMethod(ACC_PUBLIC, "load", METHOD_SIGNATURES[4], null, null));
            method.start();

            // invoke __file__ with threadcontext, self, args (null), and block (null)
            Label tryBegin = new Label();
            Label tryFinally = new Label();

            method.label(tryBegin);
            method.aload(THREADCONTEXT_INDEX);
            buildStaticScopeNames(method, topLevelScope);
            method.invokestatic(p(RuntimeHelpers.class), "preLoad", sig(void.class, ThreadContext.class, String[].class));

            method.aload(THIS);
            method.aload(THREADCONTEXT_INDEX);
            method.aload(SELF_INDEX);
            method.aload(ARGS_INDEX);
            // load always uses IRubyObject[], so simple closure offset calculation here
            method.aload(ARGS_INDEX + 1 + CLOSURE_OFFSET);

            method.invokevirtual(getClassname(),methodName, METHOD_SIGNATURES[4]);
            method.aload(THREADCONTEXT_INDEX);
            method.invokestatic(p(RuntimeHelpers.class), "postLoad", sig(void.class, ThreadContext.class));
            method.areturn();

            method.label(tryFinally);
            method.aload(THREADCONTEXT_INDEX);
            method.invokestatic(p(RuntimeHelpers.class), "postLoad", sig(void.class, ThreadContext.class));
            method.athrow();

            method.trycatch(tryBegin, tryFinally, tryFinally, null);

            method.end();
        }
        
        if (generateMain) {
            // add main impl, used for detached or command-line execution of this script with a new runtime
            // root method of a script is always in stub0, method0
            SkinnyMethodAdapter method = new SkinnyMethodAdapter(getClassVisitor().visitMethod(ACC_PUBLIC | ACC_STATIC, "main", sig(Void.TYPE, params(String[].class)), null, null));
            method.start();

            // new instance to invoke run against
            method.newobj(getClassname());
            method.dup();
            method.invokespecial(getClassname(), "<init>", sig(Void.TYPE));

            // instance config for the script run
            method.newobj(p(RubyInstanceConfig.class));
            method.dup();
            method.invokespecial(p(RubyInstanceConfig.class), "<init>", "()V");

            // set argv from main's args
            method.dup();
            method.aload(0);
            method.invokevirtual(p(RubyInstanceConfig.class), "setArgv", sig(void.class, String[].class));

            // invoke run with threadcontext and topself
            method.invokestatic(p(Ruby.class), "newInstance", sig(Ruby.class, RubyInstanceConfig.class));
            method.dup();

            method.invokevirtual(RUBY, "getCurrentContext", sig(ThreadContext.class));
            method.swap();
            method.invokevirtual(RUBY, "getTopSelf", sig(IRubyObject.class));
            method.getstatic(p(IRubyObject.class), "NULL_ARRAY", ci(IRubyObject[].class));
            method.getstatic(p(Block.class), "NULL_BLOCK", ci(Block.class));

            method.invokevirtual(getClassname(), "load", METHOD_SIGNATURES[4]);
            method.voidreturn();
            method.end();
        }
        
        // add setPosition impl, which stores filename as constant to speed updates
        SkinnyMethodAdapter method = new SkinnyMethodAdapter(getClassVisitor().visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "setPosition", sig(Void.TYPE, params(ThreadContext.class, int.class)), null, null));
        method.start();

        method.aload(0); // thread context
        method.ldc(sourcename);
        method.iload(1); // line number
        method.invokevirtual(p(ThreadContext.class), "setFileAndLine", sig(void.class, String.class, int.class));
        method.voidreturn();
        method.end();

        getCacheCompiler().finish();
        
        endInit();
        endClassInit();
    }

    public static void buildStaticScopeNames(SkinnyMethodAdapter method, StaticScope scope) {
        // construct static scope list of names
        String signature = null;
        switch (scope.getNumberOfVariables()) {
        case 0:
            method.pushInt(0);
            method.anewarray(p(String.class));
            break;
        case 1: case 2: case 3: case 4: case 5:
        case 6: case 7: case 8: case 9: case 10:
            signature = sig(String[].class, params(String.class, scope.getNumberOfVariables()));
            for (int i = 0; i < scope.getNumberOfVariables(); i++) {
                method.ldc(scope.getVariables()[i]);
            }
            method.invokestatic(p(RuntimeHelpers.class), "constructStringArray", signature);
            break;
        default:
            method.pushInt(scope.getNumberOfVariables());
            method.anewarray(p(String.class));
            for (int i = 0; i < scope.getNumberOfVariables(); i++) {
                method.dup();
                method.pushInt(i);
                method.ldc(scope.getVariables()[i]);
                method.arraystore();
            }
            break;
        }
    }

    private void beginInit() {
        ClassVisitor cv = getClassVisitor();

        initMethod = new SkinnyMethodAdapter(cv.visitMethod(ACC_PUBLIC, "<init>", sig(Void.TYPE), null, null));
        initMethod.start();
        initMethod.aload(THIS);
        initMethod.invokespecial(p(AbstractScript.class), "<init>", sig(Void.TYPE));
        
        cv.visitField(ACC_PRIVATE | ACC_FINAL, "$class", ci(Class.class), null, null);
        
        // FIXME: this really ought to be in clinit, but it doesn't matter much
        initMethod.aload(THIS);
        initMethod.ldc(c(getClassname()));
        initMethod.invokestatic(p(Class.class), "forName", sig(Class.class, params(String.class)));
        initMethod.putfield(getClassname(), "$class", ci(Class.class));
        
        // JRUBY-3014: make __FILE__ dynamically determined at load time, but
        // we provide a reasonable default here
        initMethod.aload(THIS);
        initMethod.ldc(getSourcename());
        initMethod.putfield(getClassname(), "filename", ci(String.class));
    }

    private void endInit() {
        initMethod.voidreturn();
        initMethod.end();
    }

    private void beginClassInit() {
        ClassVisitor cv = getClassVisitor();

        clinitMethod = new SkinnyMethodAdapter(cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "<clinit>", sig(Void.TYPE), null, null));
        clinitMethod.start();

        if (invDynSupportInstaller != null) {
            // install invokedynamic bootstrapper
            // TODO need to abstract this setup behind another compiler interface
            try {
                invDynSupportInstaller.invoke(null, clinitMethod,getClassname());
            } catch (IllegalAccessException ex) {
                // ignore; we won't use invokedynamic
            } catch (IllegalArgumentException ex) {
                // ignore; we won't use invokedynamic
            } catch (InvocationTargetException ex) {
                // ignore; we won't use invokedynamic
            }
        }
    }

    private void endClassInit() {
        clinitMethod.voidreturn();
        clinitMethod.end();
    }
    
    public SkinnyMethodAdapter getInitMethod() {
        return initMethod;
    }
    
    public SkinnyMethodAdapter getClassInitMethod() {
        return clinitMethod;
    }
    
    public CacheCompiler getCacheCompiler() {
        return cacheCompiler;
    }
    
    public BodyCompiler startMethod(String rubyName, String javaName, CompilerCallback args, StaticScope scope, ASTInspector inspector) {
        RootScopedBodyCompiler methodCompiler = new MethodBodyCompiler(this, rubyName, javaName, inspector, scope);
        
        methodCompiler.beginMethod(args, scope);
        
        // Emite a nop, to mark the end of the method preamble
        methodCompiler.method.nop();
        
        return methodCompiler;
    }

    public int getMethodIndex() {
        return methodIndex;
    }
    
    public int getAndIncrementMethodIndex() {
        return methodIndex++;
    }

    public int getInnerIndex() {
        return innerIndex;
    }

    public int getAndIncrementInnerIndex() {
        return innerIndex++;
    }

    public int getRescueNumber() {
        return rescueNumber;
    }

    public int getAndIncrementRescueNumber() {
        return rescueNumber++;
    }

    public int getEnsureNumber() {
        return ensureNumber;
    }

    public int getAndIncrementEnsureNumber() {
        return ensureNumber++;
    }

    private int constants = 0;

    public String getNewConstant(String type, String name_prefix) {
        return getNewConstant(type, name_prefix, null);
    }

    public String getNewConstant(String type, String name_prefix, Object init) {
        ClassVisitor cv = getClassVisitor();

        String realName;
        synchronized (this) {
            realName = "_" + constants++;
        }

        // declare the field
        cv.visitField(ACC_PRIVATE, realName, type, null, null).visitEnd();

        if(init != null) {
            initMethod.aload(THIS);
            initMethod.ldc(init);
            initMethod.putfield(getClassname(),realName, type);
        }

        return realName;
    }

    public String getNewField(String type, String name, Object init) {
        ClassVisitor cv = getClassVisitor();

        // declare the field
        cv.visitField(ACC_PRIVATE, name, type, null, null).visitEnd();

        if(init != null) {
            initMethod.aload(THIS);
            initMethod.ldc(init);
            initMethod.putfield(getClassname(),name, type);
        }

        return name;
    }

    public String getNewStaticConstant(String type, String name_prefix) {
        ClassVisitor cv = getClassVisitor();

        String realName;
        synchronized (this) {
            realName = "__" + constants++;
        }

        // declare the field
        cv.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, realName, type, null, null).visitEnd();
        return realName;
    }
}
