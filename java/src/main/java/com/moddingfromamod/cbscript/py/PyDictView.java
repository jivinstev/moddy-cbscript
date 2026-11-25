// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** {@code dict.keys()}, {@code dict.values()}, {@code dict.items()}: live views of a dict. */
public final class PyDictView extends PyObject {
    enum Kind { KEYS, VALUES, ITEMS }

    private final PyDict dict;
    private final Kind kind;

    PyDictView(PyDict dict, Kind kind) {
        this.dict = dict;
        this.kind = kind;
    }

    @Override
    public String typeName() {
        return switch (kind) {
            case KEYS -> "dict_keys";
            case VALUES -> "dict_values";
            case ITEMS -> "dict_items";
        };
    }

    List<PyObject> list() {
        return switch (kind) {
            case KEYS -> dict.keys();
            case VALUES -> dict.values();
            case ITEMS -> {
                List<PyObject> out = new ArrayList<>();
                for (Map.Entry<PyObject, PyObject> e : dict.entries()) {
                    out.add(new PyTuple(List.of(e.getKey(), e.getValue())));
                }
                yield out;
            }
        };
    }

    @Override
    public String repr() {
        return typeName() + "(" + new PyList(list()).repr() + ")";
    }

    @Override
    public boolean truthy() {
        return dict.size() > 0;
    }

    @Override
    public int len() {
        return dict.size();
    }

    @Override
    public Iterator<PyObject> iter() {
        return list().iterator();
    }

    @Override
    public boolean contains(PyObject x) {
        return switch (kind) {
            case KEYS -> dict.contains(x);
            case VALUES -> super.contains(x);
            case ITEMS -> {
                if (!(x instanceof PyTuple t) || t.len() != 2) {
                    yield false;
                }
                PyObject v = dict.get(t.items().get(0));
                yield v != null && (v == t.items().get(1) || Py.eq(v, t.items().get(1)));
            }
        };
    }
}
