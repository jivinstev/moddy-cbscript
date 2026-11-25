// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Python's {@code str}: a sequence of code points, held as a Java string (characters outside the BMP as surrogate
 * pairs). Lengths and indices count code points, as in Python. A lone surrogate is one code point in both.
 */
public final class PyStr extends PyObject {
    private static final PyStr EMPTY = new PyStr("");
    private static final PyStr[] LATIN1 = new PyStr[256];

    static {
        for (int i = 0; i < 256; i++) {
            LATIN1[i] = new PyStr(String.valueOf((char) i));
        }
    }

    private final String value;
    /** The code points, computed on first use, only for strings containing surrogate pairs. */
    private int[] cps;
    private final boolean simple;
    private long hash = -1;

    PyStr(String value) {
        this.value = value;
        this.simple = isSimple(value);
    }

    static PyStr of(String s) {
        if (s.isEmpty()) {
            return EMPTY;
        }
        if (s.length() == 1 && s.charAt(0) < 256) {
            return LATIN1[s.charAt(0)];
        }
        return new PyStr(s);
    }

    static PyStr ofCodePoint(int cp) {
        return cp < 256 ? LATIN1[cp] : new PyStr(new String(Character.toChars(cp)));
    }

    private static boolean isSimple(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isHighSurrogate(s.charAt(i)) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                return false;
            }
        }
        return true;
    }

    /** The Java string. */
    public String value() {
        return value;
    }

    /** The code points of this string. */
    int[] codePoints() {
        if (cps == null) {
            cps = value.codePoints().toArray();
        }
        return cps;
    }

    /** Code point at index i (0 <= i < len). */
    int codePointAt(int i) {
        return simple ? value.charAt(i) : codePoints()[i];
    }

    /** Substring by code point indices. */
    String substring(int from, int to) {
        if (simple) {
            return value.substring(from, to);
        }
        int[] c = codePoints();
        return new String(c, from, to - from);
    }

    /** Converts a code point index to a Java char index. */
    int charIndex(int cpIndex) {
        return simple ? cpIndex : value.offsetByCodePoints(0, cpIndex);
    }

    /** Converts a Java char index to a code point index. */
    int cpIndex(int charIndex) {
        return simple ? charIndex : value.codePointCount(0, charIndex);
    }

    @Override
    public String typeName() {
        return "str";
    }

    @Override
    public String str() {
        return value;
    }

    @Override
    public String repr() {
        return repr(value);
    }

    /** CPython's {@code unicode_repr}. */
    static String repr(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(quote);
        for (int i = 0; i < s.length(); ) {
            int ch = s.codePointAt(i);
            i += Character.charCount(ch);
            if (ch == quote || ch == '\\') {
                sb.append('\\').append((char) ch);
            } else if (ch == '\t') {
                sb.append("\\t");
            } else if (ch == '\n') {
                sb.append("\\n");
            } else if (ch == '\r') {
                sb.append("\\r");
            } else if (ch < ' ' || ch == 0x7f) {
                sb.append("\\x").append(hex2(ch));
            } else if (ch < 0x7f) {
                sb.append((char) ch);
            } else if (isPrintable(ch)) {
                sb.appendCodePoint(ch);
            } else if (ch <= 0xff) {
                sb.append("\\x").append(hex2(ch));
            } else if (ch <= 0xffff) {
                sb.append("\\u").append(String.format("%04x", ch));
            } else {
                sb.append("\\U").append(String.format("%08x", ch));
            }
        }
        sb.append(quote);
        return sb.toString();
    }

    /** {@code ascii(s)}: the repr with every non-ASCII character escaped. */
    static String ascii(String repr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repr.length(); ) {
            int ch = repr.codePointAt(i);
            i += Character.charCount(ch);
            if (ch < 0x80) {
                sb.append((char) ch);
            } else if (ch <= 0xff) {
                sb.append("\\x").append(hex2(ch));
            } else if (ch <= 0xffff) {
                sb.append("\\u").append(String.format("%04x", ch));
            } else {
                sb.append("\\U").append(String.format("%08x", ch));
            }
        }
        return sb.toString();
    }

    private static String hex2(int ch) {
        return String.format("%02x", ch);
    }

    /** Python's {@code str.isprintable} for one code point (Unicode categories as of the JDK's Unicode tables). */
    static boolean isPrintable(int ch) {
        if (ch == ' ') {
            return true;
        }
        return switch (Character.getType(ch)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    @Override
    public boolean truthy() {
        return !value.isEmpty();
    }

    @Override
    public int len() {
        return simple ? value.length() : codePoints().length;
    }

    @Override
    public Iterator<PyObject> iter() {
        return new Iterator<>() {
            int i = 0;
            final int n = len();

            @Override
            public boolean hasNext() {
                return i < n;
            }

            @Override
            public PyObject next() {
                if (i >= n) {
                    throw new NoSuchElementException();
                }
                return ofCodePoint(codePointAt(i++));
            }
        };
    }

    @Override
    public PyObject getitem(PyObject key) {
        int n = len();
        if (key instanceof PySlice sl) {
            long[] r = sl.indices(n);
            long start = r[0];
            long step = r[2];
            long count = r[3];
            if (step == 1) {
                return of(substring((int) start, (int) (start + count)));
            }
            StringBuilder sb = new StringBuilder();
            for (long i = 0, j = start; i < count; i++, j += step) {
                sb.appendCodePoint(codePointAt((int) j));
            }
            return of(sb.toString());
        }
        if (key instanceof PyInt i) {
            long idx = i.asIndex();
            if (idx < 0) {
                idx += n;
            }
            if (idx < 0 || idx >= n) {
                throw new PyException("IndexError", "string index out of range");
            }
            return ofCodePoint(codePointAt((int) idx));
        }
        throw PyException.typeError("string indices must be integers, not '" + key.typeName() + "'");
    }

    @Override
    public boolean contains(PyObject x) {
        if (!(x instanceof PyStr s)) {
            throw PyException.typeError("'in <string>' requires string as left operand, not " + x.typeName());
        }
        return value.contains(s.value);
    }

    @Override
    public long hash() {
        if (hash == -1) {
            hash = Py.hashString(this);
        }
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PyStr s && s.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
