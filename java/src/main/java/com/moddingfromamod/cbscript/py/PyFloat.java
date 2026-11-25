// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/** Python's {@code float} (an IEEE double). */
public final class PyFloat extends PyObject {
    private final double value;

    PyFloat(double value) {
        this.value = value;
    }

    /** The value. */
    public double value() {
        return value;
    }

    @Override
    public String typeName() {
        return "float";
    }

    @Override
    public String repr() {
        return FloatFormat.repr(value);
    }

    @Override
    public boolean truthy() {
        return value != 0.0;
    }

    @Override
    public long hash() {
        return Py.hashDouble(value, this);
    }
}
