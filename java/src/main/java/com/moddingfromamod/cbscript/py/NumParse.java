// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** {@code int(str, base)} and {@code float(str)} parsing, with CPython's syntax and error messages. */
final class NumParse {
    private NumParse() {}

    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern SPECIAL = Pattern.compile("[+-]?(inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

    /** Python's str.isspace for one code point (_PyUnicode_IsWhitespace). */
    static boolean isSpace(int c) {
        return (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f
                || c == 0x3000;
    }

    /**
     * _PyUnicode_TransformDecimalAndSpaceToASCII: Unicode whitespace to ' ', Unicode decimal digits to ASCII digits,
     * any other non-ASCII character to '?' (which no parser accepts).
     */
    private static String toAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c < 128) {
                sb.append((char) c);
            } else if (isSpace(c)) {
                sb.append(' ');
            } else if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER) {
                sb.append((char) ('0' + Character.digit(c, 10)));
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String stripAsciiSpace(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\n\u000b\f\r".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\n\u000b\f\r".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    private static String reprTruncated(String s) {
        String r = PyStr.repr(s);
        return r.length() > 200 ? r.substring(0, 200) : r;
    }

    static PyInt parseInt(String orig, int base) {
        PyInt r = tryParseInt(orig, base);
        if (r == null) {
            throw PyException.valueError("invalid literal for int() with base " + base + ": " + reprTruncated(orig));
        }
        return r;
    }

    private static PyInt tryParseInt(String orig, int base) {
        String s = stripAsciiSpace(toAscii(orig));
        int i = 0;
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        boolean prefixed = false;
        if (i + 1 < s.length() && s.charAt(i) == '0') {
            char p = Character.toLowerCase(s.charAt(i + 1));
            int pb = p == 'x' ? 16 : p == 'o' ? 8 : p == 'b' ? 2 : 0;
            if (pb != 0 && (base == 0 || base == pb)) {
                base = pb;
                i += 2;
                prefixed = true;
            }
        }
        boolean decimalZeroCheck = false;
        if (base == 0) {
            base = 10;
            decimalZeroCheck = i < s.length() && s.charAt(i) == '0';
        }
        StringBuilder digits = new StringBuilder();
        boolean lastUnderscore = false;
        if (i < s.length() && s.charAt(i) == '_') {
            if (!prefixed) {
                return null;
            }
            i++;
            lastUnderscore = true;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                if (lastUnderscore || digits.length() == 0) {
                    return null;
                }
                lastUnderscore = true;
                continue;
            }
            int d = Character.digit(c, base);
            if (d < 0 || c > 127) {
                return null;
            }
            digits.append(c);
            lastUnderscore = false;
        }
        if (digits.length() == 0 || lastUnderscore) {
            return null;
        }
        if (decimalZeroCheck && digits.chars().anyMatch(c -> c != '0')) {
            return null;
        }
        if ((base & (base - 1)) != 0 && digits.length() > PyInt.MAX_STR_DIGITS) {
            throw PyException.valueError("Exceeds the limit (4300 digits) for integer string conversion: value has "
                    + digits.length() + " digits; use sys.set_int_max_str_digits() to increase the limit");
        }
        BigInteger v = new BigInteger(digits.toString(), base);
        return PyInt.of(negative ? v.negate() : v);
    }

    static double parseFloat(String orig) {
        String s = stripAsciiSpace(toAscii(orig));
        // Underscores are allowed only between two digits.
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                if (i == 0 || i + 1 >= s.length() || !isDigit(s.charAt(i - 1)) || !isDigit(s.charAt(i + 1))) {
                    throw notFloat(orig);
                }
                continue;
            }
            t.append(c);
        }
        String u = t.toString();
        if (SPECIAL.matcher(u).matches()) {
            boolean neg = u.startsWith("-");
            String w = u.replaceFirst("^[+-]", "").toLowerCase();
            if (w.equals("nan")) {
                return Double.NaN;
            }
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!FLOAT.matcher(u).matches()) {
            throw notFloat(orig);
        }
        return Double.parseDouble(u);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static PyException notFloat(String orig) {
        return PyException.valueError("could not convert string to float: " + PyStr.repr(orig));
    }
}
