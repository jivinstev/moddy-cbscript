// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.lex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * scriptlex.py, run the way PLY 3.11's lexer runs it.
 *
 * <p>The master regexes and the rule behind each group come from the exported tables, so the match order is PLY's:
 * function rules in definition order, then string rules by decreasing pattern length, first alternative wins.
 */
public final class Lexer {
    static final Set<String> KEYWORDS = Set.of(
            "for", "dir", "desc", "in", "end", "not", "and", "or", "to", "by", "import",
            "name", "with", "macros",
            "at", "as", "on", "facing", "rotated", "align", "here", "the_end", "the_nether", "overworld",
            "move", "create", "tell", "title", "subtitle", "actionbar",
            "reset", "clock", "function", "if", "unless", "then", "do", "else", "switch", "case", "default",
            "return", "while", "macro", "block", "block_data", "block_tag", "entity_tag", "item_tag", "define",
            "array", "remove", "success", "result",
            "shaped", "recipe", "keys", "eyes", "feet", "advancement", "loot_table", "predicate", "item_modifier",
            "push", "pop", "true", "false");

    /** One master regex and, per group number, the rule name ({@code t_ID}...) or null for an inner group. */
    public record Master(Pattern pattern, String[] groupRule) {
    }

    private final List<Master> masters;
    private final String ignore;
    private final Consumer<String> print;

    private String data = "";
    private int lexpos;
    public int lineno = 1;

    public Lexer(List<Master> masters, String ignore, Consumer<String> print) {
        this.masters = masters;
        this.ignore = ignore;
        this.print = print;
    }

    public void input(String text) {
        data = text;
        lexpos = 0;
    }

    public int lexpos() {
        return lexpos;
    }

    /** The next token, or null at the end (PLY's {@code token()}). */
    public Token token() {
        int pos = lexpos;
        int len = data.length();
        outer:
        while (pos < len) {
            if (ignore.indexOf(data.charAt(pos)) >= 0) {
                pos++;
                continue;
            }
            for (Master master : masters) {
                Matcher m = master.pattern().matcher(data);
                m.useAnchoringBounds(false);
                m.useTransparentBounds(true);
                m.region(pos, len);
                if (!m.lookingAt()) {
                    continue;
                }
                String rule = null;
                for (int g = 1; g < master.groupRule().length; g++) {
                    if (master.groupRule()[g] != null && m.start(g) >= 0) {
                        rule = master.groupRule()[g];
                        break;
                    }
                }
                Token tok = new Token(null, m.group(), lineno, pos);
                pos = m.end();
                lexpos = pos;
                if (apply(rule, tok)) {
                    return tok;
                }
                pos = lexpos;
                continue outer;
            }
            // t_error
            int cp = data.codePointAt(pos);
            print.accept("Illegal character \"" + new String(Character.toChars(cp)) + "\" was skipped at line " + lineno);
            pos += Character.charCount(cp);
            lexpos = pos;
        }
        lexpos = pos + 1;
        return null;
    }

    /** Run rule {@code rule} on {@code tok}; false if the rule discards the token. */
    private boolean apply(String rule, Token tok) {
        String v = (String) tok.value;
        switch (rule) {
            case "t_None", "t_False" -> {
                tok.value = "0";
                tok.type = "DECIMAL";
            }
            case "t_True" -> {
                tok.value = "1";
                tok.type = "DECIMAL";
            }
            case "t_FUNCTIONID" -> {
                tok.type = "FUNCTIONID";
                tok.value = v.substring(0, v.length() - 1);
            }
            case "t_ID" -> tok.type = KEYWORDS.contains(v) ? v : "ID";
            case "t_COMMAND" -> {
                lineno += count(v, '\n');
                tok.type = "COMMAND";
                tok.value = PyStr.strip(v);
            }
            case "t_WHITESPACE" -> {
                return false;
            }
            case "t_ATID" -> {
                tok.type = "ATID";
                tok.value = v.substring(1);
            }
            case "t_TILDEEMPTY" -> {
                tok.type = "TILDEEMPTY";
                tok.value = "~";
            }
            case "t_HEX" -> {
                tok.type = "HEX";
                tok.value = new BigInteger(v.substring(2), 16).toString();
            }
            case "t_BINARY" -> {
                tok.type = "BINARY";
                tok.value = new BigInteger(v.substring(2), 2).toString();
            }
            case "t_NEWLINE" -> {
                lineno++;
                tok.type = "NEWLINE";
            }
            default -> tok.type = rule.substring(2);
        }
        return true;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Build the master regexes from the exported table lines ({@code R} pattern, {@code N} group rules). */
    public static List<Master> masters(List<String[]> rLines, List<String[]> nLines) {
        List<Master> out = new ArrayList<>();
        for (int i = 0; i < rLines.size(); i++) {
            Pattern p = PyRe.compile(rLines.get(i)[1]);
            String[] names = nLines.get(i);
            String[] rules = new String[names.length];
            for (int g = 1; g < names.length; g++) {
                rules[g - 1] = names[g].equals("-") ? null : names[g];
            }
            out.add(new Master(p, rules));
        }
        return out;
    }
}
