// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/** Attribute lookup: the methods and data attributes of the built-in types. */
final class Methods {
    private Methods() {}

    private static PyFunction bound(PyObject self, String name,
            BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> body) {
        return new PyFunction(name, body, self.typeName(), false);
    }

    static PyException noAttribute(PyObject self, String name) {
        return new PyException("AttributeError", "'" + self.typeName() + "' object has no attribute '" + name + "'");
    }

    /** {@code self.name}. */
    static PyObject getattr(PyObject self, String name) {
        PyObject r = null;
        if (!name.startsWith("_")) {
            if (self instanceof PyStr s) {
                r = strMethod(s, name);
            } else if (self instanceof PyList l) {
                r = listMethod(l, name);
            } else if (self instanceof PyTuple t) {
                r = tupleMethod(t, name);
            } else if (self instanceof PyDict d) {
                r = dictMethod(d, name);
            } else if (self instanceof PySet s) {
                r = setMethod(s, name);
            } else if (self instanceof PyInt i) {
                r = intAttr(i, name);
            } else if (self instanceof PyFloat f) {
                r = floatAttr(f, name);
            } else if (self instanceof PyRange g) {
                r = rangeAttr(g, name);
            } else if (self instanceof PySlice sl) {
                r = switch (name) {
                    case "start" -> sl.start;
                    case "stop" -> sl.stop;
                    case "step" -> sl.step;
                    default -> null;
                };
            }
        }
        if (r == null) {
            throw noAttribute(self, name);
        }
        return r;
    }

    /** {@code type.name}: an unbound method, called with the instance first. */
    static PyObject unbound(PyType type, String name) {
        if (type.name().equals("dict") && name.equals("fromkeys")) {
            return new PyFunction("fromkeys", (a, kw) -> {
                Args.noKwargs("fromkeys", kw);
                Args.count("fromkeys", a, 1, 2);
                PyDict d = new PyDict();
                PyObject v = a.size() > 1 ? a.get(1) : Py.NONE;
                Iterator<PyObject> it = a.get(0).iter();
                while (it.hasNext()) {
                    d.put(it.next(), v);
                }
                return d;
            }, "type", false);
        }
        if (name.startsWith("_") || type.name().equals("type")) {
            throw new PyException("AttributeError", "type object '" + type.name() + "' has no attribute '" + name
                    + "'");
        }
        PyObject probe = sampleOf(type.name());
        if (probe == null) {
            throw new PyException("AttributeError", "type object '" + type.name() + "' has no attribute '" + name
                    + "'");
        }
        try {
            getattr(probe, name);
        } catch (PyException e) {
            throw new PyException("AttributeError", "type object '" + type.name() + "' has no attribute '" + name
                    + "'");
        }
        String qual = type.name() + "." + name;
        String repr = "<method '" + name + "' of '" + type.name() + "' objects>";
        return new PyFunction(name, (a, kw) -> {
            if (a.isEmpty()) {
                throw PyException.typeError("unbound method " + qual + "() needs an argument");
            }
            PyObject self = a.get(0);
            if (!type.isInstance(self)) {
                throw PyException.typeError("descriptor '" + name + "' for '" + type.name()
                        + "' objects doesn't apply to a '" + self.typeName() + "' object");
            }
            return getattr(self, name).call(a.subList(1, a.size()), kw);
        }, null, false) {
            @Override
            public String repr() {
                return repr;
            }
        };
    }

    private static PyObject sampleOf(String typeName) {
        return switch (typeName) {
            case "str" -> PyStr.of("");
            case "list" -> new PyList();
            case "tuple" -> PyTuple.EMPTY;
            case "dict" -> new PyDict();
            case "set" -> new PySet();
            case "frozenset" -> new PySet(true);
            case "int", "bool" -> PyInt.of(0);
            case "float" -> Py.of(0.0);
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // str
    // ------------------------------------------------------------------------------------------------------------

    /** Python's slice-style [start, end) adjustment for str/list method ranges (ADJUST_INDICES). */
    static long[] adjust(PyObject start, PyObject end, long len) {
        for (PyObject o : new PyObject[] {start, end}) {
            if (o != null && o != Py.NONE && !(o instanceof PyInt)) {
                throw PyException.typeError("slice indices must be integers or None or have an __index__ method");
            }
        }
        long s = Args.optIndex(start, 0);
        long e = Args.optIndex(end, len);
        if (e > len) {
            e = len;
        } else if (e < 0) {
            e += len;
            if (e < 0) {
                e = 0;
            }
        }
        if (s < 0) {
            s += len;
            if (s < 0) {
                s = 0;
            }
        }
        return new long[] {s, e};
    }

    private static String strArg(String method, PyObject o) {
        if (o instanceof PyStr s) {
            return s.value();
        }
        throw PyException.typeError("must be str, not " + o.typeName());
    }

    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    /** Code point index of sub in hay[from, to), or -1 (direction by reverse). */
    private static long findIn(PyStr hay, String sub, long from, long to, boolean reverse) {
        int n = hay.len();
        int m = PyStr.of(sub).len();
        if (from > n || to - from < m) {
            return -1;
        }
        String h = hay.value();
        int cFrom = hay.charIndex((int) from);
        int cTo = hay.charIndex((int) to);
        if (!reverse) {
            int idx = h.indexOf(sub, cFrom);
            if (idx < 0 || idx + sub.length() > cTo) {
                return -1;
            }
            return hay.cpIndex(idx);
        }
        int idx = h.lastIndexOf(sub, cTo - sub.length());
        if (idx < 0 || idx < cFrom) {
            return -1;
        }
        return hay.cpIndex(idx);
    }

    private static long countIn(PyStr hay, String sub, long from, long to) {
        int n = hay.len();
        if (from > n || to < from) {
            return 0;
        }
        if (sub.isEmpty()) {
            return to - from + 1;
        }
        String h = hay.value();
        int cFrom = hay.charIndex((int) from);
        int cTo = hay.charIndex((int) to);
        long count = 0;
        int i = h.indexOf(sub, cFrom);
        while (i >= 0 && i + sub.length() <= cTo) {
            count++;
            i = h.indexOf(sub, i + sub.length());
        }
        return count;
    }

    private static boolean isCased(int c) {
        return Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c);
    }

    private static String lowerOf(int c) {
        return new String(Character.toChars(c)).toLowerCase(Locale.ROOT);
    }

    private static String upperOf(int c) {
        return new String(Character.toChars(c)).toUpperCase(Locale.ROOT);
    }

    private static String titleOf(int c) {
        int t = Character.toTitleCase(c);
        if (t != c || Character.isTitleCase(c)) {
            return new String(Character.toChars(t));
        }
        String u = upperOf(c);
        if (u.codePointCount(0, u.length()) > 1) {
            // full mapping such as U+00DF -> "Ss": first upper, rest lower
            int first = u.codePointAt(0);
            return new String(Character.toChars(first)) + u.substring(Character.charCount(first))
                    .toLowerCase(Locale.ROOT);
        }
        return u;
    }

    static boolean isAlpha(int c) {
        return switch (Character.getType(c)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                 Character.MODIFIER_LETTER, Character.OTHER_LETTER -> true;
            default -> false;
        };
    }

    static boolean isDecimal(int c) {
        return Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER;
    }

    static boolean isDigit(int c) {
        if (isDecimal(c)) {
            return true;
        }
        if (Character.getType(c) == Character.OTHER_NUMBER) {
            int v = Character.getNumericValue(c);
            return v >= 0 && v <= 9;
        }
        return false;
    }

    static boolean isNumeric(int c) {
        int t = Character.getType(c);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    private static boolean all(String s, java.util.function.IntPredicate p) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(p);
    }

    private static boolean isLineBreak(int c) {
        return c == '\n' || c == '\r' || c == 0x0b || c == 0x0c || c == 0x1c || c == 0x1d || c == 0x1e
                || c == 0x85 || c == 0x2028 || c == 0x2029;
    }

    private static List<PyObject> splitWhitespace(int[] c, long maxsplit) {
        List<PyObject> out = new ArrayList<>();
        int i = 0;
        int n = c.length;
        while (i < n && NumParse.isSpace(c[i])) {
            i++;
        }
        while (i < n) {
            if (maxsplit == 0) {
                int end = n;
                while (end > i && NumParse.isSpace(c[end - 1])) {
                    end--;
                }
                out.add(PyStr.of(new String(c, i, end - i)));
                return out;
            }
            int j = i;
            while (j < n && !NumParse.isSpace(c[j])) {
                j++;
            }
            out.add(PyStr.of(new String(c, i, j - i)));
            maxsplit--;
            i = j;
            while (i < n && NumParse.isSpace(c[i])) {
                i++;
            }
        }
        return out;
    }

    private static List<PyObject> rsplitWhitespace(int[] c, long maxsplit) {
        List<PyObject> out = new ArrayList<>();
        int i = c.length;
        while (i > 0 && NumParse.isSpace(c[i - 1])) {
            i--;
        }
        while (i > 0) {
            if (maxsplit == 0) {
                int start = 0;
                while (start < i && NumParse.isSpace(c[start])) {
                    start++;
                }
                out.add(PyStr.of(new String(c, start, i - start)));
                break;
            }
            int j = i;
            while (j > 0 && !NumParse.isSpace(c[j - 1])) {
                j--;
            }
            out.add(PyStr.of(new String(c, j, i - j)));
            maxsplit--;
            i = j;
            while (i > 0 && NumParse.isSpace(c[i - 1])) {
                i--;
            }
        }
        Collections.reverse(out);
        return out;
    }

    private static String strip(String s, PyObject chars, boolean left, boolean right, String fn) {
        int[] c = cps(s);
        int a = 0;
        int b = c.length;
        java.util.function.IntPredicate strip;
        if (chars == null || chars == Py.NONE) {
            strip = NumParse::isSpace;
        } else if (chars instanceof PyStr cs) {
            int[] set = cps(cs.value());
            strip = x -> {
                for (int y : set) {
                    if (x == y) {
                        return true;
                    }
                }
                return false;
            };
        } else {
            throw PyException.typeError(fn + " arg must be None or str");
        }
        if (left) {
            while (a < b && strip.test(c[a])) {
                a++;
            }
        }
        if (right) {
            while (b > a && strip.test(c[b - 1])) {
                b--;
            }
        }
        return new String(c, a, b - a);
    }

    private static int fillChar(String fn, PyObject o) {
        if (o instanceof PyStr s && s.len() == 1) {
            return s.codePointAt(0);
        }
        if (o instanceof PyStr) {
            throw PyException.typeError("The fill character must be exactly one character long");
        }
        throw PyException.typeError(fn + "() argument 2 must be str, not " + o.typeName());
    }

    private static PyObject strMethod(PyStr self, String name) {
        String s = self.value();
        switch (name) {
            case "upper":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.upper", a, kw);
                    return PyStr.of(s.toUpperCase(Locale.ROOT));
                });
            case "lower":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.lower", a, kw);
                    return PyStr.of(s.toLowerCase(Locale.ROOT));
                });
            case "casefold":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.casefold", a, kw);
                    return PyStr.of(s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
                });
            case "swapcase":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.swapcase", a, kw);
                    StringBuilder sb = new StringBuilder();
                    s.codePoints().forEach(c -> sb.append(Character.isUpperCase(c) ? lowerOf(c)
                            : Character.isLowerCase(c) ? upperOf(c) : new String(Character.toChars(c))));
                    return PyStr.of(sb.toString());
                });
            case "title":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.title", a, kw);
                    StringBuilder sb = new StringBuilder();
                    boolean prevCased = false;
                    for (int c : cps(s)) {
                        sb.append(prevCased ? lowerOf(c) : titleOf(c));
                        prevCased = isCased(c);
                    }
                    return PyStr.of(sb.toString());
                });
            case "capitalize":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.capitalize", a, kw);
                    if (s.isEmpty()) {
                        return self;
                    }
                    int first = s.codePointAt(0);
                    return PyStr.of(titleOf(first) + s.substring(Character.charCount(first)).toLowerCase(Locale.ROOT));
                });
            case "strip":
            case "lstrip":
            case "rstrip":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs(name, kw);
                    Args.count(name, a, 0, 1);
                    PyObject chars = a.isEmpty() ? null : a.get(0);
                    return PyStr.of(strip(s, chars, !name.equals("rstrip"), !name.equals("lstrip"), name));
                });
            case "split":
            case "rsplit":
                return bound(self, name, (a, kw) -> {
                    Args.onlyKw(name, kw, "sep", "maxsplit");
                    if (a.size() > 2) {
                        throw PyException.typeError(name + "() takes at most 2 arguments (" + a.size() + " given)");
                    }
                    PyObject sep = Args.get(a, kw, 0, "sep", Py.NONE);
                    long maxsplit = Args.optIndex(Args.get(a, kw, 1, "maxsplit", Py.NONE), -1);
                    if (maxsplit < 0) {
                        maxsplit = Long.MAX_VALUE;
                    }
                    if (sep == Py.NONE) {
                        return new PyList(name.equals("split") ? splitWhitespace(cps(s), maxsplit)
                                : rsplitWhitespace(cps(s), maxsplit));
                    }
                    if (!(sep instanceof PyStr)) {
                        throw PyException.typeError("must be str or None, not " + sep.typeName());
                    }
                    String sp = ((PyStr) sep).value();
                    if (sp.isEmpty()) {
                        throw PyException.valueError("empty separator");
                    }
                    List<PyObject> out = new ArrayList<>();
                    if (name.equals("split")) {
                        int i = 0;
                        while (maxsplit-- > 0) {
                            int j = s.indexOf(sp, i);
                            if (j < 0) {
                                break;
                            }
                            out.add(PyStr.of(s.substring(i, j)));
                            i = j + sp.length();
                        }
                        out.add(PyStr.of(s.substring(i)));
                    } else {
                        int i = s.length();
                        while (maxsplit-- > 0) {
                            int j = s.lastIndexOf(sp, i - sp.length());
                            if (j < 0) {
                                break;
                            }
                            out.add(PyStr.of(s.substring(j + sp.length(), i)));
                            i = j;
                        }
                        out.add(PyStr.of(s.substring(0, i)));
                        Collections.reverse(out);
                    }
                    return new PyList(out);
                });
            case "splitlines":
                return bound(self, name, (a, kw) -> {
                    Args.onlyKw(name, kw, "keepends");
                    Args.count(name, a, 0, 1);
                    boolean keep = Args.get(a, kw, 0, "keepends", Py.FALSE).truthy();
                    int[] c = cps(s);
                    List<PyObject> out = new ArrayList<>();
                    int i = 0;
                    while (i < c.length) {
                        int j = i;
                        while (j < c.length && !isLineBreak(c[j])) {
                            j++;
                        }
                        int eol = j;
                        if (j < c.length) {
                            if (c[j] == '\r' && j + 1 < c.length && c[j + 1] == '\n') {
                                j += 2;
                            } else {
                                j++;
                            }
                            if (keep) {
                                eol = j;
                            }
                        }
                        out.add(PyStr.of(new String(c, i, eol - i)));
                        i = j;
                    }
                    return new PyList(out);
                });
            case "join":
                return bound(self, name, (a, kw) -> {
                    PyObject it = Args.one("str.join", a, kw);
                    List<PyObject> items;
                    try {
                        items = Py.toList(it);
                    } catch (PyException e) {
                        if (e.type.equals("TypeError") && e.pyStr.endsWith("is not iterable")) {
                            throw PyException.typeError("can only join an iterable");
                        }
                        throw e;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < items.size(); i++) {
                        if (!(items.get(i) instanceof PyStr p)) {
                            throw PyException.typeError("sequence item " + i + ": expected str instance, "
                                    + items.get(i).typeName() + " found");
                        }
                        if (i > 0) {
                            sb.append(s);
                        }
                        sb.append(p.value());
                        Py.checkSize(sb.length());
                    }
                    return PyStr.of(sb.toString());
                });
            case "replace":
                return bound(self, name, (a, kw) -> {
                    Args.onlyKw(name, kw, "count");
                    if (a.size() < 2) {
                        throw PyException.typeError("replace() takes at least 2 positional arguments (" + a.size()
                                + " given)");
                    }
                    if (a.size() > 3) {
                        throw PyException.typeError("replace() takes at most 3 arguments (" + a.size() + " given)");
                    }
                    String old = Args.str("replace", a.get(0), 1);
                    String nu = Args.str("replace", a.get(1), 2);
                    long count = Args.optIndex(Args.get(a, kw, 2, "count", PyInt.of(-1)), -1);
                    if (count < 0) {
                        count = Long.MAX_VALUE;
                    }
                    StringBuilder sb = new StringBuilder();
                    if (old.isEmpty()) {
                        int[] c = cps(s);
                        for (int i = 0; i < c.length; i++) {
                            if (count > 0) {
                                sb.append(nu);
                                count--;
                            }
                            sb.appendCodePoint(c[i]);
                        }
                        if (count > 0) {
                            sb.append(nu);
                        }
                        return PyStr.of(sb.toString());
                    }
                    int i = 0;
                    while (count-- > 0) {
                        int j = s.indexOf(old, i);
                        if (j < 0) {
                            break;
                        }
                        sb.append(s, i, j).append(nu);
                        i = j + old.length();
                        Py.checkSize(sb.length());
                    }
                    sb.append(s.substring(i));
                    return PyStr.of(sb.toString());
                });
            case "startswith":
            case "endswith":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs(name, kw);
                    Args.count(name, a, 1, 3);
                    long[] r = adjust(a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null, self.len());
                    List<PyObject> options;
                    if (a.get(0) instanceof PyTuple t) {
                        options = t.items();
                    } else if (a.get(0) instanceof PyStr) {
                        options = List.of(a.get(0));
                    } else {
                        throw PyException.typeError(name + " first arg must be str or a tuple of str, not "
                                + a.get(0).typeName());
                    }
                    for (PyObject o : options) {
                        if (!(o instanceof PyStr p)) {
                            throw PyException.typeError("tuple for " + name + " must only contain str, not "
                                    + o.typeName());
                        }
                        int m = p.len();
                        if (r[0] > self.len() || r[1] - r[0] < m) {
                            continue;
                        }
                        long at = name.equals("startswith") ? r[0] : r[1] - m;
                        if (self.substring((int) at, (int) at + m).equals(p.value())) {
                            return Py.TRUE;
                        }
                    }
                    return Py.FALSE;
                });
            case "find":
            case "rfind":
            case "index":
            case "rindex":
            case "count":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs(name, kw);
                    Args.count(name, a, 1, 3);
                    if (!(a.get(0) instanceof PyStr)) {
                        throw PyException.typeError(name + "() argument 1 must be str, not " + a.get(0).typeName());
                    }
                    String sub = ((PyStr) a.get(0)).value();
                    long[] r = adjust(a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null, self.len());
                    if (name.equals("count")) {
                        return PyInt.of(countIn(self, sub, r[0], r[1]));
                    }
                    long idx = findIn(self, sub, r[0], r[1], name.startsWith("r"));
                    if (idx < 0 && name.contains("index")) {
                        throw PyException.valueError("substring not found");
                    }
                    return PyInt.of(idx);
                });
            case "format":
                return bound(self, name, (a, kw) -> PyStr.of(Formatter.strFormat(s, a, kw == null ? Map.of() : kw)));
            case "format_map":
                return bound(self, name, (a, kw) -> {
                    PyObject m = Args.one("str.format_map", a, kw);
                    Map<String, PyObject> view = new java.util.AbstractMap<>() {
                        @Override
                        public PyObject get(Object key) {
                            return m.getitem(PyStr.of((String) key));
                        }

                        @Override
                        public java.util.Set<Map.Entry<String, PyObject>> entrySet() {
                            return java.util.Set.of();
                        }
                    };
                    return PyStr.of(Formatter.strFormat(s, List.of(), view));
                });
            case "zfill":
                return bound(self, name, (a, kw) -> {
                    long width = Py.indexLong(Args.one("str.zfill", a, kw));
                    int n = self.len();
                    if (width <= n) {
                        return self;
                    }
                    Py.checkSize(width);
                    int fill = (int) (width - n);
                    if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
                        return PyStr.of(s.charAt(0) + "0".repeat(fill) + s.substring(1));
                    }
                    return PyStr.of("0".repeat(fill) + s);
                });
            case "center":
            case "ljust":
            case "rjust":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs(name, kw);
                    Args.count(name, a, 1, 2);
                    long width = Py.indexLong(a.get(0));
                    int fc = a.size() > 1 ? fillChar(name, a.get(1)) : ' ';
                    int n = self.len();
                    if (width <= n) {
                        return self;
                    }
                    Py.checkSize(width);
                    String f = new String(Character.toChars(fc));
                    long marg = width - n;
                    long left = switch (name) {
                        case "ljust" -> 0;
                        case "rjust" -> marg;
                        default -> marg / 2 + (marg & width & 1);
                    };
                    return PyStr.of(f.repeat((int) left) + s + f.repeat((int) (marg - left)));
                });
            case "isdigit":
                return pred(self, name, s, Methods::isDigit);
            case "isdecimal":
                return pred(self, name, s, Methods::isDecimal);
            case "isnumeric":
                return pred(self, name, s, Methods::isNumeric);
            case "isalpha":
                return pred(self, name, s, Methods::isAlpha);
            case "isalnum":
                return pred(self, name, s, c -> isAlpha(c) || isNumeric(c));
            case "isspace":
                return pred(self, name, s, NumParse::isSpace);
            case "isascii":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.isascii", a, kw);
                    return Py.of(s.chars().allMatch(c -> c < 128));
                });
            case "isprintable":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.isprintable", a, kw);
                    return Py.of(s.codePoints().allMatch(PyStr::isPrintable));
                });
            case "isupper":
            case "islower":
                return bound(self, name, (a, kw) -> {
                    Args.none("str." + name, a, kw);
                    boolean cased = false;
                    for (int c : cps(s)) {
                        if (name.equals("isupper") ? (Character.isLowerCase(c) || Character.isTitleCase(c))
                                : (Character.isUpperCase(c) || Character.isTitleCase(c))) {
                            return Py.FALSE;
                        }
                        cased |= isCased(c);
                    }
                    return Py.of(cased);
                });
            case "istitle":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.istitle", a, kw);
                    boolean cased = false;
                    boolean prevCased = false;
                    for (int c : cps(s)) {
                        if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                            if (prevCased) {
                                return Py.FALSE;
                            }
                            prevCased = true;
                            cased = true;
                        } else if (Character.isLowerCase(c)) {
                            if (!prevCased) {
                                return Py.FALSE;
                            }
                            prevCased = true;
                            cased = true;
                        } else {
                            prevCased = false;
                        }
                    }
                    return Py.of(cased);
                });
            case "isidentifier":
                return bound(self, name, (a, kw) -> {
                    Args.none("str.isidentifier", a, kw);
                    return Py.of(PyLexer.isIdentifier(s));
                });
            case "partition":
            case "rpartition":
                return bound(self, name, (a, kw) -> {
                    String sep = strArg(name, Args.one("str." + name, a, kw));
                    if (sep.isEmpty()) {
                        throw PyException.valueError("empty separator");
                    }
                    int i = name.equals("partition") ? s.indexOf(sep) : s.lastIndexOf(sep);
                    if (i < 0) {
                        return name.equals("partition") ? Py.tuple(self, PyStr.of(""), PyStr.of(""))
                                : Py.tuple(PyStr.of(""), PyStr.of(""), self);
                    }
                    return Py.tuple(PyStr.of(s.substring(0, i)), PyStr.of(sep),
                            PyStr.of(s.substring(i + sep.length())));
                });
            case "removeprefix":
                return bound(self, name, (a, kw) -> {
                    String p = strArg(name, Args.one("str.removeprefix", a, kw));
                    return s.startsWith(p) ? PyStr.of(s.substring(p.length())) : self;
                });
            case "removesuffix":
                return bound(self, name, (a, kw) -> {
                    String p = strArg(name, Args.one("str.removesuffix", a, kw));
                    return s.endsWith(p) && !p.isEmpty() ? PyStr.of(s.substring(0, s.length() - p.length())) : self;
                });
            case "expandtabs":
                return bound(self, name, (a, kw) -> {
                    Args.onlyKw(name, kw, "tabsize");
                    long tab = Py.indexLong(Args.get(a, kw, 0, "tabsize", PyInt.of(8)));
                    StringBuilder sb = new StringBuilder();
                    long col = 0;
                    for (int c : cps(s)) {
                        if (c == '\t') {
                            if (tab > 0) {
                                long incr = tab - (col % tab);
                                sb.append(" ".repeat((int) incr));
                                col += incr;
                            }
                        } else {
                            sb.appendCodePoint(c);
                            col = (c == '\n' || c == '\r') ? 0 : col + 1;
                        }
                    }
                    return PyStr.of(sb.toString());
                });
            default:
                return null;
        }
    }

    private static PyObject pred(PyStr self, String name, String s, java.util.function.IntPredicate p) {
        return bound(self, name, (a, kw) -> {
            Args.none("str." + name, a, kw);
            return Py.of(all(s, p));
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // list, tuple
    // ------------------------------------------------------------------------------------------------------------

    private static PyObject seqIndex(PySequence self, List<PyObject> a, Map<String, PyObject> kw) {
        String tn = self.typeName();
        Args.noKwargs("index", kw);
        Args.count("index", a, 1, 3);
        List<PyObject> items = self.items();
        long[] r = adjust(a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null, items.size());
        PyObject x = a.get(0);
        for (long i = r[0]; i < r[1] && i < items.size(); i++) {
            PyObject o = items.get((int) i);
            if (o == x || Py.eq(o, x)) {
                return PyInt.of(i);
            }
        }
        throw PyException.valueError(tn.equals("list") ? x.repr() + " is not in list"
                : "tuple.index(x): x not in tuple");
    }

    private static PyObject seqCount(PySequence self, List<PyObject> a, Map<String, PyObject> kw) {
        PyObject x = Args.one(self.typeName() + ".count", a, kw);
        long n = 0;
        for (PyObject o : self.items()) {
            if (o == x || Py.eq(o, x)) {
                n++;
            }
        }
        return PyInt.of(n);
    }

    private static PyObject listMethod(PyList self, String name) {
        List<PyObject> items = self.items();
        switch (name) {
            case "index":
                return bound(self, name, (a, kw) -> seqIndex(self, a, kw));
            case "count":
                return bound(self, name, (a, kw) -> seqCount(self, a, kw));
            case "copy":
                return bound(self, name, (a, kw) -> {
                    Args.none("list.copy", a, kw);
                    return new PyList(new ArrayList<>(items));
                });
            case "append":
                return bound(self, name, (a, kw) -> {
                    items.add(Args.one("list.append", a, kw));
                    return Py.NONE;
                });
            case "extend":
                return bound(self, name, (a, kw) -> {
                    items.addAll(Py.toList(Args.one("list.extend", a, kw)));
                    return Py.NONE;
                });
            case "insert":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("insert", kw);
                    if (a.size() != 2) {
                        throw PyException.typeError("insert expected 2 arguments, got " + a.size());
                    }
                    long i = Py.indexLong(a.get(0));
                    int n = items.size();
                    if (i < 0) {
                        i = Math.max(0, i + n);
                    }
                    items.add((int) Math.min(i, n), a.get(1));
                    return Py.NONE;
                });
            case "pop":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("pop", kw);
                    Args.count("pop", a, 0, 1);
                    if (items.isEmpty()) {
                        throw new PyException("IndexError", "pop from empty list");
                    }
                    long i = a.isEmpty() ? -1 : Py.indexLong(a.get(0));
                    if (i < 0) {
                        i += items.size();
                    }
                    if (i < 0 || i >= items.size()) {
                        throw new PyException("IndexError", "pop index out of range");
                    }
                    return items.remove((int) i);
                });
            case "remove":
                return bound(self, name, (a, kw) -> {
                    PyObject x = Args.one("list.remove", a, kw);
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i) == x || Py.eq(items.get(i), x)) {
                            items.remove(i);
                            return Py.NONE;
                        }
                    }
                    throw PyException.valueError("list.remove(x): x not in list");
                });
            case "reverse":
                return bound(self, name, (a, kw) -> {
                    Args.none("list.reverse", a, kw);
                    Collections.reverse(items);
                    return Py.NONE;
                });
            case "clear":
                return bound(self, name, (a, kw) -> {
                    Args.none("list.clear", a, kw);
                    items.clear();
                    return Py.NONE;
                });
            case "sort":
                return bound(self, name, (a, kw) -> {
                    if (!a.isEmpty()) {
                        throw PyException.typeError("sort() takes no positional arguments");
                    }
                    Args.onlyKw("sort", kw, "key", "reverse");
                    PyObject key = kw == null ? null : kw.get("key");
                    boolean reverse = kw != null && kw.containsKey("reverse") && kw.get("reverse").truthy();
                    List<PyObject> sorted = Builtins.sort(items, key, reverse);
                    items.clear();
                    items.addAll(sorted);
                    return Py.NONE;
                });
            default:
                return null;
        }
    }

    private static PyObject tupleMethod(PyTuple self, String name) {
        return switch (name) {
            case "index" -> bound(self, name, (a, kw) -> seqIndex(self, a, kw));
            case "count" -> bound(self, name, (a, kw) -> seqCount(self, a, kw));
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // dict, set
    // ------------------------------------------------------------------------------------------------------------

    static void dictUpdate(PyDict d, PyObject src, String fn) {
        if (src instanceof PyDict s) {
            for (Map.Entry<PyObject, PyObject> e : s.entries()) {
                d.put(e.getKey(), e.getValue());
            }
            return;
        }
        Iterator<PyObject> it;
        try {
            it = src.iter();
        } catch (PyException e) {
            throw PyException.typeError("'" + src.typeName() + "' object is not iterable");
        }
        int i = 0;
        while (it.hasNext()) {
            PyObject item = it.next();
            List<PyObject> kv;
            try {
                kv = Py.toList(item);
            } catch (PyException e) {
                throw PyException.typeError("cannot convert dictionary update sequence element #" + i
                        + " to a sequence");
            }
            if (kv.size() != 2) {
                throw PyException.valueError("dictionary update sequence element #" + i + " has length "
                        + kv.size() + "; 2 is required");
            }
            d.put(kv.get(0), kv.get(1));
            i++;
        }
    }

    private static PyObject dictMethod(PyDict self, String name) {
        switch (name) {
            case "get":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("get", kw);
                    Args.count("get", a, 1, 2);
                    PyObject v = self.get(a.get(0));
                    return v != null ? v : a.size() > 1 ? a.get(1) : Py.NONE;
                });
            case "keys":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.keys", a, kw);
                    return new PyDictView(self, PyDictView.Kind.KEYS);
                });
            case "values":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.values", a, kw);
                    return new PyDictView(self, PyDictView.Kind.VALUES);
                });
            case "items":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.items", a, kw);
                    return new PyDictView(self, PyDictView.Kind.ITEMS);
                });
            case "copy":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.copy", a, kw);
                    return self.copy();
                });
            case "pop":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("pop", kw);
                    Args.count("pop", a, 1, 2);
                    PyObject v = self.remove(a.get(0));
                    if (v == null) {
                        if (a.size() > 1) {
                            return a.get(1);
                        }
                        throw new PyException("KeyError", a.get(0).repr());
                    }
                    return v;
                });
            case "setdefault":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("setdefault", kw);
                    Args.count("setdefault", a, 1, 2);
                    PyObject v = self.get(a.get(0));
                    if (v == null) {
                        v = a.size() > 1 ? a.get(1) : Py.NONE;
                        self.put(a.get(0), v);
                    }
                    return v;
                });
            case "update":
                return bound(self, name, (a, kw) -> {
                    Args.count("update", a, 0, 1);
                    if (!a.isEmpty()) {
                        dictUpdate(self, a.get(0), "update");
                    }
                    if (kw != null) {
                        for (Map.Entry<String, PyObject> e : kw.entrySet()) {
                            self.put(PyStr.of(e.getKey()), e.getValue());
                        }
                    }
                    return Py.NONE;
                });
            case "clear":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.clear", a, kw);
                    for (PyObject k : self.keys()) {
                        self.remove(k);
                    }
                    return Py.NONE;
                });
            case "popitem":
                return bound(self, name, (a, kw) -> {
                    Args.none("dict.popitem", a, kw);
                    List<PyObject> keys = self.keys();
                    if (keys.isEmpty()) {
                        throw new PyException("KeyError", "'popitem(): dictionary is empty'");
                    }
                    PyObject k = keys.get(keys.size() - 1);
                    return Py.tuple(k, self.remove(k));
                });
            default:
                return null;
        }
    }

    private static PySet asSet(PyObject o) {
        return o instanceof PySet s ? s : PySet.fromIterable(o, false);
    }

    private static PyObject setMethod(PySet self, String name) {
        switch (name) {
            case "union":
            case "intersection":
            case "difference":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs(name, kw);
                    PySet r = self;
                    if (a.isEmpty()) {
                        return self.copy(self.isFrozen());
                    }
                    for (PyObject o : a) {
                        r = switch (name) {
                            case "union" -> r.union(o);
                            case "intersection" -> r.intersection(o);
                            default -> r.difference(o);
                        };
                    }
                    return r;
                });
            case "symmetric_difference":
                return bound(self, name, (a, kw) -> self.symmetricDifference(
                        Args.one("set.symmetric_difference", a, kw)));
            case "issubset":
                return bound(self, name, (a, kw) -> Py.of(self.isSubset(asSet(Args.one("set.issubset", a, kw)))));
            case "issuperset":
                return bound(self, name, (a, kw) -> Py.of(asSet(Args.one("set.issuperset", a, kw)).isSubset(self)));
            case "isdisjoint":
                return bound(self, name, (a, kw) -> Py.of(
                        self.intersection(Args.one("set.isdisjoint", a, kw)).len() == 0));
            case "copy":
                return bound(self, name, (a, kw) -> {
                    Args.none(self.typeName() + ".copy", a, kw);
                    return self.isFrozen() ? self : self.copy(false);
                });
            default:
                break;
        }
        if (self.isFrozen()) {
            return null;
        }
        switch (name) {
            case "add":
                return bound(self, name, (a, kw) -> {
                    self.add(Args.one("set.add", a, kw));
                    return Py.NONE;
                });
            case "discard":
                return bound(self, name, (a, kw) -> {
                    self.discard(Args.one("set.discard", a, kw));
                    return Py.NONE;
                });
            case "remove":
                return bound(self, name, (a, kw) -> {
                    PyObject x = Args.one("set.remove", a, kw);
                    if (!self.discard(x)) {
                        throw new PyException("KeyError", x.repr());
                    }
                    return Py.NONE;
                });
            case "update":
                return bound(self, name, (a, kw) -> {
                    Args.noKwargs("update", kw);
                    for (PyObject o : a) {
                        self.update(o);
                    }
                    return Py.NONE;
                });
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // int, float, range
    // ------------------------------------------------------------------------------------------------------------

    private static PyObject intAttr(PyInt self, String name) {
        PyInt plain = PyInt.of(self.value());
        switch (name) {
            case "real":
            case "numerator":
                return plain;
            case "imag":
                return PyInt.of(0);
            case "denominator":
                return PyInt.of(1);
            case "bit_length":
                return bound(self, name, (a, kw) -> {
                    Args.none("int.bit_length", a, kw);
                    return PyInt.of(self.value().abs().bitLength());
                });
            case "bit_count":
                return bound(self, name, (a, kw) -> {
                    Args.none("int.bit_count", a, kw);
                    return PyInt.of(self.value().abs().bitCount());
                });
            case "conjugate":
                return bound(self, name, (a, kw) -> {
                    Args.none("int.conjugate", a, kw);
                    return plain;
                });
            case "is_integer":
                return bound(self, name, (a, kw) -> {
                    Args.none("int.is_integer", a, kw);
                    return Py.TRUE;
                });
            case "as_integer_ratio":
                return bound(self, name, (a, kw) -> {
                    Args.none("int.as_integer_ratio", a, kw);
                    return Py.tuple(plain, PyInt.of(1));
                });
            default:
                return null;
        }
    }

    private static PyObject floatAttr(PyFloat self, String name) {
        double v = self.value();
        switch (name) {
            case "real":
                return self;
            case "imag":
                return Py.of(0.0);
            case "is_integer":
                return bound(self, name, (a, kw) -> {
                    Args.none("float.is_integer", a, kw);
                    return Py.of(Double.isFinite(v) && v == Math.floor(v));
                });
            case "conjugate":
                return bound(self, name, (a, kw) -> {
                    Args.none("float.conjugate", a, kw);
                    return self;
                });
            case "as_integer_ratio":
                return bound(self, name, (a, kw) -> {
                    Args.none("float.as_integer_ratio", a, kw);
                    if (Double.isInfinite(v)) {
                        throw new PyException("OverflowError", "cannot convert Infinity to integer ratio");
                    }
                    if (Double.isNaN(v)) {
                        throw PyException.valueError("cannot convert NaN to integer ratio");
                    }
                    java.math.BigDecimal bd = new java.math.BigDecimal(v);
                    BigInteger num = bd.unscaledValue();
                    BigInteger den = BigInteger.TEN.pow(Math.max(0, bd.scale()));
                    if (bd.scale() < 0) {
                        num = num.multiply(BigInteger.TEN.pow(-bd.scale()));
                    }
                    BigInteger g = num.gcd(den);
                    return Py.tuple(PyInt.of(num.divide(g)), PyInt.of(den.divide(g)));
                });
            case "hex":
                return bound(self, name, (a, kw) -> {
                    Args.none("float.hex", a, kw);
                    return PyStr.of(floatHex(v));
                });
            default:
                return null;
        }
    }

    /** float.hex(). */
    static String floatHex(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        String sign = (v < 0 || (v == 0 && 1 / v < 0)) ? "-" : "";
        v = Math.abs(v);
        if (v == 0) {
            return sign + "0x0.0p+0";
        }
        long bits = Double.doubleToRawLongBits(v);
        int exp = (int) (bits >>> 52);
        long mant = bits & 0xfffffffffffffL;
        String lead;
        int e;
        if (exp == 0) {
            lead = "0";
            e = -1022;
        } else {
            lead = "1";
            e = exp - 1023;
        }
        String m = String.format("%013x", mant);
        return sign + "0x" + lead + "." + m + "p" + (e >= 0 ? "+" : "") + e;
    }

    private static PyObject rangeAttr(PyRange self, String name) {
        return switch (name) {
            case "start" -> PyInt.of(self.start);
            case "stop" -> PyInt.of(self.stop);
            case "step" -> PyInt.of(self.step);
            case "index" -> bound(self, name, (a, kw) -> {
                PyObject x = Args.one("range.index", a, kw);
                if (x instanceof PyInt && self.contains(x)) {
                    return PyInt.of((((PyInt) x).longValue() - self.start) / self.step);
                }
                throw PyException.valueError(x.repr() + " is not in range");
            });
            case "count" -> bound(self, name, (a, kw) -> PyInt.of(self.contains(Args.one("range.count", a, kw))
                    ? 1 : 0));
            default -> null;
        };
    }
}
