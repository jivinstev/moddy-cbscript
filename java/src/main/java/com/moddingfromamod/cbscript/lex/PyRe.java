// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.lex;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python {@code re} patterns, run by {@code java.util.regex} with Python's meaning.
 *
 * <p>On a {@code str} pattern Python's {@code \s}, {@code \d}, {@code \w} and {@code \b} are Unicode-aware and
 * {@code .} excludes only {@code \n}; Java's defaults are ASCII and exclude every line terminator. {@link #compile}
 * rewrites those escapes into explicit classes with Python's membership and sets {@code UNIX_LINES}, and drops the
 * names of {@code (?P<name>...)} groups (Java group names cannot hold an underscore) while keeping their numbers.
 */
public final class PyRe {
    /** Python's {@code \s} for str patterns: the code points for which {@code str.isspace()} is true. */
    public static final String SPACE = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
    /** Python's {@code \w}: {@code str.isalnum()} or underscore. */
    public static final String WORD = "\\p{L}\\p{Nd}\\p{Nl}\\p{No}_";
    /** Python's {@code \d}: Unicode decimal digits. */
    public static final String DIGIT = "\\p{Nd}";

    public static final int IGNORECASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    public static final int MULTILINE = Pattern.MULTILINE;

    private PyRe() {
    }

    public static Pattern compile(String python) {
        return compile(python, 0);
    }

    public static Pattern compile(String python, int flags) {
        return Pattern.compile(translate(python), flags | Pattern.UNIX_LINES);
    }

    /** The Java spelling of a Python pattern (see the class comment). */
    public static String translate(String p) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                char n = p.charAt(++i);
                switch (n) {
                    case 's' -> out.append(inClass ? SPACE : "[" + SPACE + "]");
                    case 'S' -> out.append(inClass ? unsupported(p) : "[^" + SPACE + "]");
                    case 'd' -> out.append(inClass ? DIGIT : "[" + DIGIT + "]");
                    case 'D' -> out.append(inClass ? unsupported(p) : "[^" + DIGIT + "]");
                    case 'w' -> out.append(inClass ? WORD : "[" + WORD + "]");
                    case 'W' -> out.append(inClass ? unsupported(p) : "[^" + WORD + "]");
                    case 'b' -> out.append(inClass ? "\\x08"
                            : "(?:(?<=[" + WORD + "])(?![" + WORD + "])|(?<![" + WORD + "])(?=[" + WORD + "]))");
                    default -> out.append('\\').append(n);
                }
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                } else if (c == '[') {
                    out.append('\\');  // a literal '[' inside a class in Python; Java would nest
                }
                out.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                // A ']' or '^]' right after '[' is a literal in Python.
                if (i + 1 < p.length() && p.charAt(i + 1) == '^') {
                    out.append('^');
                    i++;
                }
                if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
                    out.append("\\]");
                    i++;
                }
                continue;
            }
            if (c == '(' && p.startsWith("(?P<", i)) {
                int close = p.indexOf('>', i);
                out.append('(');
                i = close;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String unsupported(String p) {
        throw new IllegalArgumentException("negated class escape inside a class: " + p);
    }

    /** {@code pattern.sub(fn, text)}: every match replaced by {@code fn(match)}, taken literally. */
    public static String sub(Pattern pattern, String text, Function<MatchResult, String> fn) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            out.append(fn.apply(m.toMatchResult()));
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** {@code re.findall} for a pattern with exactly one group. */
    public static List<String> findall1(Pattern pattern, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(1) == null ? "" : m.group(1));
        }
        return out;
    }

    /** {@code pattern.match(text)}: anchored at the start, need not reach the end; null if none. */
    public static MatchResult match(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.lookingAt() ? m.toMatchResult() : null;
    }

    /** {@code pattern.fullmatch(text)}. */
    public static boolean fullmatch(Pattern pattern, String text) {
        return pattern.matcher(text).matches();
    }

    /** {@code pattern.search(text, pos)}; null if none. */
    public static MatchResult search(Pattern pattern, String text, int pos) {
        Matcher m = pattern.matcher(text);
        return pos <= text.length() && m.find(pos) ? m.toMatchResult() : null;
    }
}
