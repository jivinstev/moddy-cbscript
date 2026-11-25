// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.moddingfromamod.cbscript.py.PyException;

/**
 * One compile's process-wide state. The Python compiler keeps some state in modules (the warn-once sets, the target
 * version read from the environment); the mod runs one Python process per compile, so here it lives per compile.
 */
public final class Ctx {
    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    public final Log log = new Log();
    public final McTarget target;
    public final SourceFiles files;
    public final Set<Object> nbtWarned = new HashSet<>();
    public final Set<Object> attributeWarned = new HashSet<>();
    public final Set<Object> componentWarned = new HashSet<>();
    public final Set<Object> lootWarned = new HashSet<>();

    Ctx(McTarget target, SourceFiles files) {
        this.target = target;
        this.files = files;
    }

    public static Ctx get() {
        return CURRENT.get();
    }

    static void set(Ctx ctx) {
        CURRENT.set(ctx);
    }

    public static void print(String s) {
        CURRENT.get().log.print(s);
    }

    /** Python {@code print(traceback.format_exc())} inside an except block that caught {@code e}. */
    public static void printTraceback(PyException e) {
        CURRENT.get().log.print(Log.traceback(e));
    }

    /**
     * The body of a Python {@code except} block that caught {@code e}: anything raised inside gets {@code e} as its
     * context, as Python links it.
     */
    public static <T> T except(PyException e, Supplier<T> body) {
        PyException.handling(e);
        try {
            return body.get();
        } finally {
            PyException.done();
        }
    }

    public static void except(PyException e, Runnable body) {
        PyException.handling(e);
        try {
            body.run();
        } finally {
            PyException.done();
        }
    }

    public static PyException compileError(String msg) {
        return new PyException("CompileError", msg);
    }

    public static PyException syntaxError(String msg) {
        return new PyException("SyntaxError", msg);
    }

    /** A Java failure inside the compiler, surfaced as a Python exception so it is refused, not crashed on. */
    public static PyException wrap(RuntimeException e) {
        if (e instanceof PyException p) {
            return p;
        }
        return new PyException("InternalError", e.toString());
    }
}
