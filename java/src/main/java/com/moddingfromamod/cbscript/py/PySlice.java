// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;


/** Python's {@code slice}, as produced by {@code a[i:j:k]}. */
public final class PySlice extends PyObject {
    final PyObject start;
    final PyObject stop;
    final PyObject step;

    PySlice(PyObject start, PyObject stop, PyObject step) {
        this.start = start;
        this.stop = stop;
        this.step = step;
    }

    @Override
    public String typeName() {
        return "slice";
    }

    @Override
    public String repr() {
        return "slice(" + start.repr() + ", " + stop.repr() + ", " + step.repr() + ")";
    }

    private static long clampIndex(PyObject o) {
        if (o instanceof PyInt i) {
            if (i.isLong()) {
                return i.longValue();
            }
            return i.value().signum() < 0 ? Long.MIN_VALUE + 1 : Long.MAX_VALUE;
        }
        throw PyException.typeError("slice indices must be integers or None or have an __index__ method");
    }

    /**
     * CPython's {@code PySlice_Unpack} + {@code PySlice_AdjustIndices} for a sequence of the given length:
     * returns {start, stop, step, slicelength}.
     */
    long[] indices(long length) {
        long st = step == Py.NONE ? 1 : clampIndex(step);
        if (st == 0) {
            throw PyException.valueError("slice step cannot be zero");
        }
        long lo;
        long hi;
        if (start == Py.NONE) {
            lo = st < 0 ? Long.MAX_VALUE : 0;
        } else {
            lo = clampIndex(start);
        }
        if (stop == Py.NONE) {
            hi = st < 0 ? Long.MIN_VALUE + 1 : Long.MAX_VALUE;
        } else {
            hi = clampIndex(stop);
        }
        if (lo < 0) {
            lo += length;
            if (lo < 0) {
                lo = (st < 0) ? -1 : 0;
            }
        } else if (lo >= length) {
            lo = (st < 0) ? length - 1 : length;
        }
        if (hi < 0) {
            hi += length;
            if (hi < 0) {
                hi = (st < 0) ? -1 : 0;
            }
        } else if (hi >= length) {
            hi = (st < 0) ? length - 1 : length;
        }
        long count;
        if (st < 0) {
            count = hi < lo ? (lo - hi - 1) / (-st) + 1 : 0;
        } else {
            count = lo < hi ? (hi - lo - 1) / st + 1 : 0;
        }
        return new long[] {lo, hi, st, count};
    }

    @Override
    public long hash() {
        return Py.tupleHash(java.util.List.of(start, stop, step));
    }

}
