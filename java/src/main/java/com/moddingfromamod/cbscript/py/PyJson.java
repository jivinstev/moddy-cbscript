// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code json} module: {@code dumps} (Lib/json/encoder.py: separators, indent, sort_keys, ensure_ascii,
 * allow_nan, skipkeys, default) and {@code loads} (Modules/_json.c's scanner, with its error messages).
 */
final class PyJson {
    private PyJson() {}

    static final PyModule MODULE = build();

    private static PyModule build() {
        PyModule m = new PyModule("json", "<module 'json' from '/usr/lib/python3.13/json/__init__.py'>");
        m.attrs.put("dumps", new PyFunction("dumps", PyJson::dumps));
        m.attrs.put("loads", new PyFunction("loads", PyJson::loads));
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------
    // dumps
    // ------------------------------------------------------------------------------------------------------------

    private static final class Encoder {
        boolean skipkeys;
        boolean ensureAscii = true;
        boolean checkCircular = true;
        boolean allowNan = true;
        String indent;
        String itemSep = ", ";
        String keySep = ": ";
        boolean sortKeys;
        PyObject dflt;
        final IdentityHashMap<PyObject, Boolean> markers = new IdentityHashMap<>();
    }

    private static PyObject dumps(List<PyObject> a, Map<String, PyObject> kw) {
        if (a.size() != 1) {
            throw PyException.typeError("dumps() takes 1 positional argument but " + a.size() + " were given");
        }
        Args.onlyKw("dumps", kw, "skipkeys", "ensure_ascii", "check_circular", "allow_nan", "cls", "indent",
                "separators", "default", "sort_keys");
        Encoder e = new Encoder();
        if (kw != null) {
            e.skipkeys = kw.getOrDefault("skipkeys", Py.FALSE).truthy();
            e.ensureAscii = kw.getOrDefault("ensure_ascii", Py.TRUE).truthy();
            e.checkCircular = kw.getOrDefault("check_circular", Py.TRUE).truthy();
            e.allowNan = kw.getOrDefault("allow_nan", Py.TRUE).truthy();
            e.sortKeys = kw.getOrDefault("sort_keys", Py.FALSE).truthy();
            PyObject cls = kw.get("cls");
            if (cls != null && cls != Py.NONE) {
                throw PyException.typeError("custom JSON encoder classes are not supported");
            }
            PyObject indent = kw.getOrDefault("indent", Py.NONE);
            if (indent instanceof PyInt i) {
                e.indent = " ".repeat((int) Math.max(0, Math.min(i.asIndex(), 1000)));
            } else if (indent instanceof PyStr s) {
                e.indent = s.value();
            } else if (indent != Py.NONE) {
                throw PyException.typeError("can't multiply sequence by non-int of type '" + indent.typeName()
                        + "'");
            }
            if (e.indent != null) {
                e.itemSep = ",";
            }
            PyObject seps = kw.getOrDefault("separators", Py.NONE);
            if (seps != Py.NONE) {
                List<PyObject> sp = Py.toList(seps);
                if (sp.size() != 2) {
                    throw PyException.valueError("too many values to unpack (expected 2)");
                }
                e.itemSep = sp.get(0).str();
                e.keySep = sp.get(1).str();
            }
            PyObject d = kw.get("default");
            e.dflt = d == Py.NONE ? null : d;
        }
        StringBuilder sb = new StringBuilder();
        encode(e, a.get(0), sb, 0);
        return PyStr.of(sb.toString());
    }

    private static String floatStr(Encoder e, double d) {
        String text;
        if (Double.isNaN(d)) {
            text = "NaN";
        } else if (d == Double.POSITIVE_INFINITY) {
            text = "Infinity";
        } else if (d == Double.NEGATIVE_INFINITY) {
            text = "-Infinity";
        } else {
            return FloatFormat.repr(d);
        }
        if (!e.allowNan) {
            throw PyException.valueError("Out of range float values are not JSON compliant: " + FloatFormat.repr(d));
        }
        return text;
    }

    static String encodeString(String s, boolean ensureAscii) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (ensureAscii && c > '~')) {
                        // Java chars are UTF-16 units, so a character outside the BMP comes out as its
                        // surrogate pair, exactly as CPython writes it.
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void newline(Encoder e, StringBuilder sb, int level) {
        sb.append('\n').append(e.indent.repeat(level));
    }

    private static void encode(Encoder e, PyObject o, StringBuilder sb, int level) {
        if (o instanceof PyStr s) {
            sb.append(encodeString(s.value(), e.ensureAscii));
        } else if (o == Py.NONE) {
            sb.append("null");
        } else if (o == Py.TRUE) {
            sb.append("true");
        } else if (o == Py.FALSE) {
            sb.append("false");
        } else if (o instanceof PyInt i) {
            sb.append(i.repr());
        } else if (o instanceof PyFloat f) {
            sb.append(floatStr(e, f.value()));
        } else if (o instanceof PyList || o instanceof PyTuple) {
            List<PyObject> items = ((PySequence) o).items();
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            enter(e, o);
            sb.append('[');
            String sep = e.itemSep;
            if (e.indent != null) {
                newline(e, sb, level + 1);
            }
            boolean first = true;
            for (PyObject item : items) {
                if (!first) {
                    sb.append(sep);
                    if (e.indent != null) {
                        newline(e, sb, level + 1);
                    }
                }
                first = false;
                encode(e, item, sb, level + 1);
            }
            if (e.indent != null) {
                newline(e, sb, level);
            }
            sb.append(']');
            leave(e, o);
        } else if (o instanceof PyDict d) {
            if (d.size() == 0) {
                sb.append("{}");
                return;
            }
            enter(e, o);
            sb.append('{');
            if (e.indent != null) {
                newline(e, sb, level + 1);
            }
            List<Map.Entry<PyObject, PyObject>> entries = d.entries();
            if (e.sortKeys) {
                List<PyObject> keys = new ArrayList<>();
                for (Map.Entry<PyObject, PyObject> en : entries) {
                    keys.add(en.getKey());
                }
                List<PyObject> sortedKeys = Builtins.sort(keys, null, false);
                List<Map.Entry<PyObject, PyObject>> sorted = new ArrayList<>();
                for (PyObject k : sortedKeys) {
                    sorted.add(Map.entry(k, d.get(k)));
                }
                entries = sorted;
            }
            boolean first = true;
            for (Map.Entry<PyObject, PyObject> en : entries) {
                PyObject key = en.getKey();
                String k;
                if (key instanceof PyStr ks) {
                    k = ks.value();
                } else if (key instanceof PyFloat kf) {
                    k = floatStr(e, kf.value());
                } else if (key == Py.TRUE) {
                    k = "true";
                } else if (key == Py.FALSE) {
                    k = "false";
                } else if (key == Py.NONE) {
                    k = "null";
                } else if (key instanceof PyInt ki) {
                    k = ki.repr();
                } else if (e.skipkeys) {
                    continue;
                } else {
                    throw PyException.typeError("keys must be str, int, float, bool or None, not "
                            + key.typeName());
                }
                if (!first) {
                    sb.append(e.itemSep);
                    if (e.indent != null) {
                        newline(e, sb, level + 1);
                    }
                }
                first = false;
                sb.append(encodeString(k, e.ensureAscii)).append(e.keySep);
                encode(e, en.getValue(), sb, level + 1);
            }
            if (e.indent != null) {
                newline(e, sb, level);
            }
            sb.append('}');
            leave(e, o);
        } else {
            if (e.dflt == null) {
                throw PyException.typeError("Object of type " + o.typeName() + " is not JSON serializable");
            }
            enter(e, o);
            encode(e, e.dflt.call(List.of(o), Map.of()), sb, level);
            leave(e, o);
        }
    }

    private static void enter(Encoder e, PyObject o) {
        if (e.checkCircular && e.markers.put(o, Boolean.TRUE) != null) {
            throw PyException.valueError("Circular reference detected");
        }
    }

    private static void leave(Encoder e, PyObject o) {
        if (e.checkCircular) {
            e.markers.remove(o);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // loads
    // ------------------------------------------------------------------------------------------------------------

    private static PyObject loads(List<PyObject> a, Map<String, PyObject> kw) {
        if (a.size() != 1) {
            throw PyException.typeError("loads() takes 1 positional argument but " + a.size() + " were given");
        }
        Args.onlyKw("loads", kw, "strict");
        PyObject src = a.get(0);
        if (!(src instanceof PyStr ps)) {
            throw PyException.typeError("the JSON object must be str, bytes or bytearray, not " + src.typeName());
        }
        boolean strict = kw == null || kw.getOrDefault("strict", Py.TRUE).truthy();
        int[] s = ps.codePoints();
        Decoder d = new Decoder(ps.value(), s, strict);
        if (s.length > 0 && s[0] == 0xfeff) {
            throw d.error("Unexpected UTF-8 BOM (decode using utf-8-sig)", 0);
        }
        int idx = d.skipWs(0);
        PyObject v = d.scanOnce(idx);
        int end = d.skipWs(d.next);
        if (end != s.length) {
            throw d.error("Extra data", end);
        }
        return v;
    }

    private static final class Decoder {
        final String doc;
        final int[] s;
        final boolean strict;
        int next;

        Decoder(String doc, int[] s, boolean strict) {
            this.doc = doc;
            this.s = s;
            this.strict = strict;
        }

        PyException error(String msg, int pos) {
            int lineno = 1;
            int lastNl = -1;
            for (int i = 0; i < pos && i < s.length; i++) {
                if (s[i] == '\n') {
                    lineno++;
                    lastNl = i;
                }
            }
            int colno = pos - lastNl;
            return new PyException("JSONDecodeError", msg + ": line " + lineno + " column " + colno + " (char "
                    + pos + ")");
        }

        int skipWs(int i) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) {
                i++;
            }
            return i;
        }

        private boolean at(int i, String lit) {
            if (i + lit.length() > s.length) {
                return false;
            }
            for (int k = 0; k < lit.length(); k++) {
                if (s[i + k] != lit.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        /** scan_once: one value at idx; sets next. "Expecting value" where no value starts. */
        PyObject scanOnce(int idx) {
            if (idx >= s.length) {
                throw error("Expecting value", idx);
            }
            int c = s[idx];
            switch (c) {
                case '"':
                    return scanString(idx + 1);
                case '{':
                    return parseObject(idx + 1);
                case '[':
                    return parseArray(idx + 1);
                case 'n':
                    if (at(idx, "null")) {
                        next = idx + 4;
                        return Py.NONE;
                    }
                    break;
                case 't':
                    if (at(idx, "true")) {
                        next = idx + 4;
                        return Py.TRUE;
                    }
                    break;
                case 'f':
                    if (at(idx, "false")) {
                        next = idx + 5;
                        return Py.FALSE;
                    }
                    break;
                case 'N':
                    if (at(idx, "NaN")) {
                        next = idx + 3;
                        return Py.of(Double.NaN);
                    }
                    break;
                case 'I':
                    if (at(idx, "Infinity")) {
                        next = idx + 8;
                        return Py.of(Double.POSITIVE_INFINITY);
                    }
                    break;
                case '-':
                    if (at(idx, "-Infinity")) {
                        next = idx + 9;
                        return Py.of(Double.NEGATIVE_INFINITY);
                    }
                    break;
                default:
                    break;
            }
            return matchNumber(idx);
        }

        private boolean digit(int i) {
            return i < s.length && s[i] >= '0' && s[i] <= '9';
        }

        private PyObject matchNumber(int start) {
            int endIdx = s.length - 1;
            int idx = start;
            if (s[idx] == '-') {
                idx++;
                if (idx > endIdx) {
                    throw error("Expecting value", start);
                }
            }
            if (s[idx] >= '1' && s[idx] <= '9') {
                idx++;
                while (idx <= endIdx && digit(idx)) {
                    idx++;
                }
            } else if (s[idx] == '0') {
                idx++;
            } else {
                throw error("Expecting value", start);
            }
            boolean isFloat = false;
            if (idx < endIdx && s[idx] == '.' && digit(idx + 1)) {
                isFloat = true;
                idx += 2;
                while (idx <= endIdx && digit(idx)) {
                    idx++;
                }
            }
            if (idx < endIdx && (s[idx] == 'e' || s[idx] == 'E')) {
                int eStart = idx;
                idx++;
                if (idx < endIdx && (s[idx] == '-' || s[idx] == '+')) {
                    idx++;
                }
                while (idx <= endIdx && digit(idx)) {
                    idx++;
                }
                if (digit(idx - 1)) {
                    isFloat = true;
                } else {
                    idx = eStart;
                }
            }
            String num = new String(s, start, idx - start);
            next = idx;
            if (isFloat) {
                return Py.of(Double.parseDouble(num));
            }
            return NumParse.parseInt(num, 10);
        }

        private PyObject scanString(int end) {
            int begin = end - 1;
            StringBuilder sb = new StringBuilder();
            int len = s.length;
            while (true) {
                int n = end;
                int c = 0;
                for (; n < len; n++) {
                    c = s[n];
                    if (c == '"' || c == '\\') {
                        break;
                    }
                    if (c <= 0x1f && strict) {
                        throw error("Invalid control character at", n);
                    }
                }
                if (n >= len || (c != '"' && c != '\\')) {
                    throw error("Unterminated string starting at", begin);
                }
                for (int k = end; k < n; k++) {
                    sb.appendCodePoint(s[k]);
                }
                n++;
                if (c == '"') {
                    next = n;
                    return PyStr.of(sb.toString());
                }
                if (n == len) {
                    throw error("Unterminated string starting at", begin);
                }
                c = s[n];
                if (c != 'u') {
                    end = n + 1;
                    switch (c) {
                        case '"', '\\', '/' -> { }
                        case 'b' -> c = '\b';
                        case 'f' -> c = '\f';
                        case 'n' -> c = '\n';
                        case 'r' -> c = '\r';
                        case 't' -> c = '\t';
                        default -> c = 0;
                    }
                    if (c == 0) {
                        throw error("Invalid \\escape", end - 2);
                    }
                } else {
                    n++;
                    end = n + 4;
                    if (end > len) {
                        throw error("Invalid \\uXXXX escape", n - 1);
                    }
                    c = 0;
                    for (; n < end; n++) {
                        int dgt = Character.digit(s[n], 16);
                        if (dgt < 0 || s[n] > 'f') {
                            throw error("Invalid \\uXXXX escape", end - 5);
                        }
                        c = (c << 4) | dgt;
                    }
                    if (c >= 0xd800 && c <= 0xdbff && end + 6 < len && s[n] == '\\' && s[n + 1] == 'u') {
                        n += 2;
                        int c2 = 0;
                        end += 6;
                        for (; n < end; n++) {
                            int dgt = Character.digit(s[n], 16);
                            if (dgt < 0 || s[n] > 'f') {
                                throw error("Invalid \\uXXXX escape", end - 5);
                            }
                            c2 = (c2 << 4) | dgt;
                        }
                        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                            c = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
                        } else {
                            end -= 6;
                        }
                    }
                }
                sb.appendCodePoint(c);
            }
        }

        private PyObject parseObject(int idx) {
            int endIdx = s.length - 1;
            PyDict rval = new PyDict();
            idx = skipWs(idx);
            if (idx > endIdx || s[idx] != '}') {
                while (true) {
                    if (idx > endIdx || s[idx] != '"') {
                        throw error("Expecting property name enclosed in double quotes", idx);
                    }
                    PyObject key = scanString(idx + 1);
                    idx = skipWs(next);
                    if (idx > endIdx || s[idx] != ':') {
                        throw error("Expecting ':' delimiter", idx);
                    }
                    idx = skipWs(idx + 1);
                    PyObject val = scanOnce(idx);
                    rval.put(key, val);
                    idx = skipWs(next);
                    if (idx <= endIdx && s[idx] == '}') {
                        break;
                    }
                    if (idx > endIdx || s[idx] != ',') {
                        throw error("Expecting ',' delimiter", idx);
                    }
                    int comma = idx;
                    idx = skipWs(idx + 1);
                    if (idx <= endIdx && s[idx] == '}') {
                        throw error("Illegal trailing comma before end of object", comma);
                    }
                }
            }
            next = idx + 1;
            return rval;
        }

        private PyObject parseArray(int idx) {
            int endIdx = s.length - 1;
            PyList rval = new PyList();
            idx = skipWs(idx);
            if (idx > endIdx || s[idx] != ']') {
                while (true) {
                    rval.items().add(scanOnce(idx));
                    idx = skipWs(next);
                    if (idx <= endIdx && s[idx] == ']') {
                        break;
                    }
                    if (idx > endIdx || s[idx] != ',') {
                        throw error("Expecting ',' delimiter", idx);
                    }
                    int comma = idx;
                    idx = skipWs(idx + 1);
                    if (idx <= endIdx && s[idx] == ']') {
                        throw error("Illegal trailing comma before end of array", comma);
                    }
                }
            }
            if (idx > endIdx || s[idx] != ']') {
                throw error("Expecting value", endIdx);
            }
            next = idx + 1;
            return rval;
        }
    }
}
