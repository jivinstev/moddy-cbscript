// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * A type object ({@code int}, {@code str}, {@code list}, ...): callable as its constructor, usable with
 * {@code isinstance}, and the result of {@code type(x)}. Attribute access gives unbound methods
 * ({@code str.upper("a")}).
 */
public final class PyType extends PyObject {
    private static final Map<String, PyType> TYPES = new ConcurrentHashMap<>();

    private final String name;
    private final String base;
    private final BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> constructor;

    private PyType(String name, String base, BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> ctor) {
        this.name = name;
        this.base = base;
        this.constructor = ctor;
    }

    static PyType define(String name, String base,
            BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> ctor) {
        PyType t = new PyType(name, base, ctor);
        TYPES.put(name, t);
        return t;
    }

    /** The type object for a type name (a non-callable one for types without a constructor here). */
    static PyType of(String name) {
        Builtins.ensureLoaded();
        return TYPES.computeIfAbsent(name, n -> new PyType(n, "object", null));
    }

    /** The type's name. */
    public String name() {
        return name;
    }

    /** True if an object of type {@code typeName} is an instance of this type. */
    boolean isInstance(PyObject o) {
        if (name.equals("object")) {
            return true;
        }
        String t = o.typeName();
        while (t != null) {
            if (t.equals(name)) {
                return true;
            }
            PyType ty = TYPES.get(t);
            t = ty == null || ty.base == null || ty.base.equals("object") ? null : ty.base;
        }
        return false;
    }

    @Override
    public String typeName() {
        return "type";
    }

    @Override
    public String repr() {
        return "<class '" + name + "'>";
    }

    @Override
    public PyObject call(List<PyObject> args, Map<String, PyObject> kwargs) {
        if (constructor == null) {
            throw PyException.typeError("cannot create '" + name + "' instances");
        }
        return constructor.apply(args, kwargs);
    }

    @Override
    public PyObject getattr(String attr) {
        return Methods.unbound(this, attr);
    }

    @Override
    public long hash() {
        return Py.identityHash(this);
    }
}
