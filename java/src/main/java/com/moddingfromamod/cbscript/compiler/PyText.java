// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

/** Python str indexing (by code point) for the places the compiler slices text. */
final class PyText {
    private PyText() {
    }

    static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@code s[a:b]} with non-negative bounds, clamped as Python clamps. */
    static String slice(String s, int a, int b) {
        int n = len(s);
        a = Math.min(Math.max(a, 0), n);
        b = Math.min(Math.max(b, a), n);
        return s.substring(s.offsetByCodePoints(0, a), s.offsetByCodePoints(0, b));
    }

    /** {@code s[a:]}. */
    static String from(String s, int a) {
        return slice(s, a, Integer.MAX_VALUE);
    }

    /** {@code s[0]} as a string; IndexError on empty. */
    static String first(String s) {
        if (s.isEmpty()) {
            throw new com.moddingfromamod.cbscript.py.PyException("IndexError", "string index out of range");
        }
        return s.substring(0, Character.charCount(s.codePointAt(0)));
    }
}
