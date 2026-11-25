// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.Collections;
import java.util.List;

/** Python's {@code tuple}. */
public final class PyTuple extends PySequence {
    static final PyTuple EMPTY = new PyTuple(List.of());

    private final List<PyObject> items;

    /** A tuple of {@code items} (wrapped unmodifiable, not copied). */
    public PyTuple(List<PyObject> items) {
        this.items = Collections.unmodifiableList(items);
    }

    static PyTuple of(List<PyObject> items) {
        return items.isEmpty() ? EMPTY : new PyTuple(items);
    }

    @Override
    public List<PyObject> items() {
        return items;
    }

    @Override
    PyObject make(List<PyObject> items) {
        return of(items);
    }

    @Override
    public String typeName() {
        return "tuple";
    }

    @Override
    public String repr() {
        if (items.size() == 1) {
            String inner = joinReprs();
            return inner == null ? "(...)" : "(" + inner + ",)";
        }
        String inner = joinReprs();
        return inner == null ? "(...)" : "(" + inner + ")";
    }

    @Override
    public long hash() {
        return Py.tupleHash(items);
    }
}
