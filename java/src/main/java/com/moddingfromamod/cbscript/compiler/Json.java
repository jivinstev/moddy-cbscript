// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.py.PyBool;
import com.moddingfromamod.cbscript.py.PyDict;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyFloat;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyList;
import com.moddingfromamod.cbscript.py.PyNone;
import com.moddingfromamod.cbscript.py.PyObject;
import com.moddingfromamod.cbscript.py.PyStr;
import com.moddingfromamod.cbscript.py.PyTuple;

/**
 * Python's {@code json.dumps}/{@code json.loads} for the values the compiler writes and reads: Java maps (insertion
 * ordered), lists, strings, numbers, booleans and null, and Python values from compile-time expressions.
 */
public final class Json {
    private Json() {
    }

    /** {@code json.dumps(value, indent=indent)}; indent null = one line with Python's default separators. */
    public static String dumps(Object value, Integer indent) {
        StringBuilder b = new StringBuilder();
        write(b, value, indent, 0);
        return b.toString();
    }

    /** {@code json.dumps(value, separators=(',', ':'), ensure_ascii=False)}. */
    public static String dumpsCompact(Object value) {
        StringBuilder b = new StringBuilder();
        writeCompact(b, value);
        return b.toString();
    }

    private static void writeCompact(StringBuilder b, Object v) {
        v = unwrap(v);
        if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                string(b, key(e.getKey()), false);
                b.append(':');
                writeCompact(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                writeCompact(b, l.get(i));
            }
            b.append(']');
        } else if (v instanceof String s) {
            string(b, s, false);
        } else {
            scalar(b, v);
        }
    }

    private static void write(StringBuilder b, Object v, Integer indent, int level) {
        v = unwrap(v);
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(first ? "" : (indent == null ? ", " : ","));
                first = false;
                newline(b, indent, level + 1);
                string(b, key(e.getKey()), true);
                b.append(": ");
                write(b, e.getValue(), indent, level + 1);
            }
            newline(b, indent, level);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                b.append(i == 0 ? "" : (indent == null ? ", " : ","));
                newline(b, indent, level + 1);
                write(b, l.get(i), indent, level + 1);
            }
            newline(b, indent, level);
            b.append(']');
        } else if (v instanceof String s) {
            string(b, s, true);
        } else {
            scalar(b, v);
        }
    }

    private static void newline(StringBuilder b, Integer indent, int level) {
        if (indent != null) {
            b.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static void scalar(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean x) {
            b.append(x ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof BigInteger) {
            b.append(v);
        } else if (v instanceof Double d) {
            b.append(floatText(d));
        } else {
            throw new PyException("TypeError", "Object of type " + typeName(v) + " is not JSON serializable");
        }
    }

    private static String floatText(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return com.moddingfromamod.cbscript.py.Py.of(d).repr();
    }

    private static String key(Object k) {
        k = unwrap(k);
        if (k instanceof String s) {
            return s;
        }
        if (k == null) {
            return "null";
        }
        if (k instanceof Boolean x) {
            return x ? "true" : "false";
        }
        if (k instanceof Long || k instanceof Integer || k instanceof BigInteger) {
            return k.toString();
        }
        if (k instanceof Double d) {
            return floatText(d);
        }
        throw new PyException("TypeError", "keys must be str, int, float, bool or None, not " + typeName(k));
    }

    /** The Python type name of a value json.loads returned. */
    public static String pyTypeName(Object v) {
        if (v == null) {
            return "NoneType";
        }
        if (v instanceof String) {
            return "str";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        if (v instanceof BigInteger) {
            return "int";
        }
        if (v instanceof Double) {
            return "float";
        }
        if (v instanceof List) {
            return "list";
        }
        return "dict";
    }

    private static String typeName(Object v) {
        if (v instanceof PyObject p) {
            return p.typeName();
        }
        if (v instanceof PyLike p) {
            return p.typeName();
        }
        return v.getClass().getSimpleName();
    }

    /** A Python value as the plain Java value json writes it as. */
    private static Object unwrap(Object v) {
        if (v instanceof PyLike p) {
            return p;
        }
        if (!(v instanceof PyObject)) {
            return v;
        }
        if (v instanceof PyNone) {
            return null;
        }
        if (v instanceof PyBool x) {
            return x.truthy();
        }
        if (v instanceof PyInt x) {
            return x.value();
        }
        if (v instanceof PyFloat x) {
            return x.value();
        }
        if (v instanceof PyStr x) {
            return x.value();
        }
        if (v instanceof PyList x) {
            return x.items();
        }
        if (v instanceof PyTuple x) {
            return x.items();
        }
        if (v instanceof PyDict d) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (PyObject k : d.keys()) {
                m.put(key(k), d.get(k));
            }
            return m;
        }
        return new PyLike(((PyObject) v).typeName());
    }

    /** A value json cannot write; carries its Python type name for the TypeError. */
    private record PyLike(String typeName) {
    }

    private static void string(StringBuilder b, String s, boolean ensureAscii) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || (ensureAscii && c > 0x7e && c != 0x7f)) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    // ── loads ────────────────────────────────────────────────────────────────

    /** {@code json.loads(text)} into LinkedHashMap / ArrayList / String / BigInteger / Double / Boolean / null. */
    public static Object loads(String text) {
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value();
        r.ws();
        if (r.i != text.length()) {
            throw r.error("Extra data");
        }
        return v;
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        PyException error(String what) {
            int line = 1;
            int col = 1;
            for (int k = 0; k < i && k < s.length(); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new PyException("JSONDecodeError", what + ": line " + line + " column " + col + " (char " + i + ")");
        }

        void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
                i++;
            }
        }

        Object value() {
            if (i >= s.length()) {
                throw error("Expecting value");
            }
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') {
                        throw error("Expecting property name enclosed in double quotes");
                    }
                    String k = str();
                    ws();
                    if (i >= s.length() || s.charAt(i) != ':') {
                        throw error("Expecting ':' delimiter");
                    }
                    i++;
                    ws();
                    m.put(k, value());
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    throw error("Expecting ',' delimiter");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == ']') {
                        i++;
                        return l;
                    }
                    throw error("Expecting ',' delimiter");
                }
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            if (s.startsWith("NaN", i)) {
                i += 3;
                return Double.NaN;
            }
            if (s.startsWith("Infinity", i)) {
                i += 8;
                return Double.POSITIVE_INFINITY;
            }
            if (s.startsWith("-Infinity", i)) {
                i += 9;
                return Double.NEGATIVE_INFINITY;
            }
            java.util.regex.Matcher m = NUMBER.matcher(s);
            m.region(i, s.length());
            if (m.lookingAt()) {
                i = m.end();
                if (m.group(2) == null && m.group(3) == null) {
                    return new BigInteger(m.group(1));
                }
                return Double.parseDouble(m.group());
            }
            throw error("Expecting value");
        }

        String str() {
            int start = i;
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    i = start;
                    throw error("Unterminated string starting at");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("Invalid \\escape");
                    }
                } else if (c < 0x20) {
                    throw error("Invalid control character at");
                } else {
                    b.append(c);
                }
            }
        }
    }

    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("(-?(?:0|[1-9][0-9]*))(\\.[0-9]+)?([eE][-+]?[0-9]+)?");
}
