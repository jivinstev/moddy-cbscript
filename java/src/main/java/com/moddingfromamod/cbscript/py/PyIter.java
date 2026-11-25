// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.Iterator;

/**
 * A one-shot iterator object: {@code iter(x)}, generators, and the lazy results of {@code map}, {@code filter},
 * {@code zip}, {@code enumerate} and {@code reversed}.
 */
public final class PyIter extends PyObject {
    private final String typeName;
    private final Iterator<PyObject> it;

    PyIter(String typeName, Iterator<PyObject> it) {
        this.typeName = typeName;
        this.it = it;
    }

    @Override
    public String typeName() {
        return typeName;
    }

    @Override
    public String repr() {
        if (typeName.equals("generator")) {
            return "<generator object <genexpr> at " + Py.fakeAddress(this) + ">";
        }
        return super.repr();
    }

    @Override
    public Iterator<PyObject> iter() {
        return it;
    }

    /** {@code next(it)}, or null when exhausted. */
    PyObject next() {
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public long hash() {
        return Py.identityHash(this);
    }
}
