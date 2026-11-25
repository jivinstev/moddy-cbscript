// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/** Python's {@code None}; the only instance is {@link Py#NONE}. */
public final class PyNone extends PyObject {
    static final PyNone INSTANCE = new PyNone();

    private PyNone() {}

    @Override
    public String typeName() {
        return "NoneType";
    }

    @Override
    public String repr() {
        return "None";
    }

    @Override
    public boolean truthy() {
        return false;
    }

    @Override
    public long hash() {
        return 0xFCA86420L;
    }
}
