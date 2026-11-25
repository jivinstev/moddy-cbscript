// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Shared behaviour of {@link PyList} and {@link PyTuple}. */
public abstract class PySequence extends PyObject {

    /** The items (live for a list, unmodifiable for a tuple). */
    public abstract List<PyObject> items();

    abstract PyObject make(List<PyObject> items);

    @Override
    public boolean truthy() {
        return !items().isEmpty();
    }

    @Override
    public int len() {
        return items().size();
    }

    @Override
    public Iterator<PyObject> iter() {
        List<PyObject> items = items();
        // Index-based like CPython's list iterator, so appending while iterating is seen.
        return new Iterator<>() {
            int i = 0;

            @Override
            public boolean hasNext() {
                return i < items.size();
            }

            @Override
            public PyObject next() {
                return items.get(i++);
            }
        };
    }

    @Override
    public PyObject getitem(PyObject key) {
        List<PyObject> items = items();
        if (key instanceof PyInt i) {
            long idx = i.asIndex();
            if (idx < 0) {
                idx += items.size();
            }
            if (idx < 0 || idx >= items.size()) {
                throw new PyException("IndexError", typeName() + " index out of range");
            }
            return items.get((int) idx);
        }
        if (key instanceof PySlice sl) {
            long[] r = sl.indices(items.size());
            List<PyObject> out = new ArrayList<>((int) r[3]);
            for (long n = 0, j = r[0]; n < r[3]; n++, j += r[2]) {
                out.add(items.get((int) j));
            }
            return make(out);
        }
        throw PyException.typeError(typeName() + " indices must be integers or slices, not " + key.typeName());
    }

    @Override
    public boolean contains(PyObject x) {
        for (PyObject o : items()) {
            if (o == x || Py.eq(o, x)) {
                return true;
            }
        }
        return false;
    }

    /** Repr of the items separated by ", ", guarding against self-reference as CPython does. */
    String joinReprs() {
        if (!Py.reprEnter(this)) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            List<PyObject> items = items();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(items.get(i).repr());
            }
            return sb.toString();
        } finally {
            Py.reprLeave(this);
        }
    }
}
