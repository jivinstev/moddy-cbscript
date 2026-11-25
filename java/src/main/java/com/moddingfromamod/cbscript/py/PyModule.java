// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.LinkedHashMap;
import java.util.Map;

/** A module object: {@code math} or {@code json}. */
public final class PyModule extends PyObject {
    private final String name;
    private final String reprText;
    final Map<String, PyObject> attrs = new LinkedHashMap<>();

    PyModule(String name, String reprText) {
        this.name = name;
        this.reprText = reprText;
    }

    @Override
    public String typeName() {
        return "module";
    }

    @Override
    public String repr() {
        return reprText;
    }

    @Override
    public PyObject getattr(String attr) {
        PyObject v = attr.startsWith("_") ? null : attrs.get(attr);
        if (v == null) {
            throw new PyException("AttributeError", "module '" + name + "' has no attribute '" + attr + "'");
        }
        return v;
    }

    @Override
    public long hash() {
        return Py.identityHash(this);
    }
}
