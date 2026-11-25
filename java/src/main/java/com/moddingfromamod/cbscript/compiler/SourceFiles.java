// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

/**
 * The files a script can see beside it: what {@code import name} (name.cblib), {@code import name.py} and a block
 * switch (blocks.json) read. The Python compiler reads them from the script's directory.
 */
public interface SourceFiles {
    /** The text of file {@code name} (newlines as Python's text mode reads them), or null if there is none. */
    String read(String name);

    /** The absolute path Python's os.path.abspath would give {@code name}, as messages show it. */
    String absolute(String name);
}
