// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/** Python's {@code bool}, a subtype of {@code int}; the only instances are {@link Py#TRUE} and {@link Py#FALSE}. */
public final class PyBool extends PyInt {
    static final PyBool TRUE = new PyBool(true);
    static final PyBool FALSE = new PyBool(false);

    private final boolean value;

    private PyBool(boolean value) {
        super(value ? 1 : 0);
        this.value = value;
    }

    /** The Java boolean. */
    public boolean booleanValue() {
        return value;
    }

    @Override
    public String typeName() {
        return "bool";
    }

    @Override
    public String repr() {
        return value ? "True" : "False";
    }
}
