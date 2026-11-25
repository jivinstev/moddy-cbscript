// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/** Tokenizer for one Python expression (eval input), with CPython's syntax error messages for common mistakes. */
final class PyLexer {

    enum T { NAME, NUMBER, STRING, OP, NEWLINE, END }

    /** A token. For STRING, value is a {@link StrLit}; for NUMBER, the PyObject. */
    record Tok(T type, String text, Object value, int line) {}

    /** A string literal: plain text, or (for an f-string) a list of String and {@link FExpr} parts. */
    record StrLit(boolean bytes, boolean fstring, List<Object> parts) {}

    /** A replacement field of an f-string: expression source, conversion, spec parts, '=' debug text. */
    record FExpr(String source, int line, char conversion, List<Object> spec, String debugText) {}

    static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
            "try", "while", "with", "yield");

    private static final String[] OPS = {"**=", "//=", ">>=", "<<=", "...", "**", "//", "<<", ">>", "<=", ">=",
        "==", "!=", "->", ":=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=", "<>",
        "(", ")", "[", "]", "{", "}", ",", ":", ".", ";", "@", "=", "+", "-", "*", "/", "%", "&", "|", "^", "~",
        "<", ">", "!"};

    private final String src;
    private int pos;
    private int line = 1;
    private final Deque<char[]> brackets = new ArrayDeque<>();
    private final List<Tok> out = new ArrayList<>();
    private int specDepth;

    private PyLexer(String src) {
        this.src = src;
    }

    static PyException syntaxError(String msg, int line) {
        return new PyException("SyntaxError", msg + " (<string>, line " + line + ")");
    }

    /** Tokenizes an eval source (leading spaces and tabs already stripped by the caller). */
    static List<Tok> tokenize(String src) {
        PyLexer lx = new PyLexer(src);
        lx.run();
        return lx.out;
    }

    static boolean isIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int c0 = s.codePointAt(0);
        if (!(c0 == '_' || Character.isUnicodeIdentifierStart(c0))) {
            return false;
        }
        for (int i = Character.charCount(c0); i < s.length(); ) {
            int c = s.codePointAt(i);
            if (!(c == '_' || Character.isUnicodeIdentifierPart(c)) || Character.isIdentifierIgnorable(c)) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }

    private char peek(int k) {
        return pos + k < src.length() ? src.charAt(pos + k) : '\0';
    }

    private void run() {
        while (true) {
            // whitespace, comments, line continuations
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\f') {
                    pos++;
                } else if (c == '#') {
                    while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
                        pos++;
                    }
                } else if (c == '\\') {
                    if (peek(1) == '\n' || (peek(1) == '\r')) {
                        pos += (peek(1) == '\r' && peek(2) == '\n') ? 3 : 2;
                        line++;
                    } else {
                        throw syntaxError("unexpected character after line continuation character", line);
                    }
                } else if (c == '\n' || c == '\r') {
                    pos += (c == '\r' && peek(1) == '\n') ? 2 : 1;
                    if (brackets.isEmpty() && !out.isEmpty() && out.get(out.size() - 1).type() != T.NEWLINE) {
                        out.add(new Tok(T.NEWLINE, "\n", null, line));
                    }
                    line++;
                } else {
                    break;
                }
            }
            if (pos >= src.length()) {
                if (!brackets.isEmpty()) {
                    char[] b = brackets.peek();
                    throw syntaxError("'" + b[0] + "' was never closed", b[1]);
                }
                out.add(new Tok(T.END, "", null, line));
                return;
            }
            char c = src.charAt(pos);
            int cp = src.codePointAt(pos);
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                number();
            } else if (c == '"' || c == '\'' || (isStringPrefixStart())) {
                if (!string()) {
                    name();
                }
            } else if (cp == '_' || Character.isUnicodeIdentifierStart(cp)) {
                name();
            } else {
                op();
            }
        }
    }

    private boolean isStringPrefixStart() {
        int i = pos;
        while (i < src.length() && i - pos < 3 && "rRbBuUfF".indexOf(src.charAt(i)) >= 0) {
            i++;
        }
        return i > pos && i < src.length() && (src.charAt(i) == '"' || src.charAt(i) == '\'');
    }

    private void name() {
        int start = pos;
        while (pos < src.length()) {
            int cp = src.codePointAt(pos);
            if (cp == '_' || Character.isUnicodeIdentifierPart(cp) && !Character.isIdentifierIgnorable(cp)) {
                pos += Character.charCount(cp);
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        boolean ascii = text.chars().allMatch(ch -> ch < 128);
        if (!ascii) {
            text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        }
        out.add(new Tok(T.NAME, text, null, line));
    }

    private void op() {
        for (String o : OPS) {
            if (src.startsWith(o, pos)) {
                pos += o.length();
                switch (o) {
                    case "(", "[", "{" -> brackets.push(new char[] {o.charAt(0), (char) line});
                    case ")", "]", "}" -> {
                        if (brackets.isEmpty()) {
                            throw syntaxError("unmatched '" + o + "'", line);
                        }
                        char open = brackets.pop()[0];
                        char want = open == '(' ? ')' : open == '[' ? ']' : '}';
                        if (want != o.charAt(0)) {
                            throw syntaxError("closing parenthesis '" + o + "' does not match opening parenthesis '"
                                    + open + "'", line);
                        }
                    }
                    case "<>" -> throw syntaxError("invalid syntax.  Maybe you meant '!=' instead of '<>'?", line);
                    default -> { }
                }
                out.add(new Tok(T.OP, o, null, line));
                return;
            }
        }
        throw syntaxError("invalid syntax", line);
    }

    // ---- numbers ----

    private void number() {
        int start = pos;
        char c = src.charAt(pos);
        if (c == '0' && "xXoObB".indexOf(peek(1)) >= 0) {
            char kind = Character.toLowerCase(peek(1));
            int radix = kind == 'x' ? 16 : kind == 'o' ? 8 : 2;
            String what = kind == 'x' ? "hexadecimal" : kind == 'o' ? "octal" : "binary";
            pos += 2;
            StringBuilder digits = new StringBuilder();
            boolean lastUnderscore = true;
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (d == '_') {
                    if (lastUnderscore && digits.length() > 0) {
                        throw syntaxError("invalid " + what + " literal", line);
                    }
                    lastUnderscore = true;
                    pos++;
                } else if (Character.digit(d, 16) >= 0 && d < 128) {
                    if (Character.digit(d, radix) < 0) {
                        if (radix != 16 && Character.isDigit(d)) {
                            throw syntaxError("invalid digit '" + d + "' in " + what + " literal", line);
                        }
                        throw syntaxError("invalid " + what + " literal", line);
                    }
                    digits.append(d);
                    lastUnderscore = false;
                    pos++;
                } else {
                    break;
                }
            }
            if (digits.length() == 0 || lastUnderscore || !endOfNumber()) {
                throw syntaxError("invalid " + what + " literal", line);
            }
            out.add(new Tok(T.NUMBER, src.substring(start, pos), PyInt.of(new BigInteger(digits.toString(), radix)),
                    line));
            return;
        }
        String intPart = digitRun();
        boolean isFloat = false;
        String frac = "";
        String exp = "";
        if (peek(0) == '.') {
            isFloat = true;
            pos++;
            if (Character.isDigit(peek(0))) {
                frac = digitRun();
            }
        }
        if (peek(0) == 'e' || peek(0) == 'E') {
            int save = pos;
            pos++;
            String sign = "";
            if (peek(0) == '+' || peek(0) == '-') {
                sign = String.valueOf(peek(0));
                pos++;
            }
            if (!Character.isDigit(peek(0))) {
                pos = save;
                throw syntaxError("invalid decimal literal", line);
            }
            exp = "e" + sign + digitRun();
            isFloat = true;
        }
        if (peek(0) == 'j' || peek(0) == 'J') {
            throw syntaxError("complex numbers are not supported", line);
        }
        if (!endOfNumber()) {
            throw syntaxError("invalid decimal literal", line);
        }
        String text = src.substring(start, pos);
        if (isFloat) {
            String s = (intPart.isEmpty() ? "0" : intPart) + "." + (frac.isEmpty() ? "0" : frac) + exp;
            out.add(new Tok(T.NUMBER, text, Py.of(Double.parseDouble(s)), line));
        } else {
            if (intPart.length() > 1 && intPart.charAt(0) == '0' && intPart.chars().anyMatch(d -> d != '0')) {
                throw syntaxError("leading zeros in decimal integer literals are not permitted; use an 0o prefix "
                        + "for octal integers", line);
            }
            if (intPart.length() > PyInt.MAX_STR_DIGITS) {
                throw syntaxError("Exceeds the limit (4300 digits) for integer string conversion: value has "
                        + intPart.length() + " digits; use sys.set_int_max_str_digits() to increase the limit "
                        + "- Consider hexadecimal for huge integer literals to avoid decimal conversion limits.",
                        line);
            }
            out.add(new Tok(T.NUMBER, text, PyInt.of(new BigInteger(intPart)), line));
        }
    }

    /**
     * verify_end_of_number: a number may be followed directly by one of the keywords that can follow it in valid
     * code ("1if x else y"), but not by any other identifier character.
     */
    private boolean endOfNumber() {
        for (String kw : new String[] {"and", "else", "for", "if", "in", "is", "or", "not"}) {
            if (src.startsWith(kw, pos)) {
                return true;
            }
        }
        char c = peek(0);
        return !(c < 128 && isIdentChar(c));
    }

    private boolean isIdentChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /** Digits with single underscores between them. */
    private String digitRun() {
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char d = src.charAt(pos);
            if (d >= '0' && d <= '9') {
                sb.append(d);
                pos++;
            } else if (d == '_') {
                if (sb.length() == 0 || !(peek(1) >= '0' && peek(1) <= '9')) {
                    throw syntaxError("invalid decimal literal", line);
                }
                pos++;
            } else {
                break;
            }
        }
        return sb.toString();
    }

    // ---- strings ----

    /** Lexes a string literal at pos; false if the prefix turned out not to start a string. */
    private boolean string() {
        int start = pos;
        int startLine = line;
        String prefix = "";
        while (pos < src.length() && "rRbBuUfF".indexOf(src.charAt(pos)) >= 0) {
            prefix += Character.toLowerCase(src.charAt(pos));
            pos++;
        }
        if (pos >= src.length() || (src.charAt(pos) != '"' && src.charAt(pos) != '\'')) {
            pos = start;
            return false;
        }
        boolean raw = prefix.contains("r");
        boolean bytes = prefix.contains("b");
        boolean fstr = prefix.contains("f");
        boolean valid = switch (prefix) {
            case "", "r", "u", "b", "br", "rb", "f", "fr", "rf" -> true;
            default -> false;
        };
        if (!valid) {
            pos = start;
            return false;
        }
        char q = src.charAt(pos);
        boolean triple = src.startsWith(String.valueOf(q).repeat(3), pos);
        pos += triple ? 3 : 1;
        int bodyStart = pos;
        List<Object> parts = new ArrayList<>();
        if (fstr) {
            fstringBody(q, triple, raw, parts, startLine, false);
        } else {
            StringBuilder sb = new StringBuilder();
            boolean escapedQuote = false;
            while (true) {
                if (pos >= src.length()) {
                    throw unterminated(triple, startLine, false, escapedQuote);
                }
                char c = src.charAt(pos);
                if (c == q && (!triple || src.startsWith(String.valueOf(q).repeat(3), pos))) {
                    pos += triple ? 3 : 1;
                    break;
                }
                if ((c == '\n' || c == '\r') && !triple) {
                    throw unterminated(false, startLine, false, escapedQuote);
                }
                if (c == '\\') {
                    escapedQuote |= peek(1) == q;
                    escape(sb, raw, bytes, bodyStart);
                    continue;
                }
                if (c == '\n') {
                    line++;
                }
                if (bytes && c > 127) {
                    throw syntaxError("bytes can only contain ASCII literal characters", line);
                }
                sb.append(c);
                pos++;
            }
            parts.add(sb.toString());
        }
        out.add(new Tok(T.STRING, src.substring(start, pos), new StrLit(bytes, fstr, parts), startLine));
        return true;
    }

    private PyException unterminated(boolean triple, int startLine) {
        return unterminated(triple, startLine, false, false);
    }

    private PyException unterminated(boolean triple, int startLine, boolean fstring, boolean escapedQuote) {
        String kind = fstring ? "f-string" : "string";
        if (triple) {
            return syntaxError("unterminated triple-quoted " + kind + " literal (detected at line " + line + ")",
                    startLine);
        }
        return syntaxError("unterminated " + kind + " literal (detected at line " + line + ")"
                + (escapedQuote ? "; perhaps you escaped the end quote?" : ""), startLine);
    }

    /** Handles a backslash at pos inside a string (raw: kept verbatim). */
    private void escape(StringBuilder sb, boolean raw, boolean bytes, int bodyStart) {
        char n = peek(1);
        if (raw) {
            sb.append('\\');
            if (n != '\0') {
                if (n == '\n') {
                    line++;
                }
                sb.append(n);
                pos += 2;
            } else {
                pos++;
            }
            return;
        }
        int escStart = src.substring(bodyStart, pos).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        pos += 2;
        switch (n) {
            case '\n' -> line++;
            case '\r' -> {
                if (peek(0) == '\n') {
                    pos++;
                }
                line++;
            }
            case '\\' -> sb.append('\\');
            case '\'' -> sb.append('\'');
            case '"' -> sb.append('"');
            case 'a' -> sb.append('\u0007');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'v' -> sb.append('\u000b');
            case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                int v = n - '0';
                for (int k = 0; k < 2 && peek(0) >= '0' && peek(0) <= '7'; k++) {
                    v = v * 8 + (src.charAt(pos++) - '0');
                }
                sb.append((char) v);
            }
            case 'x' -> sb.appendCodePoint(hexEscape(2, "\\xXX", escStart));
            case 'u', 'U', 'N' -> {
                if (bytes) {
                    sb.append('\\').append(n);
                } else if (n == 'N') {
                    if (peek(0) != '{') {
                        throw unicodeError("malformed \\N character escape", escStart, 1);
                    }
                    int close = src.indexOf('}', pos);
                    if (close < 0) {
                        throw unicodeError("malformed \\N character escape", escStart, 1);
                    }
                    String nm = src.substring(pos + 1, close);
                    int cp;
                    try {
                        cp = Character.codePointOf(nm);
                    } catch (IllegalArgumentException e) {
                        throw unicodeError("unknown Unicode character name", escStart, close - pos + 2);
                    }
                    pos = close + 1;
                    sb.appendCodePoint(cp);
                } else {
                    int cp = hexEscape(n == 'u' ? 4 : 8, n == 'u' ? "\\uXXXX" : "\\UXXXXXXXX", escStart);
                    if (cp > 0x10ffff) {
                        throw unicodeError("illegal Unicode character", escStart, 9);
                    }
                    sb.appendCodePoint(cp);
                }
            }
            case '\0' -> sb.append('\\');
            default -> sb.append('\\').append(n);
        }
    }

    private int hexEscape(int count, String name, int escStart) {
        int v = 0;
        for (int k = 0; k < count; k++) {
            int d = Character.digit(peek(0), 16);
            if (d < 0 || peek(0) > 127) {
                throw unicodeError("truncated " + name + " escape", escStart, k + 1);
            }
            v = v * 16 + d;
            pos++;
        }
        return v;
    }

    private PyException unicodeError(String what, int escStart, int extra) {
        return syntaxError("(unicode error) 'unicodeescape' codec can't decode bytes in position " + escStart + "-"
                + (escStart + extra) + ": " + what, line);
    }

    /**
     * The body of an f-string (or of a nested format spec when inSpec): literal text with {{ }} and escapes, and
     * replacement fields. Leaves pos after the closing quote (or, inSpec, at the '}' that ends the spec).
     */
    private void fstringBody(char q, boolean triple, boolean raw, List<Object> parts, int startLine,
            boolean inSpec) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                if (inSpec) {
                    throw syntaxError("f-string: expecting '}'", startLine);
                }
                throw unterminated(triple, startLine, true, false);
            }
            char c = src.charAt(pos);
            if (c == q && (!triple || src.startsWith(String.valueOf(q).repeat(3), pos))) {
                if (inSpec) {
                    throw syntaxError("f-string: expecting '}'", startLine);
                }
                pos += triple ? 3 : 1;
                break;
            }
            if ((c == '\n' || c == '\r') && !triple) {
                if (inSpec) {
                    throw syntaxError("f-string: newlines are not allowed in format specifiers for single "
                            + "quoted f-strings", line);
                }
                throw unterminated(false, startLine, true, false);
            }
            if (c == '{') {
                if (!inSpec && peek(1) == '{') {
                    sb.append('{');
                    pos += 2;
                    continue;
                }
                if (sb.length() > 0) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                }
                pos++;
                parts.add(replacementField(q, triple, raw, startLine));
                continue;
            }
            if (c == '}') {
                if (inSpec) {
                    break;
                }
                if (peek(1) == '}') {
                    sb.append('}');
                    pos += 2;
                    continue;
                }
                throw syntaxError("f-string: single '}' is not allowed", line);
            }
            if (c == '\\') {
                escape(sb, raw, false, pos);
                continue;
            }
            if (c == '\n') {
                line++;
            }
            sb.append(c);
            pos++;
        }
        if (sb.length() > 0) {
            parts.add(sb.toString());
        }
    }

    /** Parses a replacement field after '{': expression text, '=', '!conv', ':spec', and the closing '}'. */
    private FExpr replacementField(char q, boolean triple, boolean raw, int startLine) {
        int exprStart = pos;
        int depth = 0;
        int exprLine = line;
        while (true) {
            if (pos >= src.length()) {
                throw syntaxError("'{' was never closed", startLine);
            }
            char c = src.charAt(pos);
            if (c == q && (!triple || src.startsWith(String.valueOf(q).repeat(3), pos)) && depth == 0) {
                throw syntaxError("f-string: expecting '}'", startLine);
            }
            if (c == '\'' || c == '"') {
                skipNestedString();
                continue;
            }
            if (c == '\\') {
                // Python 3.12+ reads a backslash in the expression as a line continuation.
                if (peek(1) != '\n') {
                    throw syntaxError("unexpected character after line continuation character", line);
                }
                pos += 2;
                line++;
                continue;
            }
            if (c == '#') {
                // a comment runs to the end of the line, so the field is never closed
                throw syntaxError("'{' was never closed", startLine);
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '}') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0 && c == '!' && peek(1) != '=') {
                break;
            } else if (depth == 0 && c == ':') {
                break;
            } else if (depth == 0 && c == '=' && peek(1) != '=' && pos > exprStart
                    && "=!<>".indexOf(src.charAt(pos - 1)) < 0) {
                break;
            } else if (c == '\n') {
                line++;
            }
            pos++;
        }
        String expr = src.substring(exprStart, pos);
        if (expr.isBlank()) {
            char c = src.charAt(pos);
            throw syntaxError("f-string: valid expression required before '" + c + "'", line);
        }
        String debug = null;
        if (src.charAt(pos) == '=') {
            pos++;
            debug = src.substring(exprStart, pos);
            // whitespace after '=' is part of the debug text
            while (pos < src.length() && (src.charAt(pos) == ' ')) {
                debug += " ";
                pos++;
            }
        }
        char conv = 0;
        if (pos < src.length() && src.charAt(pos) == '!') {
            pos++;
            char cc = peek(0);
            if (cc != 's' && cc != 'r' && cc != 'a') {
                if (cc == ':' || cc == '}') {
                    throw syntaxError("f-string: missing conversion character", line);
                }
                throw syntaxError("f-string: invalid conversion character '" + cc
                        + "': expected 's', 'r', or 'a'", line);
            }
            conv = cc;
            pos++;
            while (pos < src.length() && src.charAt(pos) == ' ') {
                pos++;
            }
            if (pos < src.length() && src.charAt(pos) != ':' && src.charAt(pos) != '}' && src.charAt(pos) != q) {
                throw syntaxError("f-string: expecting ':' or '}'", line);
            }
        }
        List<Object> spec = null;
        if (pos < src.length() && src.charAt(pos) == ':') {
            pos++;
            if (pos >= src.length() || src.charAt(pos) == q) {
                throw syntaxError("f-string: expecting '}', or format specs", startLine);
            }
            if (++specDepth > 2) {
                throw syntaxError("f-string: expressions nested too deeply", startLine);
            }
            spec = new ArrayList<>();
            fstringBody(q, triple, raw, spec, startLine, true);
            specDepth--;
        }
        if (pos >= src.length() || src.charAt(pos) != '}') {
            throw syntaxError("f-string: expecting '}'", startLine);
        }
        pos++;
        return new FExpr(expr, exprLine, conv, spec, debug);
    }

    /** Skips a string literal inside an f-string expression (any quote style). */
    private void skipNestedString() {
        char q = src.charAt(pos);
        boolean triple = src.startsWith(String.valueOf(q).repeat(3), pos);
        pos += triple ? 3 : 1;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos += 2;
                continue;
            }
            if (c == q && (!triple || src.startsWith(String.valueOf(q).repeat(3), pos))) {
                pos += triple ? 3 : 1;
                return;
            }
            pos++;
        }
        throw unterminated(triple, line);
    }
}
