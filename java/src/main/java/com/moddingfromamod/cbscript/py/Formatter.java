// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Python's three formatting languages: the format-spec mini-language ({@code format()}, {@code str.format},
 * f-strings), {@code str.format} field syntax, and printf-style {@code %} formatting.
 */
final class Formatter {
    private Formatter() {}

    // ------------------------------------------------------------------------------------------------------------
    // Format spec mini-language
    // ------------------------------------------------------------------------------------------------------------

    /** A parsed format spec: [[fill]align][sign][z][#][0][width][grouping][.precision][type]. */
    static final class Spec {
        char fill = ' ';
        char align = 0;
        char sign = 0;
        boolean z;
        boolean alt;
        int width = -1;
        char grouping = 0;
        int precision = -1;
        char type = 0;
        boolean fillGiven;
        boolean zeroFlag;
    }

    static Spec parseSpec(String s) {
        Spec sp = new Spec();
        int i = 0;
        int n = s.length();
        if (n >= 2 && "<>=^".indexOf(s.charAt(1)) >= 0) {
            sp.fill = s.charAt(0);
            sp.fillGiven = true;
            sp.align = s.charAt(1);
            i = 2;
        } else if (n >= 1 && "<>=^".indexOf(s.charAt(0)) >= 0) {
            sp.align = s.charAt(0);
            i = 1;
        }
        if (i < n && "+- ".indexOf(s.charAt(i)) >= 0) {
            sp.sign = s.charAt(i++);
        }
        if (i < n && s.charAt(i) == 'z') {
            sp.z = true;
            i++;
        }
        if (i < n && s.charAt(i) == '#') {
            sp.alt = true;
            i++;
        }
        if (i < n && s.charAt(i) == '0' && !sp.fillGiven) {
            // The '0' flag: zero fill, and '=' alignment for numbers when no alignment is given.
            sp.fill = '0';
            sp.zeroFlag = true;
            i++;
        }
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i > start) {
            sp.width = parseCount(s.substring(start, i));
        }
        if (i < n && (s.charAt(i) == ',' || s.charAt(i) == '_')) {
            sp.grouping = s.charAt(i++);
            if (i < n && (s.charAt(i) == ',' || s.charAt(i) == '_')) {
                throw PyException.valueError("Cannot specify both ',' and '_'.");
            }
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            start = i;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i == start) {
                throw PyException.valueError("Format specifier missing precision");
            }
            sp.precision = parseCount(s.substring(start, i));
        }
        if (n - i > 1) {
            throw PyException.valueError("Invalid format specifier '" + s + "' for object of type '%s'");
        }
        if (i < n) {
            sp.type = s.charAt(i);
        }
        return sp;
    }

    private static int parseCount(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw PyException.valueError("Too many decimal digits in format string");
        }
    }

    /** {@code format(obj, spec)}. */
    static String format(PyObject obj, String spec) {
        Spec sp;
        try {
            sp = parseSpec(spec);
        } catch (PyException e) {
            if (e.pyStr.contains("'%s'")) {
                throw PyException.valueError(e.pyStr.replace("%s", obj.typeName()));
            }
            throw e;
        }
        if (obj instanceof PyBool && spec.isEmpty()) {
            return obj.str();
        }
        if (obj instanceof PyInt i) {
            return formatInt(i, sp, obj.typeName());
        }
        if (obj instanceof PyFloat f) {
            return formatFloat(f.value(), sp, "float");
        }
        if (obj instanceof PyStr s) {
            return formatStr(s.value(), sp);
        }
        if (!spec.isEmpty()) {
            throw PyException.typeError("unsupported format string passed to " + obj.typeName() + ".__format__");
        }
        return obj.str();
    }

    private static PyException unknownCode(char type, String typeName) {
        return PyException.valueError("Unknown format code '" + type + "' for object of type '" + typeName + "'");
    }

    private static String formatStr(String s, Spec sp) {
        if (sp.type != 0 && sp.type != 's') {
            throw unknownCode(sp.type, "str");
        }
        if (sp.sign != 0) {
            throw PyException.valueError("Sign not allowed in string format specifier");
        }
        if (sp.alt) {
            throw PyException.valueError("Alternate form (#) not allowed in string format specifier");
        }
        if (sp.z) {
            throw PyException.valueError("Negative zero coercion (z) not allowed in format specifier");
        }
        if (sp.grouping != 0) {
            throw PyException.valueError("Cannot specify '" + sp.grouping + "' with 's'.");
        }
        if (sp.align == '=') {
            throw PyException.valueError("'=' alignment not allowed in string format specifier");
        }
        PyStr p = PyStr.of(s);
        if (sp.precision >= 0 && sp.precision < p.len()) {
            s = p.substring(0, sp.precision);
        }
        return pad(s, "", sp, '<');
    }

    /** Pads body (prefix = sign and base prefix, kept left of '=' padding) to the spec's width. */
    private static String pad(String body, String prefix, Spec sp, char defaultAlign) {
        char align = sp.align == 0 ? defaultAlign : sp.align;
        int len = PyStr.of(prefix + body).len();
        if (sp.width <= len) {
            return prefix + body;
        }
        String fill = String.valueOf(sp.fill);
        int total = sp.width - len;
        return switch (align) {
            case '<' -> prefix + body + fill.repeat(total);
            case '^' -> fill.repeat(total / 2) + prefix + body + fill.repeat(total - total / 2);
            case '=' -> prefix + fill.repeat(total) + body;
            default -> fill.repeat(total) + prefix + body;
        };
    }

    /** Inserts grouping separators into a digit string, padding with zeros up to minWidth (CPython's rules). */
    static String group(String digits, int groupSize, char sep, int minWidth) {
        StringBuilder out = new StringBuilder();
        int i = digits.length();
        int count = 0;
        while (true) {
            char c;
            if (i > 0) {
                c = digits.charAt(--i);
            } else if (out.length() < minWidth) {
                c = '0';
            } else {
                break;
            }
            if (count == groupSize) {
                out.append(sep);
                count = 0;
            }
            out.append(c);
            count++;
        }
        return out.reverse().toString();
    }

    private static String formatInt(PyInt v, Spec sp, String typeName) {
        if (sp.align == 0 && sp.zeroFlag) {
            sp.align = '=';
        }
        char type = sp.type == 0 ? 'd' : sp.type;
        switch (type) {
            case 'e', 'E', 'f', 'F', 'g', 'G', '%':
                return formatFloat(v.toDouble(), sp, typeName);
            case 'b', 'c', 'd', 'o', 'x', 'X', 'n':
                break;
            default:
                throw unknownCode(type, typeName);
        }
        if (sp.precision >= 0) {
            throw PyException.valueError("Precision not allowed in integer format specifier");
        }
        if (sp.z) {
            throw PyException.valueError("Negative zero coercion (z) not allowed in integer format specifier");
        }
        if (type == 'c') {
            if (sp.sign != 0) {
                throw PyException.valueError("Sign not allowed with integer format specifier 'c'");
            }
            if (sp.alt) {
                throw PyException.valueError("Alternate form (#) not allowed with integer format specifier 'c'");
            }
            BigInteger b = v.value();
            if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(0x10ffff)) > 0) {
                throw new PyException("OverflowError", "%c arg not in range(0x110000)");
            }
            return pad(new String(Character.toChars(b.intValue())), "", sp, '<');
        }
        if (sp.grouping == ',' && "bcoxXn".indexOf(type) >= 0) {
            throw PyException.valueError("Cannot specify ',' with '" + type + "'.");
        }
        if (sp.grouping != 0 && type == 'n') {
            throw PyException.valueError("Cannot specify '" + sp.grouping + "' with 'n'.");
        }
        BigInteger b = v.value();
        int radix = switch (type) {
            case 'b' -> 2;
            case 'o' -> 8;
            case 'x', 'X' -> 16;
            default -> 10;
        };
        String digits = b.abs().toString(radix);
        if (radix == 10 && digits.length() > PyInt.MAX_STR_DIGITS) {
            v.repr();
        }
        if (type == 'X') {
            digits = digits.toUpperCase();
        }
        String prefix = signOf(b.signum() < 0, sp.sign);
        if (sp.alt && radix != 10) {
            prefix += switch (type) {
                case 'b' -> "0b";
                case 'o' -> "0o";
                case 'x' -> "0x";
                default -> "0X";
            };
        }
        if (sp.grouping != 0) {
            int minWidth = (sp.fill == '0' && sp.align == '=') ? Math.max(0, sp.width - prefix.length()) : 0;
            digits = group(digits, radix == 10 ? 3 : 4, sp.grouping, minWidth);
        }
        return pad(digits, prefix, sp, '>');
    }

    private static String signOf(boolean negative, char sign) {
        if (negative) {
            return "-";
        }
        return sign == '+' ? "+" : sign == ' ' ? " " : "";
    }

    private static String formatFloat(double x, Spec sp, String typeName) {
        if (sp.align == 0 && sp.zeroFlag) {
            sp.align = '=';
        }
        char type = sp.type;
        if (type != 0 && "eEfFgGn%".indexOf(type) < 0) {
            throw unknownCode(type, typeName);
        }
        if (type == 'n' && sp.grouping != 0) {
            throw PyException.valueError("Cannot specify '" + sp.grouping + "' with 'n'.");
        }
        boolean negative = x < 0 || (x == 0 && 1 / x < 0);
        if (Double.isNaN(x)) {
            negative = false;
        }
        double ax = Math.abs(x);
        String body;
        int prec = sp.precision;
        boolean upper = type == 'E' || type == 'F' || type == 'G';
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            body = Double.isNaN(x) ? "nan" : "inf";
            if (upper) {
                body = body.toUpperCase();
            }
            if (type == '%') {
                body += "%";
            }
        } else {
            switch (type) {
                case 'e', 'E' -> body = FloatFormat.formatE(ax, prec < 0 ? 6 : prec, sp.alt, upper ? 'E' : 'e');
                case 'f', 'F' -> body = FloatFormat.formatF(ax, prec < 0 ? 6 : prec, sp.alt);
                case 'g', 'G', 'n' -> body = FloatFormat.formatG(ax, prec < 0 ? 6 : prec, sp.alt, false,
                        upper ? 'E' : 'e');
                case '%' -> body = FloatFormat.formatF(ax * 100, prec < 0 ? 6 : prec, sp.alt) + "%";
                default -> {
                    if (prec < 0) {
                        body = FloatFormat.formatR(ax);
                        if (sp.alt && !body.contains(".")) {
                            body = body.replace("e", ".e");
                        }
                    } else {
                        body = FloatFormat.formatG(ax, prec, sp.alt, true, 'e');
                    }
                }
            }
            if (sp.z && negative && isZeroText(body)) {
                negative = false;
            }
        }
        String prefix = signOf(negative, sp.sign);
        if (sp.grouping != 0 && Double.isFinite(x)) {
            int end = 0;
            while (end < body.length() && Character.isDigit(body.charAt(end))) {
                end++;
            }
            int minWidth = (sp.fill == '0' && sp.align == '=')
                    ? Math.max(0, sp.width - prefix.length() - (body.length() - end)) : 0;
            body = group(body.substring(0, end), 3, sp.grouping, minWidth) + body.substring(end);
        }
        return pad(body, prefix, sp, '>');
    }

    private static boolean isZeroText(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c >= '1' && c <= '9') {
                return false;
            }
            if (c == 'e' || c == 'E') {
                break;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // str.format
    // ------------------------------------------------------------------------------------------------------------

    /** State of automatic field numbering across one str.format call. */
    private static final class AutoNumber {
        int next = 0;
        int mode = 0; // 0 unknown, 1 auto, 2 manual
    }

    static String strFormat(String fmt, List<PyObject> args, Map<String, PyObject> kwargs) {
        return strFormat(fmt, args, kwargs, new AutoNumber(), 2);
    }

    private static String strFormat(String fmt, List<PyObject> args, Map<String, PyObject> kwargs, AutoNumber auto,
            int depth) {
        if (depth < 0) {
            throw PyException.valueError("Max string recursion exceeded");
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char c = fmt.charAt(i);
            if (c == '{') {
                if (i + 1 < n && fmt.charAt(i + 1) == '{') {
                    out.append('{');
                    i += 2;
                    continue;
                }
                // find the matching '}' (nested braces allowed in the spec)
                int level = 1;
                int j = i + 1;
                while (j < n && level > 0) {
                    char d = fmt.charAt(j);
                    if (d == '{') {
                        level++;
                    } else if (d == '}') {
                        level--;
                    }
                    if (level > 0) {
                        j++;
                    }
                }
                if (level > 0) {
                    throw PyException.valueError(i + 1 == n ? "Single '{' encountered in format string"
                            : "expected '}' before end of string");
                }
                out.append(formatField(fmt.substring(i + 1, j), args, kwargs, auto, depth));
                i = j + 1;
            } else if (c == '}') {
                if (i + 1 < n && fmt.charAt(i + 1) == '}') {
                    out.append('}');
                    i += 2;
                    continue;
                }
                throw PyException.valueError("Single '}' encountered in format string");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String formatField(String field, List<PyObject> args, Map<String, PyObject> kwargs,
            AutoNumber auto, int depth) {
        // split field_name[!conv][:spec], where '!' and ':' inside [] of the name do not count
        int i = 0;
        int bracket = 0;
        while (i < field.length()) {
            char c = field.charAt(i);
            if (c == '[') {
                bracket++;
            } else if (c == ']') {
                bracket--;
            } else if (bracket == 0 && (c == '!' || c == ':')) {
                break;
            }
            i++;
        }
        String name = field.substring(0, i);
        char conv = 0;
        String spec = "";
        if (i < field.length() && field.charAt(i) == '!') {
            if (i + 1 >= field.length()) {
                throw PyException.valueError("end of string while looking for conversion specifier");
            }
            conv = field.charAt(i + 1);
            i += 2;
            if (i < field.length() && field.charAt(i) != ':') {
                throw PyException.valueError("expected ':' after conversion specifier");
            }
        }
        if (i < field.length() && field.charAt(i) == ':') {
            spec = field.substring(i + 1);
        }
        PyObject obj = lookupField(name, args, kwargs, auto);
        obj = convert(obj, conv);
        if (spec.indexOf('{') >= 0) {
            spec = strFormat(spec, args, kwargs, auto, depth - 1);
        }
        return format(obj, spec);
    }

    static PyObject convert(PyObject obj, char conv) {
        return switch (conv) {
            case 0 -> obj;
            case 'r' -> PyStr.of(obj.repr());
            case 's' -> PyStr.of(obj.str());
            case 'a' -> PyStr.of(PyStr.ascii(obj.repr()));
            default -> throw PyException.valueError("Unknown conversion specifier " + conv);
        };
    }

    private static PyObject lookupField(String name, List<PyObject> args, Map<String, PyObject> kwargs,
            AutoNumber auto) {
        int i = 0;
        while (i < name.length() && name.charAt(i) != '.' && name.charAt(i) != '[') {
            i++;
        }
        String first = name.substring(0, i);
        PyObject obj;
        if (first.isEmpty()) {
            if (auto.mode == 2) {
                throw PyException.valueError(
                        "cannot switch from manual field specification to automatic field numbering");
            }
            auto.mode = 1;
            int idx = auto.next++;
            if (idx >= args.size()) {
                throw new PyException("IndexError",
                        "Replacement index " + idx + " out of range for positional args tuple");
            }
            obj = args.get(idx);
        } else if (first.chars().allMatch(Character::isDigit)) {
            if (auto.mode == 1) {
                throw PyException.valueError(
                        "cannot switch from automatic field numbering to manual field specification");
            }
            auto.mode = 2;
            int idx = Integer.parseInt(first);
            if (idx >= args.size()) {
                throw new PyException("IndexError",
                        "Replacement index " + idx + " out of range for positional args tuple");
            }
            obj = args.get(idx);
        } else {
            obj = kwargs.get(first);
            if (obj == null) {
                throw new PyException("KeyError", PyStr.repr(first));
            }
        }
        while (i < name.length()) {
            char c = name.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < name.length() && name.charAt(j) != '.' && name.charAt(j) != '[') {
                    j++;
                }
                String attr = name.substring(i + 1, j);
                if (attr.isEmpty()) {
                    throw PyException.valueError("Empty attribute in format string");
                }
                obj = obj.getattr(attr);
                i = j;
            } else if (c == '[') {
                int j = name.indexOf(']', i);
                if (j < 0) {
                    throw PyException.valueError("Missing ']' in format string");
                }
                String key = name.substring(i + 1, j);
                if (key.isEmpty()) {
                    throw PyException.valueError("Empty attribute in format string");
                }
                PyObject k = key.chars().allMatch(Character::isDigit) ? PyInt.of(Long.parseLong(key)) : PyStr.of(key);
                obj = obj.getitem(k);
                i = j + 1;
                if (i < name.length() && name.charAt(i) != '.' && name.charAt(i) != '[') {
                    throw PyException.valueError("Only '.' or '[' may follow ']' in format field specifier");
                }
            } else {
                throw PyException.valueError("Only '.' or '[' may follow ']' in format field specifier");
            }
        }
        return obj;
    }

    // ------------------------------------------------------------------------------------------------------------
    // printf-style % formatting
    // ------------------------------------------------------------------------------------------------------------

    static String percent(String fmt, PyObject args) {
        List<PyObject> argList;
        PyObject dict = null;
        if (args instanceof PyTuple t) {
            argList = t.items();
        } else {
            argList = List.of(args);
        }
        if (args instanceof PyDict) {
            dict = args;
        }
        boolean tuple = args instanceof PyTuple;
        int argIdx = 0;
        StringBuilder out = new StringBuilder();
        int n = fmt.length();
        int i = 0;
        while (i < n) {
            char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= n) {
                throw PyException.valueError("incomplete format");
            }
            PyObject value = null;
            boolean usedKey = false;
            if (fmt.charAt(i) == '(') {
                if (dict == null) {
                    throw PyException.typeError("format requires a mapping");
                }
                int level = 1;
                int j = i + 1;
                while (j < n && level > 0) {
                    if (fmt.charAt(j) == '(') {
                        level++;
                    } else if (fmt.charAt(j) == ')') {
                        level--;
                    }
                    j++;
                }
                if (level > 0) {
                    throw PyException.valueError("incomplete format key");
                }
                PyObject key = PyStr.of(fmt.substring(i + 1, j - 1));
                value = dict.getitem(key);
                usedKey = true;
                i = j;
            }
            boolean left = false;
            boolean plus = false;
            boolean space = false;
            boolean alt = false;
            boolean zero = false;
            for (; i < n; i++) {
                char f = fmt.charAt(i);
                if (f == '-') {
                    left = true;
                } else if (f == '+') {
                    plus = true;
                } else if (f == ' ') {
                    space = true;
                } else if (f == '#') {
                    alt = true;
                } else if (f == '0') {
                    zero = true;
                } else {
                    break;
                }
            }
            int width = -1;
            if (i < n && fmt.charAt(i) == '*') {
                if (argIdx >= argList.size()) {
                    throw PyException.typeError("not enough arguments for format string");
                }
                PyObject w = argList.get(argIdx++);
                if (!(w instanceof PyInt wi)) {
                    throw PyException.typeError("* wants int");
                }
                width = (int) wi.asIndex();
                if (width < 0) {
                    left = true;
                    width = -width;
                }
                i++;
            } else {
                int s = i;
                while (i < n && Character.isDigit(fmt.charAt(i))) {
                    i++;
                }
                if (i > s) {
                    width = parseCount(fmt.substring(s, i));
                }
            }
            int prec = -1;
            if (i < n && fmt.charAt(i) == '.') {
                i++;
                if (i < n && fmt.charAt(i) == '*') {
                    if (argIdx >= argList.size()) {
                        throw PyException.typeError("not enough arguments for format string");
                    }
                    PyObject p = argList.get(argIdx++);
                    if (!(p instanceof PyInt pi)) {
                        throw PyException.typeError("* wants int");
                    }
                    prec = (int) Math.max(0, pi.asIndex());
                    i++;
                } else {
                    int s = i;
                    while (i < n && Character.isDigit(fmt.charAt(i))) {
                        i++;
                    }
                    prec = i > s ? parseCount(fmt.substring(s, i)) : 0;
                }
            }
            while (i < n && "hlL".indexOf(fmt.charAt(i)) >= 0) {
                i++;
            }
            if (i >= n) {
                throw PyException.valueError("incomplete format");
            }
            char type = fmt.charAt(i++);
            if (type == '%') {
                out.append('%');
                continue;
            }
            if (!usedKey) {
                if (dict != null && !tuple) {
                    value = args;
                    argIdx = 1;
                } else {
                    if (argIdx >= argList.size()) {
                        throw PyException.typeError("not enough arguments for format string");
                    }
                    value = argList.get(argIdx++);
                }
            }
            String body;
            String sign = "";
            boolean numeric = false;
            switch (type) {
                case 's' -> body = value.str();
                case 'r' -> body = value.repr();
                case 'a' -> body = PyStr.ascii(value.repr());
                case 'c' -> {
                    if (value instanceof PyStr s && s.len() == 1) {
                        body = s.value();
                    } else if (value instanceof PyInt ci) {
                        BigInteger b = ci.value();
                        if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(0x10ffff)) > 0) {
                            throw new PyException("OverflowError", "%c arg not in range(0x110000)");
                        }
                        body = new String(Character.toChars(b.intValue()));
                    } else {
                        throw PyException.typeError("%c requires int or char");
                    }
                }
                case 'd', 'i', 'u', 'x', 'X', 'o' -> {
                    numeric = true;
                    PyInt iv;
                    if (value instanceof PyInt vi) {
                        iv = vi;
                    } else if (value instanceof PyFloat vf && (type == 'd' || type == 'i' || type == 'u')) {
                        iv = Py.floatToInt(vf.value());
                    } else if (type == 'd' || type == 'i' || type == 'u') {
                        throw PyException.typeError("%" + type + " format: a real number is required, not "
                                + value.typeName());
                    } else {
                        throw PyException.typeError("%" + type + " format: an integer is required, not "
                                + value.typeName());
                    }
                    BigInteger b = iv.value();
                    int radix = type == 'x' || type == 'X' ? 16 : type == 'o' ? 8 : 10;
                    body = b.abs().toString(radix);
                    if (radix == 10 && body.length() > PyInt.MAX_STR_DIGITS) {
                        iv.repr();
                    }
                    if (type == 'X') {
                        body = body.toUpperCase();
                    }
                    if (prec > body.length()) {
                        body = "0".repeat(prec - body.length()) + body;
                    }
                    sign = b.signum() < 0 ? "-" : plus ? "+" : space ? " " : "";
                    if (alt && radix == 16) {
                        sign += type == 'x' ? "0x" : "0X";
                    } else if (alt && radix == 8) {
                        sign += "0o";
                    }
                }
                case 'e', 'E', 'f', 'F', 'g', 'G' -> {
                    numeric = true;
                    double d;
                    if (value instanceof PyFloat vf) {
                        d = vf.value();
                    } else if (value instanceof PyInt vi) {
                        d = vi.toDouble();
                    } else {
                        throw PyException.typeError("must be real number, not " + value.typeName());
                    }
                    int p = prec < 0 ? 6 : prec;
                    double ad = Math.abs(d);
                    if (Double.isNaN(d)) {
                        body = "nan";
                    } else if (Double.isInfinite(d)) {
                        body = "inf";
                    } else {
                        body = switch (type) {
                            case 'e', 'E' -> FloatFormat.formatE(ad, p, alt, 'e');
                            case 'f', 'F' -> FloatFormat.formatF(ad, p, alt);
                            default -> FloatFormat.formatG(ad, p, alt, false, 'e');
                        };
                    }
                    if (Character.isUpperCase(type)) {
                        body = body.toUpperCase();
                    }
                    boolean negative = !Double.isNaN(d) && (d < 0 || (d == 0 && 1 / d < 0));
                    sign = negative ? "-" : plus ? "+" : space ? " " : "";
                }
                default -> throw PyException.valueError("unsupported format character '" + type + "' (0x"
                        + Integer.toHexString(type) + ") at index " + (i - 1));
            }
            if (prec >= 0 && (type == 's' || type == 'r' || type == 'a')) {
                PyStr b = PyStr.of(body);
                if (prec < b.len()) {
                    body = b.substring(0, prec);
                }
            }
            int len = PyStr.of(sign + body).len();
            if (width > len) {
                int padding = width - len;
                if (left) {
                    out.append(sign).append(body).append(" ".repeat(padding));
                } else if (zero && numeric) {
                    out.append(sign).append("0".repeat(padding)).append(body);
                } else {
                    out.append(" ".repeat(padding)).append(sign).append(body);
                }
            } else {
                out.append(sign).append(body);
            }
        }
        if (argIdx < argList.size() && dict == null) {
            throw PyException.typeError("not all arguments converted during string formatting");
        }
        return out.toString();
    }
}
