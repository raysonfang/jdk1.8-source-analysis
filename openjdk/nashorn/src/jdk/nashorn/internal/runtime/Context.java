/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.runtime;

import static jdk.nashorn.internal.codegen.CompilerConstants.RUN_SCRIPT;
import static jdk.nashorn.internal.codegen.CompilerConstants.STRICT_MODE;
import static jdk.nashorn.internal.lookup.Lookup.MH;
import static jdk.nashorn.internal.runtime.ECMAErrors.typeError;
import static jdk.nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Map;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.util.CheckClassAdapter;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import jdk.nashorn.internal.codegen.Compiler;
import jdk.nashorn.internal.codegen.ObjectClassGenerator;
import jdk.nashorn.internal.ir.FunctionNode;
import jdk.nashorn.internal.ir.debug.ASTWriter;
import jdk.nashorn.internal.ir.debug.PrintVisitor;
import jdk.nashorn.internal.objects.Global;
import jdk.nashorn.internal.parser.Parser;
import jdk.nashorn.internal.runtime.options.Options;

/**
 * This class manages the global state of execution. Context is immutable.
 */
public final class Context {
    // nashorn specific security runtime access permission names
    /**
     * Permission needed to pass arbitrary nashorn command line options when creating Context.
     */
    public static final String NASHORN_SET_CONFIG      = "nashorn.setConfig";

    /**
     * Permission needed to create Nashorn Context instance.
     */
    public static final String NASHORN_CREATE_CONTEXT  = "nashorn.createContext";

    /**
     * Permission needed to create Nashorn Global instance.
     */
    public static final String NASHORN_CREATE_GLOBAL   = "nashorn.createGlobal";

    /**
     * Permission to get current Nashorn Context from thread local storage.
     */
    public static final String NASHORN_GET_CONTEXT     = "nashorn.getContext";

    /**
     * Permission to use Java reflection/jsr292 from script code.
     */
    public static final String NASHORN_JAVA_REFLECTION = "nashorn.JavaReflection";

    // nashorn load psuedo URL prefixes
    private static final String LOAD_CLASSPATH = "classpath:";
    private static final String LOAD_FX = "fx:";
    private static final String LOAD_NASHORN = "nashorn:";

    /* Force DebuggerSupport to be loaded. */
    static {
        DebuggerSupport.FORCELOAD = true;
    }

    /**
     * ContextCodeInstaller that has the privilege of installing classes in the Context.
     * Can only be instantiated from inside the context and is opaque to other classes
     */
    public static class ContextCodeInstaller implements CodeInstaller<ScriptEnvironment> {
        private final Context      context;
        private final ScriptLoader loader;
        private final CodeSource   codeSource;

        private ContextCodeInstaller(final Context context, final ScriptLoader loader, final CodeSource codeSource) {
            this.context    = context;
            this.loader     = loader;
            this.codeSource = codeSource;
        }

        /**
         * Return the context for this installer
         * @return ScriptEnvironment
         */
        @Override
        public ScriptEnvironment getOwner() {
            return context.env;
        }

        @Override
        public Class<?> install(final String className, final byte[] bytecode) {
            return loader.installClass(className, bytecode, codeSource);
        }

        @Override
        public void verify(final byte[] code) {
            context.verify(code);
        }

        @Override
        public long getUniqueScriptId() {
            return context.getUniqueScriptId();
        }

        @Override
        public long getUniqueEvalId() {
            return context.getUniqueEvalId();
        }
    }

    /** Is Context global debug mode enabled ? */
    public static final boolean DEBUG = Options.getBooleanProperty("nashorn.debug");

    private static final ThreadLocal<ScriptObject> currentGlobal = new ThreadLocal<>();

    /**
     * Get the current global scope
     * @return the current global scope
     */
    public static ScriptObject getGlobal() {
        // This class in a package.access protected package.
        // Trusted code only can call this method.
        return getGlobalTrusted();
    }

    /**
     * Set the current global scope
     * @param global the global scope
     */
    public static void setGlobal(final ScriptObject global) {
        if (global != null && !(global instanceof Global)) {
            throw new IllegalArgumentException("global is not an instance of Global!");
        }

        setGlobalTrusted(global);
    }

    /**
     * Get context of the current global
     * @return current global scope's context.
     */
    public static Context getContext() {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission(NASHORN_GET_CONTEXT));
        }
        return getContextTrusted();
    }

    /**
     * Get current context's error writer
     *
     * @return error writer of the current context
     */
    public static PrintWriter getCurrentErr() {
        final ScriptObject global = getGlobalTrusted();
        return (global != null)? global.getContext().getErr() : new PrintWriter(System.err);
    }

    /**
     * Output text to this Context's error stream
     * @param str text to write
     */
    public static void err(final String str) {
        err(str, true);
    }

    /**
     * Output text to this Context's error stream, optionally with
     * a newline afterwards
     *
     * @param str  text to write
     * @param crlf write a carriage return/new line after text
     */
    @SuppressWarnings("resource")
    public static void err(final String str, final boolean crlf) {
        final PrintWriter err = Context.getCurrentErr();
        if (err != null) {
            if (crlf) {
                err.println(str);
            } else {
                err.print(str);
            }
        }
    }

    /** Current environment. */
    private final ScriptEnvironment env;

    /** is this context in strict mode? Cached from env. as this is used heavily. */
    final boolean _strict;

    /** class loader to resolve classes from script. */
    private final ClassLoader  appLoader;

    /** Class loader to load classes from -classpath option, if set. */
    private final ClassLoader  classPathLoader;

    /** Class loader to load classes compiled from scripts. */
    private final ScriptLoader scriptLoader;

    /** Current error manager. */
    private final ErrorManager errors;

    /** Unique id for script. Used only when --loader-per-compile=false */
    private final AtomicLong uniqueScriptId;

    /** Unique id for 'eval' */
    private final AtomicLong uniqueEvalId;

    private static final ClassLoader myLoader = Context.class.getClassLoader();
    private static final StructureLoader sharedLoader;

    /*package-private*/ @SuppressWarnings("static-method")
    ClassLoader getSharedLoader() {
        return sharedLoader;
    }

    private static AccessControlContext createNoPermAccCtxt() {
        return new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, new Permissions()) });
    }

    private static AccessControlContext createPermAccCtxt(final String permName) {
        final Permissions perms = new Permissions();
        perms.add(new RuntimePermission(permName));
        return new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, perms) });
    }

    private static final AccessControlContext NO_PERMISSIONS_ACC_CTXT = createNoPermAccCtxt();
    private static final AccessControlContext CREATE_LOADER_ACC_CTXT  = createPermAccCtxt("createClassLoader");
    private static final AccessControlContext CREATE_GLOBAL_ACC_CTXT  = createPermAccCtxt(NASHORN_CREATE_GLOBAL);

    static {
        sharedLoader = AccessController.doPrivileged(new PrivilegedAction<StructureLoader>() {
            @Override
            public StructureLoader run() {
                return new StructureLoader(myLoader);
            }
        }, CREATE_LOADER_ACC_CTXT);
    }

    /**
     * ThrowErrorManager that throws ParserException upon error conditions.
     */
    public static class ThrowErrorManager extends ErrorManager {
        @Override
        public void error(final String message) {
            throw new ParserException(message);
        }

        @Override
        public void error(final ParserException e) {
            throw e;
        }
    }

    /**
     * Constructor
     *
     * @param options options from command line or Context creator
     * @param errors  error manger
     * @param appLoader application class loader
     */
    public Context(final Options options, final ErrorManager errors, final ClassLoader appLoader) {
        this(options, errors, new PrintWriter(System.out, true), new PrintWriter(System.err, true), appLoader);
    }

    /**
     * Constructor
     *
     * @param options options from command line or Context creator
     * @param errors  error manger
     * @param out     output writer for this Context
     * @param err     error writer for this Context
     * @param appLoader application class loader
     */
    public Context(final Options options, final ErrorManager errors, final PrintWriter out, final PrintWriter err, final ClassLoader appLoader) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission(NASHORN_CREATE_CONTEXT));
        }

        this.env       = new ScriptEnvironment(options, out, err);
        this._strict   = env._strict;
        this.appLoader = appLoader;
        if (env._loader_per_compile) {
            this.scriptLoader = null;
            this.uniqueScriptId = null;
        } else {
            this.scriptLoader = createNewLoader();
            this.uniqueScriptId = new AtomicLong();
        }
        this.errors    = errors;
        this.uniqueEvalId = new AtomicLong();

        // if user passed -classpath option, make a class loader with that and set it as
        // thread context class loader so that script can access classes from that path.
        final String classPath = options.getString("classpath");
        if (! env._compile_only && classPath != null && !classPath.isEmpty()) {
            // make sure that caller can create a class loader.
            if (sm != null) {
                sm.checkPermission(new RuntimePermission("createClassLoader"));
            }
            this.classPathLoader = NashornLoader.createClassLoader(classPath);
        } else {
            this.classPathLoader = null;
        }

        // print version info if asked.
        if (env._version) {
            getErr().println("nashorn " + Version.version());
        }

        if (env._fullversion) {
            getErr().println("nashorn full version " + Version.fullVersion());
        }
    }

    /**
     * Get the error manager for this context
     * @return error manger
     */
    public ErrorManager getErrorManager() {
        return errors;
    }

    /**
     * Get the script environment for this context
     * @return script environment
     */
    public ScriptEnvironment getEnv() {
        return env;
    }

    /**
     * Get the output stream for this context
     * @return output print writer
     */
    public PrintWriter getOut() {
        return env.getOut();
    }

    /**
     * Get the error stream for this context
     * @return error print writer
     */
    public PrintWriter getErr() {
        return env.getErr();
    }

    /**
     * Get the PropertyMap of the current global scope
     * @return the property map of the current global scope
     */
    public static PropertyMap getGlobalMap() {
        return Context.getGlobalTrusted().getMap();
    }

    /**
     * Compile a top level script.
     *
     * @param source the source
     * @param scope  the scope
     *
     * @return top level function for script
     */
    public ScriptFunction compileScript(final Source source, final ScriptObject scope) {
        return compileScript(source, scope, this.errors);
    }

    /**
     * Entry point for {@code eval}
     *
     * @param initialScope The scope of this eval call
     * @param string       Evaluated code as a String
     * @param callThis     "this" to be passed to the evaluated code
     * @param location     location of the eval call
     * @param strict       is this {@code eval} call from a strict mode code?
     *
     * @return the return value of the {@code eval}
     */
    public Object eval(final ScriptObject initialScope, final String string, final Object callThis, final Object location, final boolean strict) {
        final String  file       = (location == UNDEFINED || location == null) ? "<eval>" : location.toString();
        final Source  source     = new Source(file, string);
        final boolean directEval = location != UNDEFINED; // is this direct 'eval' call or indirectly invoked eval?
        final ScriptObject global = Context.getGlobalTrusted();

        ScriptObject scope = initialScope;

        // ECMA section 10.1.1 point 2 says eval code is strict if it begins
        // with "use strict" directive or eval direct call itself is made
        // from from strict mode code. We are passed with caller's strict mode.
        boolean strictFlag = directEval && strict;

        Class<?> clazz = null;
        try {
            clazz = compile(source, new ThrowErrorManager(), strictFlag);
        } catch (final ParserException e) {
            e.throwAsEcmaException(global);
            return null;
        }

        if (!strictFlag) {
            // We need to get strict mode flag from compiled class. This is
            // because eval code may start with "use strict" directive.
            try {
                strictFlag = clazz.getField(STRICT_MODE.symbolName()).getBoolean(null);
            } catch (final NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
                //ignored
                strictFlag = false;
            }
        }

        // In strict mode, eval does not instantiate variables and functions
        // in the caller's environment. A new environment is created!
        if (strictFlag) {
            // Create a new scope object
            final ScriptObject strictEvalScope = ((GlobalObject)global).newObject();

            // bless it as a "scope"
            strictEvalScope.setIsScope();

            // set given scope to be it's proto so that eval can still
            // access caller environment vars in the new environment.
            strictEvalScope.setProto(scope);
            scope = strictEvalScope;
        }

        ScriptFunction func = getRunScriptFunction(clazz, scope);
        Object evalThis;
        if (directEval) {
            evalThis = (callThis instanceof ScriptObject || strictFlag) ? callThis : global;
        } else {
            evalThis = global;
        }

        return ScriptRuntime.apply(func, evalThis);
    }

    private static Source loadInternal(final String srcStr, final String prefix, final String resourcePath) {
        if (srcStr.startsWith(prefix)) {
            final String resource = resourcePath + srcStr.substring(prefix.length());
            // NOTE: even sandbox scripts should be able to load scripts in nashorn: scheme
            // These scripts are always available and are loaded from nashorn.jar's resources.
            return AccessController.doPrivileged(
                    new PrivilegedAction<Source>() {
                        @Override
                        public Source run() {
                            try {
                                final URL resURL = Context.class.getResource(resource);
                                return (resURL != null)? new Source(srcStr, resURL) : null;
                            } catch (final IOException exp) {
                                return null;
                            }
                        }
                    });
        }

        return null;
    }

    /**
     * Implementation of {@code load} Nashorn extension. Load a script file from a source
     * expression
     *
     * @param scope  the scope
     * @param from   source expression for script
     *
     * @return return value for load call (undefined)
     *
     * @throws IOException if source cannot be found or loaded
     */
    public Object load(final ScriptObject scope, final Object from) throws IOException {
        final Object src = (from instanceof ConsString)?  from.toString() : from;
        Source source = null;

        // load accepts a String (which could be a URL or a file name), a File, a URL
        // or a ScriptObject that has "name" and "source" (string valued) properties.
        if (src instanceof String) {
            final String srcStr = (String)src;
            if (srcStr.startsWith(LOAD_CLASSPATH)) {
                URL url = getResourceURL(srcStr.substring(LOAD_CLASSPATH.length()));
                source = (url != null)? new Source(url.toString(), url) : null;
            } else {
                final File file = new File(srcStr);
                if (srcStr.indexOf(':') != -1) {
                    if ((source = loadInternal(srcStr, LOAD_NASHORN, "resources/")) == null &&
                        (source = loadInternal(srcStr, LOAD_FX, "resources/fx/")) == null) {
                        URL url;
                        try {
                            //check for malformed url. if malformed, it may still be a valid file
                            url = new URL(srcStr);
                        } catch (final MalformedURLException e) {
                            url = file.toURI().toURL();
                        }
                        source = new Source(url.toString(), url);
                    }
                } else if (file.isFile()) {
                    source = new Source(srcStr, file);
                }
            }
        } else if (src instanceof File && ((File)src).isFile()) {
            final File file = (File)src;
            source = new Source(file.getName(), file);
        } else if (src instanceof URL) {
            final URL url = (URL)src;
            source = new Source(url.toString(), url);
        } else if (src instanceof ScriptObject) {
            final ScriptObject sobj = (ScriptObject)src;
            if (sobj.has("script") && sobj.has("name")) {
                final String script = JSType.toString(sobj.get("script"));
                final String name   = JSType.toString(sobj.get("name"));
                source = new Source(name, script);
            }
        } else if (src instanceof Map) {
            final Map<?,?> map = (Map<?,?>)src;
            if (map.containsKey("script") && map.containsKey("name")) {
                final String script = JSType.toString(map.get("script"));
                final String name   = JSType.toString(map.get("name"));
                source = new Source(name, script);
            }
        }

        if (source != null) {
            return evaluateSource(source, scope, scope);
        }

        throw typeError("cant.load.script", ScriptRuntime.safeToString(from));
    }

    /**
     * Implementation of {@code loadWithNewGlobal} Nashorn extension. Load a script file from a source
     * expression, after creating a new global scope.
     *
     * @param from source expression for script
     * @param args (optional) arguments to be passed to the loaded script
     *
     * @return return value for load call (undefined)
     *
     * @throws IOException if source cannot be found or loaded
     */
    public Object loadWithNewGlobal(final Object from, final Object...args) throws IOException {
        final ScriptObject oldGlobal = getGlobalTrusted();
        final ScriptObject newGlobal = AccessController.doPrivileged(new PrivilegedAction<ScriptObject>() {
           @Override
           public ScriptObject run() {
               try {
                   return newGlobal();
               } catch (final RuntimeException e) {
                   if (Context.DEBUG) {
                       e.printStackTrace();
                   }
                   throw e;
               }
           }
        }, CREATE_GLOBAL_ACC_CTXT);
        // initialize newly created Global instance
        initGlobal(newGlobal);
        setGlobalTrusted(newGlobal);

        final Object[] wrapped = args == null? ScriptRuntime.EMPTY_ARRAY :  ScriptObjectMirror.wrapArray(args, oldGlobal);
        newGlobal.put("arguments", ((GlobalObject)newGlobal).wrapAsObject(wrapped), env._strict);

        try {
            // wrap objects from newGlobal's world as mirrors - but if result
            // is from oldGlobal's world, unwrap it!
            return ScriptObjectMirror.unwrap(ScriptObjectMirror.wrap(load(newGlobal, from), newGlobal), oldGlobal);
        } finally {
            setGlobalTrusted(oldGlobal);
        }
    }

    /**
     * Load or get a structure class. Structure class names are based on the number of parameter fields
     * and {@link AccessorProperty} fields in them. Structure classes are used to represent ScriptObjects
     *
     * @see ObjectClassGenerator
     * @see AccessorProperty
     * @see ScriptObject
     *
     * @param fullName  full name of class, e.g. jdk.nashorn.internal.objects.JO2P1 contains 2 fields and 1 parameter.
     *
     * @return the {@code Class<?>} for this structure
     *
     * @throws ClassNotFoundException if structure class cannot be resolved
     */
    public static Class<?> forStructureClass(final String fullName) throws ClassNotFoundException {
        if (System.getSecurityManager() != null && !StructureLoader.isStructureClass(fullName)) {
            throw new ClassNotFoundException(fullName);
        }
        return Class.forName(fullName, true, sharedLoader);
    }

    /**
     * Checks that the given Class can be accessed from no permissions context.
     *
     * @param clazz Class object
     * @throw SecurityException if not accessible
     */
    public static void checkPackageAccess(final Class<?> clazz) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            Class<?> bottomClazz = clazz;
            while (bottomClazz.isArray()) {
                bottomClazz = bottomClazz.getComponentType();
            }
            checkPackageAccess(sm, bottomClazz.getName());
        }
    }

    /**
     * Checks that the given package name can be accessed from no permissions context.
     *
     * @param pkgName package name
     * @throw SecurityException if not accessible
     */
    public static void checkPackageAccess(final String pkgName) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            checkPackageAccess(sm, pkgName.endsWith(".")? pkgName : pkgName + ".");
        }
    }

    /**
     * Checks that the given package can be accessed from no permissions context.
     *
     * @param sm current security manager instance
     * @param fullName fully qualified package name
     * @throw SecurityException if not accessible
     */
    private static void checkPackageAccess(final SecurityManager sm, final String fullName) {
        sm.getClass(); // null check
        final int index = fullName.lastIndexOf('.');
        if (index != -1) {
            final String pkgName = fullName.substring(0, index);
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    sm.checkPackageAccess(pkgName);
                    return null;
                }
            }, NO_PERMISSIONS_ACC_CTXT);
        }
    }

    /**
     * Checks that the given Class can be accessed from no permissions context.
     *
     * @param clazz Class object
     * @return true if package is accessible, false otherwise
     */
    private static boolean isAccessiblePackage(final Class<?> clazz) {
        try {
            checkPackageAccess(clazz);
            return true;
        } catch (final SecurityException se) {
            return false;
        }
    }

    /**
     * Checks that the given Class is public and it can be accessed from no permissions context.
     *
     * @param clazz Class object to check
     * @return true if Class is accessible, false otherwise
     */
    public static boolean isAccessibleClass(final Class<?> clazz) {
        return Modifier.isPublic(clazz.getModifiers()) && Context.isAccessiblePackage(clazz);
    }

    /**
     * Lookup a Java class. This is used for JSR-223 stuff linking in from
     * {@code jdk.nashorn.internal.objects.NativeJava} and {@code jdk.nashorn.internal.runtime.NativeJavaPackage}
     *
     * @param fullName full name of class to load
     *
     * @return the {@code Class<?>} for the name
     *
     * @throws ClassNotFoundException if class cannot be resolved
     */
    public Class<?> findClass(final String fullName) throws ClassNotFoundException {
        if (fullName.indexOf('[') != -1 || fullName.indexOf('/') != -1) {
            // don't allow array class names or internal names.
            throw new ClassNotFoundException(fullName);
        }

        // check package access as soon as possible!
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            checkPackageAccess(sm, fullName);
        }

        // try the script -classpath loader, if that is set
        if (classPathLoader != null) {
            try {
                return Class.forName(fullName, true, classPathLoader);
            } catch (final ClassNotFoundException ignored) {
                // ignore, continue search
            }
        }

        // Try finding using the "app" loader.
        return Class.forName(fullName, true, appLoader);
    }

    /**
     * Hook to print stack trace for a {@link Throwable} that occurred during
     * execution
     *
     * @param t throwable for which to dump stack
     */
    public static void printStackTrace(final Throwable t) {
        if (Context.DEBUG) {
            t.printStackTrace(Context.getCurrentErr());
        }
    }

    /**
     * Verify generated bytecode before emission. This is called back from the
     * {@link ObjectClassGenerator} or the {@link Compiler}. If the "--verify-code" parameter
     * hasn't been given, this is a nop
     *
     * Note that verification may load classes -- we don't want to do that unless
     * user specified verify option. We check it here even though caller
     * may have already checked that flag
     *
     * @param bytecode bytecode to verify
     */
    public void verify(final byte[] bytecode) {
        if (env._verify_code) {
            // No verification when security manager is around as verifier
            // may load further classes - which should be avoided.
            if (System.getSecurityManager() == null) {
                CheckClassAdapter.verify(new ClassReader(bytecode), sharedLoader, false, new PrintWriter(System.err, true));
            }
        }
    }

    /**
     * Create and initialize a new global scope object.
     *
     * @return the initialized global scope object.
     */
    public ScriptObject createGlobal() {
        return initGlobal(newGlobal());
    }

    /**
     * Create a new uninitialized global scope object
     * @return the global script object
     */
    public ScriptObject newGlobal() {
        return new Global(this);
    }

    /**
     * Initialize given global scope object.
     *
     * @param global the global
     * @return the initialized global scope object.
     */
    public ScriptObject initGlobal(final ScriptObject global) {
        if (! (global instanceof GlobalObject)) {
            throw new IllegalArgumentException("not a global object!");
        }

        // Need only minimal global object, if we are just compiling.
        if (!env._compile_only) {
            final ScriptObject oldGlobal = Context.getGlobalTrusted();
            try {
                Context.setGlobalTrusted(global);
                // initialize global scope with builtin global objects
                ((GlobalObject)global).initBuiltinObjects();
            } finally {
                Context.setGlobalTrusted(oldGlobal);
            }
        }

        return global;
    }

    /**
     * Trusted variants - package-private
     */

    /**
     * Return the current global scope
     * @return current global scope
     */
    static ScriptObject getGlobalTrusted() {
        return currentGlobal.get();
    }

    /**
     * Set the current global scope
     */
    static void setGlobalTrusted(ScriptObject global) {
         currentGlobal.set(global);
    }

    /**
     * Return the current global's context
     * @return current global's context
     */
    static Context getContextTrusted() {
        return Context.getGlobalTrusted().getContext();
    }

    /**
     * Try to infer Context instance from the Class. If we cannot,
     * then get it from the thread local variable.
     *
     * @param clazz the class
     * @return context
     */
    static Context fromClass(final Class<?> clazz) {
        final ClassLoader loader = clazz.getClassLoader();

        if (loader instanceof ScriptLoader) {
            return ((ScriptLoader)loader).getContext();
        }

        return Context.getContextTrusted();
    }

    private URL getResourceURL(final String resName) {
        // try the classPathLoader if we have and then
        // try the appLoader if non-null.
        if (classPathLoader != null) {
            return classPathLoader.getResource(resName);
        } else if (appLoader != null) {
            return appLoader.getResource(resName);
        }

        return null;
    }

    private Object evaluateSource(final Source source, final ScriptObject scope, final ScriptObject thiz) {
        ScriptFunction script = null;

        try {
            script = compileScript(source, scope, new Context.ThrowErrorManager());
        } catch (final ParserException e) {
            e.throwAsEcmaException();
        }

        return ScriptRuntime.apply(script, thiz);
    }

    private static ScriptFunction getRunScriptFunction(final Class<?> script, final ScriptObject scope) {
        if (script == null) {
            return null;
        }

        // Get run method - the entry point to the script
        final MethodHandle runMethodHandle =
                MH.findStatic(
                    MethodHandles.lookup(),
                    script,
                    RUN_SCRIPT.symbolName(),
                    MH.type(
                        Object.class,
                        ScriptFunction.class,
                        Object.class));

        boolean strict;

        try {
            strict = script.getField(STRICT_MODE.symbolName()).getBoolean(null);
        } catch (final NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            strict = false;
        }

        // Package as a JavaScript function and pass function back to shell.
        return ((GlobalObject)Context.getGlobalTrusted()).newScriptFunction(RUN_SCRIPT.symbolName(), runMethodHandle, scope, strict);
    }

    private ScriptFunction compileScript(final Source source, final ScriptObject scope, final ErrorManager errMan) {
        return getRunScriptFunction(compile(source, errMan, this._strict), scope);
    }

    private synchronized Class<?> compile(final Source source, final ErrorManager errMan, final boolean strict) {
        // start with no errors, no warnings.
        errMan.reset();

        GlobalObject global = null;
        Class<?> script;

        if (env._class_cache_size > 0) {
            global = (GlobalObject)Context.getGlobalTrusted();
            script = global.findCachedClass(source);
            if (script != null) {
                Compiler.LOG.fine("Code cache hit for ", source, " avoiding recompile.");
                return script;
            }
        }

        final FunctionNode functionNode = new Parser(env, source, errMan, strict).parse();
        if (errors.hasErrors()) {
            return null;
        }

        if (env._print_ast) {
            getErr().println(new ASTWriter(functionNode));
        }

        if (env._print_parse) {
            getErr().println(new PrintVisitor(functionNode));
        }

        if (env._parse_only) {
            return null;
        }

        final URL          url    = source.getURL();
        final ScriptLoader loader = env._loader_per_compile ? createNewLoader() : scriptLoader;
        final CodeSource   cs     = url == null ? null : new CodeSource(url, (CodeSigner[])null);
        final CodeInstaller<ScriptEnvironment> installer = new ContextCodeInstaller(this, loader, cs);

        final Compiler compiler = new Compiler(installer, strict);

        final FunctionNode newFunctionNode = compiler.compile(functionNode);
        script = compiler.install(newFunctionNode);

        if (global != null) {
            global.cacheClass(source, script);
        }

        return script;
    }

    private ScriptLoader createNewLoader() {
        return AccessController.doPrivileged(
             new PrivilegedAction<ScriptLoader>() {
                @Override
                public ScriptLoader run() {
                    return new ScriptLoader(appLoader, Context.this);
                }
             }, CREATE_LOADER_ACC_CTXT);
    }

    private long getUniqueEvalId() {
        return uniqueEvalId.getAndIncrement();
    }

    private long getUniqueScriptId() {
        return uniqueScriptId.getAndIncrement();
    }
}
