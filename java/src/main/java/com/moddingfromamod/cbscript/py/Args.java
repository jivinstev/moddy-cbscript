// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.List;
import java.util.Map;

/** Argument checking for built-in functions and methods, with CPython's error messages. */
final class Args {
    private Args() {}

    static void noKwargs(String fn, Map<String, PyObject> kw) {
        if (kw != null && !kw.isEmpty()) {
            throw PyException.typeError(fn + "() takes no keyword arguments");
        }
    }

    /** METH_NOARGS: {@code str.upper() takes no arguments (1 given)}. */
    static void none(String qualname, List<PyObject> a, Map<String, PyObject> kw) {
        noKwargs(qualname, kw);
        if (!a.isEmpty()) {
            throw PyException.typeError(qualname + "() takes no arguments (" + a.size() + " given)");
        }
    }

    /** METH_O: {@code len() takes exactly one argument (2 given)}. */
    static PyObject one(String fn, List<PyObject> a, Map<String, PyObject> kw) {
        noKwargs(fn, kw);
        if (a.size() != 1) {
            throw PyException.typeError(fn + "() takes exactly one argument (" + a.size() + " given)");
        }
        return a.get(0);
    }

    /** Argument Clinic positional-only range: {@code split expected at most 2 arguments, got 3}. */
    static void count(String fn, List<PyObject> a, int min, int max) {
        int n = a.size();
        if (n < min) {
            throw PyException.typeError(fn + " expected at least " + min + " argument" + (min == 1 ? "" : "s")
                    + ", got " + n);
        }
        if (n > max) {
            throw PyException.typeError(fn + " expected at most " + max + " argument" + (max == 1 ? "" : "s")
                    + ", got " + n);
        }
    }

    /** Positional argument i, or keyword {@code name}, or {@code dflt}. Unknown keywords raise TypeError. */
    static PyObject get(List<PyObject> a, Map<String, PyObject> kw, int i, String name, PyObject dflt) {
        if (i < a.size()) {
            if (kw != null && kw.containsKey(name)) {
                throw PyException.typeError("argument for " + name + "() given by name ('" + name
                        + "') and position (" + (i + 1) + ")");
            }
            return a.get(i);
        }
        if (kw != null && kw.containsKey(name)) {
            return kw.get(name);
        }
        return dflt;
    }

    /** Rejects keywords not in {@code allowed}. */
    static void onlyKw(String fn, Map<String, PyObject> kw, String... allowed) {
        if (kw == null) {
            return;
        }
        outer:
        for (String k : kw.keySet()) {
            for (String ok : allowed) {
                if (ok.equals(k)) {
                    continue outer;
                }
            }
            throw PyException.typeError(fn + "() got an unexpected keyword argument '" + k + "'");
        }
    }

    static String str(String fn, PyObject o, int argNo) {
        if (o instanceof PyStr s) {
            return s.value();
        }
        throw PyException.typeError(fn + "() argument " + argNo + " must be str, not " + o.typeName());
    }

    /** An optional int argument where None means "absent" (returns dflt). */
    static long optIndex(PyObject o, long dflt) {
        if (o == null || o == Py.NONE) {
            return dflt;
        }
        PyInt i = Py.index(o);
        if (!i.isLong()) {
            return i.signum() < 0 ? Long.MIN_VALUE / 2 : Long.MAX_VALUE / 2;
        }
        return i.longValue();
    }
}
