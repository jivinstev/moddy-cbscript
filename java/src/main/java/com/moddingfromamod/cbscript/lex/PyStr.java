// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.lex;

/** Python {@code str} methods whose meaning differs from Java's nearest ones (Unicode whitespace, isalnum...). */
public final class PyStr {
    private PyStr() {
    }

    /** {@code c.isspace()} for one code point. */
    public static boolean isSpace(int c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000;
    }

    /** {@code s.strip()}. */
    public static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.codePointAt(a))) {
            a += Character.charCount(s.codePointAt(a));
        }
        while (b > a && isSpace(s.codePointBefore(b))) {
            b -= Character.charCount(s.codePointBefore(b));
        }
        return s.substring(a, b);
    }

    /** {@code c.isalnum()}. */
    public static boolean isAlnum(int c) {
        if (Character.isLetter(c)) {
            return true;
        }
        int t = Character.getType(c);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    /** {@code c.isupper()} for one code point (a cased, uppercase or titlecase-free letter). */
    public static boolean isUpper(int c) {
        return Character.isUpperCase(c);
    }

    /** Index into code points: number of code points before UTF-16 index {@code i}. */
    public static int cpIndex(String s, int i) {
        return s.codePointCount(0, i);
    }
}
