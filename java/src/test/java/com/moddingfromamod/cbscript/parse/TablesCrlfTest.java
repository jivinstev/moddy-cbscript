// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.parse;

import java.util.Arrays;

import com.moddingfromamod.cbscript.CbScript;
import com.moddingfromamod.cbscript.CompileResult;

/**
 * The parser tables parse the same from a CRLF (or CR) copy as from the LF original: a Windows checkout with
 * core.autocrlf=true writes CRLF, and splitting on "\n" alone left a CR on the last field ("27\r":
 * NumberFormatException). Then a compile through the tables still works.
 */
public final class TablesCrlfTest {
    private TablesCrlfTest() {
    }

    public static void main(String[] args) {
        String lf = Tables.resourceText();
        check(lf.indexOf('\r') < 0, "the bundled tables are LF (.gitattributes)");
        Tables want = new Tables(lf);
        for (String[] eol : new String[][] {{"CRLF", "\r\n"}, {"CR", "\r"}}) {
            String copy = lf.replace("\n", eol[1]);
            Tables got;
            try {
                got = new Tables(copy);
            } catch (RuntimeException e) {
                throw new AssertionError(eol[0] + " copy of parser_tables.txt does not parse: " + e, e);
            }
            check(got.productions.equals(want.productions), eol[0] + ": productions");
            check(got.action.equals(want.action), eol[0] + ": action table");
            check(got.gotoTable.equals(want.gotoTable), eol[0] + ": goto table");
            check(Arrays.equals(got.defaulted, want.defaulted), eol[0] + ": default reductions");
            check(got.ignore.equals(want.ignore), eol[0] + ": ignore string");
            check(got.masters.size() == want.masters.size(), eol[0] + ": lexer masters");
            for (int i = 0; i < want.masters.size(); i++) {
                var g = got.masters.get(i);
                var w = want.masters.get(i);
                check(g.pattern().pattern().equals(w.pattern().pattern()) && g.pattern().flags() == w.pattern().flags()
                        && Arrays.equals(g.groupRule(), w.groupRule()), eol[0] + ": lexer master " + i);
            }
        }
        CompileResult r = CbScript.compile("t", "dir \".\"\nreset\n\t/say hi\nend\n", "1.21.8");
        check(r.ok(), "a compile still works: " + r.error());
        System.out.println("TablesCrlfTest: OK (CRLF and CR copies parse to the same tables)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("TablesCrlfTest failed: " + what);
        }
    }
}
