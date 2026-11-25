// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code dict}: insertion ordered, with Python key semantics ({@code 1}, {@code 1.0} and {@code True} are
 * the same key; the first key object inserted is kept, as in CPython).
 */
public final class PyDict extends PyObject {

    /** A dict key: Python hash and equality. */
    static final class Key {
        final PyObject obj;
        private final long hash;

        Key(PyObject obj) {
            this.obj = obj;
            this.hash = obj.hash();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(hash);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && (k.obj == obj || (k.hash == hash && Py.eq(obj, k.obj)));
        }
    }

    private final LinkedHashMap<Key, PyObject> map = new LinkedHashMap<>();

    public PyDict() {}

    /** Number of entries. */
    public int size() {
        return map.size();
    }

    /** The value for {@code key}, or null if absent (TypeError if the key is unhashable). */
    public PyObject get(PyObject key) {
        return map.get(new Key(key));
    }

    /** {@code self[key] = value}. */
    public void put(PyObject key, PyObject value) {
        map.put(new Key(key), value);
    }

    /** Removes {@code key}; returns the old value or null. */
    public PyObject remove(PyObject key) {
        return map.remove(new Key(key));
    }

    /** The keys in insertion order. */
    public List<PyObject> keys() {
        List<PyObject> out = new ArrayList<>(map.size());
        for (Key k : map.keySet()) {
            out.add(k.obj);
        }
        return out;
    }

    /** The values in insertion order. */
    public List<PyObject> values() {
        return new ArrayList<>(map.values());
    }

    /** The (key, value) pairs in insertion order. */
    public List<Map.Entry<PyObject, PyObject>> entries() {
        List<Map.Entry<PyObject, PyObject>> out = new ArrayList<>(map.size());
        for (Map.Entry<Key, PyObject> e : map.entrySet()) {
            out.add(Map.entry(e.getKey().obj, e.getValue()));
        }
        return out;
    }

    PyDict copy() {
        PyDict d = new PyDict();
        d.map.putAll(map);
        return d;
    }

    @Override
    public String typeName() {
        return "dict";
    }

    @Override
    public String repr() {
        if (map.isEmpty()) {
            return "{}";
        }
        if (!Py.reprEnter(this)) {
            return "{...}";
        }
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Key, PyObject> e : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey().obj.repr()).append(": ").append(e.getValue().repr());
            }
            return sb.append('}').toString();
        } finally {
            Py.reprLeave(this);
        }
    }

    @Override
    public boolean truthy() {
        return !map.isEmpty();
    }

    @Override
    public int len() {
        return map.size();
    }

    @Override
    public Iterator<PyObject> iter() {
        Iterator<Key> it = new ArrayList<>(map.keySet()).iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public PyObject next() {
                return it.next().obj;
            }
        };
    }

    @Override
    public PyObject getitem(PyObject key) {
        PyObject v = get(key);
        if (v == null) {
            throw new PyException("KeyError", key.repr());
        }
        return v;
    }

    @Override
    public boolean contains(PyObject x) {
        return map.containsKey(new Key(x));
    }
}
