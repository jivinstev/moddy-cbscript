// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.Iterator;
import java.util.List;

/** Python's {@code range} (bounds limited to the long range; CPython allows arbitrary ints). */
public final class PyRange extends PyObject {
    final long start;
    final long stop;
    final long step;
    private final long length;

    PyRange(long start, long stop, long step) {
        if (step == 0) {
            throw PyException.valueError("range() arg 3 must not be zero");
        }
        this.start = start;
        this.stop = stop;
        this.step = step;
        long n;
        if (step > 0) {
            n = start < stop ? (Math.subtractExact(stop, start) - 1) / step + 1 : 0;
        } else {
            n = start > stop ? Math.subtractExact(start, stop) - 1 : -1;
            n = n < 0 ? 0 : n / (-step) + 1;
        }
        this.length = n;
    }

    long get(long i) {
        return start + i * step;
    }

    long length() {
        return length;
    }

    @Override
    public String typeName() {
        return "range";
    }

    @Override
    public String repr() {
        return step == 1 ? "range(" + start + ", " + stop + ")" : "range(" + start + ", " + stop + ", " + step + ")";
    }

    @Override
    public boolean truthy() {
        return length > 0;
    }

    @Override
    public int len() {
        if (length > Integer.MAX_VALUE) {
            throw new PyException("OverflowError", "Python int too large to convert to C ssize_t");
        }
        return (int) length;
    }

    @Override
    public Iterator<PyObject> iter() {
        return new Iterator<>() {
            long i = 0;

            @Override
            public boolean hasNext() {
                return i < length;
            }

            @Override
            public PyObject next() {
                Cancelled.check();
                return PyInt.of(get(i++));
            }
        };
    }

    @Override
    public PyObject getitem(PyObject key) {
        if (key instanceof PyInt k) {
            long i = k.asIndex();
            if (i < 0) {
                i += length;
            }
            if (i < 0 || i >= length) {
                throw new PyException("IndexError", "range object index out of range");
            }
            return PyInt.of(get(i));
        }
        if (key instanceof PySlice sl) {
            long[] r = sl.indices(length);
            // compute_slice: the adjusted slice bounds mapped through the range
            return new PyRange(get(r[0]), get(r[1]), step * r[2]);
        }
        throw PyException.typeError("range indices must be integers or slices, not " + key.typeName());
    }

    @Override
    public boolean contains(PyObject x) {
        if (x instanceof PyInt i && !(x instanceof PyBool) && i.isLong()) {
            long v = i.longValue();
            if (length == 0) {
                return false;
            }
            if (step > 0 ? (v < start || v >= stop) : (v > start || v <= stop)) {
                return false;
            }
            return Math.floorMod(v - start, Math.abs(step)) == 0;
        }
        return super.contains(x);
    }

    @Override
    public long hash() {
        if (length == 0) {
            return Py.tupleHash(List.of(PyInt.of(0), Py.NONE, Py.NONE));
        }
        if (length == 1) {
            return Py.tupleHash(List.of(PyInt.of(1), PyInt.of(start), Py.NONE));
        }
        return Py.tupleHash(List.of(PyInt.of(length), PyInt.of(start), PyInt.of(step)));
    }
}
