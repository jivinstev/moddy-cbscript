// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A callable: a built-in function, a bound method, a lambda, or a function the compiler supplies (e.g.
 * {@code get_num_blocks}): {@code new PyFunction("name", (args, kwargs) -> ...)}.
 */
public class PyFunction extends PyObject {
    private final String name;
    private final BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> body;
    /** For a bound method: the type name of the object it is bound to; null for a function. */
    private final String boundTo;
    private final boolean lambda;

    public PyFunction(String name, BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> body) {
        this(name, body, null, false);
    }

    PyFunction(String name, BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> body, String boundTo,
            boolean lambda) {
        this.name = name;
        this.body = body;
        this.boundTo = boundTo;
        this.lambda = lambda;
    }

    /** The function's name. */
    public String name() {
        return name;
    }

    @Override
    public String typeName() {
        return lambda ? "function" : "builtin_function_or_method";
    }

    @Override
    public String repr() {
        if (lambda) {
            return "<function " + name + " at " + Py.fakeAddress(this) + ">";
        }
        if (boundTo != null) {
            return "<built-in method " + name + " of " + boundTo + " object at " + Py.fakeAddress(this) + ">";
        }
        return "<built-in function " + name + ">";
    }

    @Override
    public PyObject call(List<PyObject> args, Map<String, PyObject> kwargs) {
        return body.apply(args, kwargs);
    }

    @Override
    public long hash() {
        return Py.identityHash(this);
    }
}
