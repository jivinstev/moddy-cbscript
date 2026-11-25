// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.List;

/** Python's {@code list}. */
public final class PyList extends PySequence {
    private final List<PyObject> items;

    /** A list over {@code items} (used directly, not copied). */
    public PyList(List<PyObject> items) {
        this.items = items;
    }

    public PyList() {
        this(new ArrayList<>());
    }

    @Override
    public List<PyObject> items() {
        return items;
    }

    @Override
    PyObject make(List<PyObject> items) {
        return new PyList(items);
    }

    @Override
    public String typeName() {
        return "list";
    }

    @Override
    public String repr() {
        String inner = joinReprs();
        return inner == null ? "[...]" : "[" + inner + "]";
    }
}
