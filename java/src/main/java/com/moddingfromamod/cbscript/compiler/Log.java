// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.moddingfromamod.cbscript.lex.PyStr;
import com.moddingfromamod.cbscript.py.PyException;

/** What the Python compiler prints to stdout during a compile, which compile_once.py returns as its log. */
public final class Log {
    private final StringBuilder out = new StringBuilder();

    /** Python {@code print(text)}. */
    public void print(String text) {
        out.append(text).append('\n');
    }

    /** The non-blank lines printed, as compile_once.py collects them. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (String ln : out.toString().split("\n", -1)) {
            if (!PyStr.strip(ln).isEmpty()) {
                lines.add(ln);
            }
        }
        return lines;
    }

    /**
     * What {@code traceback.format_exc()} gives while {@code e} is being handled: the context chain, oldest first.
     * Frames are not reproduced (they name Python source lines); the corpus compares a traceback by its last line.
     */
    public static String traceback(PyException e) {
        Deque<PyException> chain = new ArrayDeque<>();
        for (PyException x = e; x != null && !chain.contains(x); x = x.context) {
            chain.push(x);
        }
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (PyException x : chain) {
            if (!first) {
                b.append("\nDuring handling of the above exception, another exception occurred:\n\n");
            }
            first = false;
            b.append("Traceback (most recent call last):\n  File \"<cbscript>\", in compile\n");
            String msg = x.str();
            b.append(x.tracebackName()).append(msg.isEmpty() ? "" : ": " + msg)
                    .append(x.tracebackNote == null ? "" : x.tracebackNote).append('\n');
        }
        return b.toString();
    }
}
