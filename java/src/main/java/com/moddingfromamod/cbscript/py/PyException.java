// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A Python exception, as the Python compiler would raise it: its Python type name and its {@code str()}.
 *
 * <p>The compiler prints exceptions into its log ({@code print(e)} prints {@code str(e)}; a traceback prints the
 * chain), so the type and text are part of the output being matched. Python links an exception raised while another
 * is being handled to it ({@code __context__}); {@link #handling} reproduces that: Java code that mirrors a Python
 * {@code except} block wraps its body in {@code handling(e)}/{@code done()}, and every exception constructed meanwhile
 * gets {@code e} as its context.
 */
public class PyException extends RuntimeException {
    private static final ThreadLocal<Deque<PyException>> HANDLING = ThreadLocal.withInitial(ArrayDeque::new);

    /** Python type name, e.g. {@code ValueError}, {@code CompileError}, {@code SyntaxError}. */
    public final String type;
    /** {@code str(e)} in Python. */
    public final String pyStr;
    /** The exception being handled when this one was raised ({@code __context__}), or null. */
    public PyException context;

    public PyException(String type, String pyStr) {
        super(type + ": " + pyStr, null, false, false);
        this.type = type;
        this.pyStr = pyStr;
        PyException current = HANDLING.get().peek();
        if (current != null && current != this) {
            this.context = current;
        }
    }

    /** {@code repr(e)}: {@code Type('message')}, or {@code Type()} for an exception without arguments. */
    public String repr() {
        if (reprArgs != null) {
            return type + "(" + reprArgs + ")";
        }
        return type + "(" + reprString(pyStr) + ")";
    }

    /** The repr of the exception's arguments when it is not repr(str(e)) (KeyError, OSError...). */
    public String reprArgs;

    /** CPython's {@code repr()} of a str. */
    public static String reprString(String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == quote || c == '\\') {
                b.append('\\').appendCodePoint(c);
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < 0x20 || c == 0x7f) {
                b.append(String.format("\\x%02x", c));
            } else if (c < 0x7f) {
                b.appendCodePoint(c);
            } else if (isPrintable(c)) {
                b.appendCodePoint(c);
            } else if (c <= 0xff) {
                b.append(String.format("\\x%02x", c));
            } else if (c <= 0xffff) {
                b.append(String.format("\\u%04x", c));
            } else {
                b.append(String.format("\\U%08x", c));
            }
        }
        return b.append(quote).toString();
    }

    /** CPython's Py_UNICODE_ISPRINTABLE. */
    public static boolean isPrintable(int c) {
        if (c == ' ') {
            return true;
        }
        int t = Character.getType(c);
        return !(t == Character.CONTROL || t == Character.FORMAT || t == Character.SURROGATE
                || t == Character.PRIVATE_USE || t == Character.UNASSIGNED || t == Character.LINE_SEPARATOR
                || t == Character.PARAGRAPH_SEPARATOR || t == Character.SPACE_SEPARATOR);
    }

    /** What a 3.12+ traceback appends to the last line ("Did you mean: ...?"), or null. */
    public String tracebackNote;

    /** {@code str(e)}. */
    public String str() {
        return pyStr;
    }

    /** The name a traceback's last line shows: the module-qualified name for a class defined in a module. */
    public String tracebackName() {
        return switch (type) {
            case "CompileError" -> "CompileError.CompileError";
            default -> type;
        };
    }

    /** True if this is an instance of Python type {@code name} or one of its subclasses that matter here. */
    public boolean isA(String name) {
        if (type.equals(name)) {
            return true;
        }
        return switch (name) {
            case "Exception" -> !type.equals("SystemExit") && !type.equals("KeyboardInterrupt");
            case "ValueError" -> type.equals("JSONDecodeError");
            case "RuntimeError" -> type.equals("RecursionError");
            case "LookupError" -> type.equals("KeyError") || type.equals("IndexError");
            case "ArithmeticError" -> type.equals("ZeroDivisionError") || type.equals("OverflowError");
            case "OSError" -> type.equals("FileNotFoundError");
            case "SyntaxError" -> type.equals("IndentationError") || type.equals("TabError");
            default -> false;
        };
    }

    /** Enter a Python {@code except} block that caught {@code e}. Pair with {@link #done()} in a finally. */
    public static void handling(PyException e) {
        HANDLING.get().push(e);
    }

    /** Leave the innermost {@code except} block. */
    public static void done() {
        HANDLING.get().pop();
    }

    /** The exception currently being handled, or null. */
    public static PyException current() {
        return HANDLING.get().peek();
    }

    /** Forget any handling state (start of a compile). */
    public static void reset() {
        HANDLING.get().clear();
    }

    public static PyException typeError(String msg) {
        return new PyException("TypeError", msg);
    }

    public static PyException valueError(String msg) {
        return new PyException("ValueError", msg);
    }
}
