// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/**
 * Thrown where a compile notices its thread was interrupted (its time ran out). An {@link Error}, so no handler that
 * mirrors a Python {@code except} catches it: it unwinds the whole compile.
 */
public final class Cancelled extends Error {
    public Cancelled() {
        super("compile cancelled", null, false, false);
    }

    /** Stop here if this thread has been interrupted. Called where a compile can run for unbounded time. */
    public static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new Cancelled();
        }
    }
}
