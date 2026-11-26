// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.lex.Lexer;

/** PLY's parse tables and lexer regexes for scriptparse.py, as exported by tools/export_parser_tables.py. */
public final class Tables {
    public record Production(int index, String name, int len, String func, String text) {
    }

    public final List<Production> productions = new ArrayList<>();
    public final List<Map<String, Integer>> action = new ArrayList<>();
    public final List<Map<String, Integer>> gotoTable = new ArrayList<>();
    /** Per state, the default reduction, or null. */
    public Integer[] defaulted;
    public final List<Lexer.Master> masters;
    public final String ignore;

    private static Tables instance;

    public static synchronized Tables get() {
        if (instance == null) {
            instance = new Tables();
        }
        return instance;
    }

    private Tables() {
        this(resourceText());
    }

    /** The bundled parser_tables.txt, as text. */
    static String resourceText() {
        try (InputStream in = Tables.class.getResourceAsStream("/com/moddingfromamod/cbscript/parser_tables.txt")) {
            if (in == null) {
                throw new IllegalStateException("parser_tables.txt is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses the tables from their text. Lines end in LF, CRLF or CR: a Windows checkout with core.autocrlf=true writes
     * CRLF (the repository's .gitattributes asks for LF), and a field must never keep the CR. The table text itself has
     * no CR in it (the ignore string is a repr, {@code '\r'}).
     */
    Tables(String text) {
        List<String[]> rLines = new ArrayList<>();
        List<String[]> nLines = new ArrayList<>();
        String ign = "";
        int states = 0;
        List<String[]> rows = new ArrayList<>();
        for (String line : (Iterable<String>) text.lines()::iterator) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            rows.add(f);
            if (f[0].equals("A") || f[0].equals("G") || f[0].equals("D")) {
                states = Math.max(states, Integer.parseInt(f[1]) + 1);
            }
        }
        for (int i = 0; i < states; i++) {
            action.add(new HashMap<>());
            gotoTable.add(new HashMap<>());
        }
        defaulted = new Integer[states];
        for (String[] f : rows) {
            switch (f[0]) {
                case "P" -> productions.add(new Production(Integer.parseInt(f[1]), f[2], Integer.parseInt(f[3]),
                        f[4].equals("-") ? null : f[4], f[5]));
                case "A" -> action.get(Integer.parseInt(f[1])).put(f[2], Integer.parseInt(f[3]));
                case "G" -> gotoTable.get(Integer.parseInt(f[1])).put(f[2], Integer.parseInt(f[3]));
                case "D" -> defaulted[Integer.parseInt(f[1])] = Integer.parseInt(f[2]);
                case "R" -> rLines.add(f);
                case "N" -> nLines.add(f);
                case "I" -> ign = unrepr(f[1]);
                default -> throw new IllegalStateException("bad table line " + f[0]);
            }
        }
        masters = Lexer.masters(rLines, nLines);
        ignore = ign;
    }

    /** The Python repr of the ignore string, e.g. {@code '\r'}. */
    private static String unrepr(String r) {
        String body = r.substring(1, r.length() - 1);
        return body.replace("\\r", "\r").replace("\\t", "\t").replace("\\n", "\n");
    }
}
