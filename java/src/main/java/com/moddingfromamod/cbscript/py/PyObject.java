// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A Python value. Subclasses model the built-in types a compile-time expression can produce; the defaults here raise
 * the {@code TypeError} CPython raises for a type that does not support the operation.
 *
 * <p>Equality, ordering, arithmetic and hashing live in {@link Py} because they dispatch on both operands.
 */
public abstract class PyObject {

    /** The Python type name, as {@code type(x).__name__} gives it: {@code int}, {@code str}, {@code NoneType}... */
    public abstract String typeName();

    /** {@code repr(x)}. */
    public String repr() {
        return "<" + typeName() + " object at " + Py.fakeAddress(this) + ">";
    }

    /** {@code str(x)}. */
    public String str() {
        return repr();
    }

    /** {@code bool(x)}. */
    public boolean truthy() {
        return true;
    }

    /** {@code iter(x)}, as a Java iterator. */
    public Iterator<PyObject> iter() {
        throw PyException.typeError("'" + typeName() + "' object is not iterable");
    }

    /** {@code len(x)}. */
    public int len() {
        throw PyException.typeError("object of type '" + typeName() + "' has no len()");
    }

    /** {@code x[key]}. */
    public PyObject getitem(PyObject key) {
        throw PyException.typeError("'" + typeName() + "' object is not subscriptable");
    }

    /** {@code x.name}. Names starting with an underscore are refused (the evaluator exposes no dunders). */
    public PyObject getattr(String name) {
        return Methods.getattr(this, name);
    }

    /** {@code x(*args, **kwargs)}. */
    public PyObject call(List<PyObject> args, Map<String, PyObject> kwargs) {
        throw PyException.typeError("'" + typeName() + "' object is not callable");
    }

    /** {@code x in self}. */
    public boolean contains(PyObject x) {
        Iterator<PyObject> it;
        try {
            it = iter();
        } catch (PyException e) {
            throw PyException.typeError("argument of type '" + typeName() + "' is not iterable");
        }
        while (it.hasNext()) {
            PyObject o = it.next();
            if (o == x || Py.eq(o, x)) {
                return true;
            }
        }
        return false;
    }

    /** {@code hash(x)} as CPython 3.13 computes it with {@code PYTHONHASHSEED=0}. */
    public long hash() {
        throw PyException.typeError("unhashable type: '" + typeName() + "'");
    }

    @Override
    public String toString() {
        return repr();
    }
}
