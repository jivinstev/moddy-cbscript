// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Python's {@code set} and {@code frozenset}, on a port of CPython 3.13's open-addressing table
 * ({@code Objects/setobject.c}: linear probing of 9 slots, then perturbed jumps; resize at 60% fill). With the hashes
 * of {@link Py#hash} this reproduces CPython's iteration order, and so the {@code repr} of a set, exactly, for every
 * construction this evaluator offers (displays, comprehensions, {@code set()}, {@code | & - ^} and their method
 * forms).
 */
public final class PySet extends PyObject {
    private static final int MINSIZE = 8;
    private static final int LINEAR_PROBES = 9;
    private static final int PERTURB_SHIFT = 5;
    /** Marks a deleted slot (CPython's dummy key with hash -1). */
    private static final PyObject DUMMY = new PyObject() {
        @Override
        public String typeName() {
            return "<dummy key>";
        }
    };

    private final boolean frozen;
    private PyObject[] keys = new PyObject[MINSIZE];
    private long[] hashes = new long[MINSIZE];
    private int mask = MINSIZE - 1;
    private int fill;
    private int used;

    PySet(boolean frozen) {
        this.frozen = frozen;
    }

    PySet() {
        this(false);
    }

    boolean isFrozen() {
        return frozen;
    }

    // ---- table logic (setobject.c) ----

    private int lookkey(PyObject key, long hash) {
        long perturb = hash;
        int i = (int) (hash & mask);
        while (true) {
            int probes = (i + LINEAR_PROBES <= mask) ? LINEAR_PROBES : 0;
            int j = i;
            do {
                PyObject k = keys[j];
                if (k == null && hashes[j] == 0) {
                    return j;
                }
                if (k != null && k != DUMMY && hashes[j] == hash && (k == key || Py.eq(k, key))) {
                    return j;
                }
                j++;
            } while (probes-- > 0);
            perturb >>>= PERTURB_SHIFT;
            i = (int) ((i * 5L + 1 + perturb) & mask);
        }
    }

    void add(PyObject key) {
        addEntry(key, key.hash());
    }

    private void addEntry(PyObject key, long hash) {
        long perturb = hash;
        int i = (int) (hash & mask);
        int freeslot = -1;
        while (true) {
            int probes = (i + LINEAR_PROBES <= mask) ? LINEAR_PROBES : 0;
            int j = i;
            do {
                PyObject k = keys[j];
                if (k == null && hashes[j] == 0) {
                    if (freeslot >= 0) {
                        used++;
                        keys[freeslot] = key;
                        hashes[freeslot] = hash;
                        return;
                    }
                    fill++;
                    used++;
                    keys[j] = key;
                    hashes[j] = hash;
                    if ((long) fill * 5 < (long) mask * 3) {
                        return;
                    }
                    resize(used > 50000 ? used * 2 : used * 4);
                    return;
                }
                if (hashes[j] == hash && k != DUMMY && (k == key || Py.eq(k, key))) {
                    return;
                } else if (hashes[j] == -1 && k == DUMMY) {
                    freeslot = j;
                }
                j++;
            } while (probes-- > 0);
            perturb >>>= PERTURB_SHIFT;
            i = (int) ((i * 5L + 1 + perturb) & mask);
        }
    }

    private static void insertClean(PyObject[] keys, long[] hashes, int mask, PyObject key, long hash) {
        long perturb = hash;
        int i = (int) (hash & mask);
        while (true) {
            if (keys[i] == null) {
                keys[i] = key;
                hashes[i] = hash;
                return;
            }
            if (i + LINEAR_PROBES <= mask) {
                for (int j = 1; j <= LINEAR_PROBES; j++) {
                    if (keys[i + j] == null) {
                        keys[i + j] = key;
                        hashes[i + j] = hash;
                        return;
                    }
                }
            }
            perturb >>>= PERTURB_SHIFT;
            i = (int) ((i * 5L + 1 + perturb) & mask);
        }
    }

    private void resize(int minused) {
        int newsize = MINSIZE;
        while (newsize <= minused) {
            newsize <<= 1;
        }
        if (newsize == MINSIZE && keys.length == MINSIZE && fill == used) {
            return;
        }
        PyObject[] oldKeys = keys;
        long[] oldHashes = hashes;
        keys = new PyObject[newsize];
        hashes = new long[newsize];
        mask = newsize - 1;
        fill = used;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != null && oldKeys[j] != DUMMY) {
                insertClean(keys, hashes, mask, oldKeys[j], oldHashes[j]);
            }
        }
    }

    boolean discard(PyObject key) {
        return discardEntry(key, key.hash());
    }

    private boolean discardEntry(PyObject key, long hash) {
        int j = lookkey(key, hash);
        if (keys[j] == null) {
            return false;
        }
        keys[j] = DUMMY;
        hashes[j] = -1;
        used--;
        return true;
    }

    private boolean containsEntry(PyObject key, long hash) {
        return keys[lookkey(key, hash)] != null;
    }

    /** set_merge: adds every element of {@code other}. */
    private void merge(PySet other) {
        if (other == this || other.used == 0) {
            return;
        }
        if ((long) (fill + other.used) * 5 >= (long) mask * 3) {
            resize((used + other.used) * 2);
        }
        if (fill == 0 && mask == other.mask && other.fill == other.used) {
            System.arraycopy(other.keys, 0, keys, 0, keys.length);
            System.arraycopy(other.hashes, 0, hashes, 0, hashes.length);
            fill = other.fill;
            used = other.used;
            return;
        }
        if (fill == 0) {
            fill = other.used;
            used = other.used;
            for (int j = 0; j <= other.mask; j++) {
                PyObject k = other.keys[j];
                if (k != null && k != DUMMY) {
                    insertClean(keys, hashes, mask, k, other.hashes[j]);
                }
            }
            return;
        }
        for (int j = 0; j <= other.mask; j++) {
            PyObject k = other.keys[j];
            if (k != null && k != DUMMY) {
                addEntry(k, other.hashes[j]);
            }
        }
    }

    /** set_update_internal: adds every element of an iterable. */
    void update(PyObject other) {
        if (other instanceof PySet s) {
            merge(s);
        } else if (other instanceof PyDict d) {
            int dictsize = d.size();
            if ((long) (fill + dictsize) * 5 >= (long) mask * 3) {
                resize((used + dictsize) * 2);
            }
            for (PyObject k : d.keys()) {
                addEntry(k, k.hash());
            }
        } else {
            Iterator<PyObject> it = other.iter();
            while (it.hasNext()) {
                add(it.next());
            }
        }
    }

    /** Active elements in table order (CPython's iteration order). */
    List<PyObject> elements() {
        List<PyObject> out = new ArrayList<>(used);
        for (PyObject k : keys) {
            if (k != null && k != DUMMY) {
                out.add(k);
            }
        }
        return out;
    }

    private List<long[]> slots() {
        List<long[]> out = new ArrayList<>(used);
        for (int j = 0; j < keys.length; j++) {
            if (keys[j] != null && keys[j] != DUMMY) {
                out.add(new long[] {j, hashes[j]});
            }
        }
        return out;
    }

    PySet copy(boolean asFrozen) {
        PySet r = new PySet(asFrozen);
        r.merge(this);
        return r;
    }

    static PySet fromIterable(PyObject it, boolean frozen) {
        PySet s = new PySet(frozen);
        s.update(it);
        return s;
    }

    /** {@code self | other} and {@code union}. */
    PySet union(PyObject other) {
        PySet r = copy(frozen);
        if (other != this) {
            r.update(other);
        }
        return r;
    }

    /** {@code self & other} and {@code intersection}. */
    PySet intersection(PyObject other) {
        if (other == this) {
            return copy(frozen);
        }
        PySet result = new PySet(frozen);
        if (other instanceof PySet o) {
            PySet so = this;
            if (o.used > so.used) {
                PySet tmp = so;
                so = o;
                o = tmp;
            }
            for (long[] slot : o.slots()) {
                PyObject key = o.keys[(int) slot[0]];
                if (so.containsEntry(key, slot[1])) {
                    result.addEntry(key, slot[1]);
                }
            }
            return result;
        }
        Iterator<PyObject> it = other.iter();
        while (it.hasNext()) {
            PyObject key = it.next();
            long h = key.hash();
            if (containsEntry(key, h)) {
                result.addEntry(key, h);
                if (result.used >= used) {
                    break;
                }
            }
        }
        return result;
    }

    private void differenceUpdate(PyObject other) {
        if (other == this) {
            keys = new PyObject[MINSIZE];
            hashes = new long[MINSIZE];
            mask = MINSIZE - 1;
            fill = 0;
            used = 0;
            return;
        }
        if (other instanceof PySet o) {
            if ((o.used >> 3) > used) {
                o = intersection(o);
            }
            for (long[] slot : o.slots()) {
                discardEntry(o.keys[(int) slot[0]], slot[1]);
            }
        } else {
            Iterator<PyObject> it = other.iter();
            while (it.hasNext()) {
                discard(it.next());
            }
        }
    }

    /** {@code self - other} and {@code difference}. */
    PySet difference(PyObject other) {
        int otherSize;
        if (other instanceof PySet o) {
            otherSize = o.used;
        } else if (other instanceof PyDict d) {
            otherSize = d.size();
        } else {
            PySet r = copy(frozen);
            r.differenceUpdate(other);
            return r;
        }
        if ((used >> 2) > otherSize) {
            PySet r = copy(frozen);
            r.differenceUpdate(other);
            return r;
        }
        PySet result = new PySet(frozen);
        for (long[] slot : slots()) {
            PyObject key = keys[(int) slot[0]];
            boolean in = other instanceof PySet o ? o.containsEntry(key, slot[1]) : other.contains(key);
            if (!in) {
                result.addEntry(key, slot[1]);
            }
        }
        return result;
    }

    /** {@code self ^ other} and {@code symmetric_difference}. */
    PySet symmetricDifference(PyObject other) {
        PySet result = new PySet(frozen);
        result.update(other);
        for (long[] slot : slots()) {
            PyObject key = keys[(int) slot[0]];
            if (!result.discardEntry(key, slot[1])) {
                result.addEntry(key, slot[1]);
            }
        }
        return result;
    }

    boolean isSubset(PySet other) {
        if (used > other.used) {
            return false;
        }
        for (long[] slot : slots()) {
            if (!other.containsEntry(keys[(int) slot[0]], slot[1])) {
                return false;
            }
        }
        return true;
    }

    // ---- PyObject ----

    @Override
    public String typeName() {
        return frozen ? "frozenset" : "set";
    }

    @Override
    public String repr() {
        if (used == 0) {
            return typeName() + "()";
        }
        if (!Py.reprEnter(this)) {
            return typeName() + "(...)";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (PyObject k : elements()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(k.repr());
            }
            return frozen ? "frozenset({" + sb + "})" : "{" + sb + "}";
        } finally {
            Py.reprLeave(this);
        }
    }

    @Override
    public boolean truthy() {
        return used > 0;
    }

    @Override
    public int len() {
        return used;
    }

    @Override
    public Iterator<PyObject> iter() {
        return elements().iterator();
    }

    @Override
    public boolean contains(PyObject x) {
        return containsEntry(x, x.hash());
    }

    @Override
    public long hash() {
        if (!frozen) {
            return super.hash();
        }
        long hash = 0;
        for (int j = 0; j <= mask; j++) {
            hash ^= shuffleBits(hashes[j]);
        }
        if (((mask + 1 - fill) & 1) != 0) {
            hash ^= shuffleBits(0);
        }
        if (((fill - used) & 1) != 0) {
            hash ^= shuffleBits(-1);
        }
        hash ^= ((long) used + 1) * 1927868237L;
        hash ^= (hash >>> 11) ^ (hash >>> 25);
        hash = hash * 69069L + 907133923L;
        if (hash == -1) {
            hash = 590923713L;
        }
        return hash;
    }

    private static long shuffleBits(long h) {
        return ((h ^ 89869747L) ^ (h << 16)) * 3644798167L;
    }
}
