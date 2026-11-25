// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.lex;

/** A grammar symbol on the parser's stack, or a token from the lexer (PLY's LexToken / YaccSymbol). */
public final class Token {
    public String type;
    public Object value;
    /** Null where PLY's symbol has no lineno attribute. */
    public Integer lineno;
    public Integer lexpos;

    public Token(String type, Object value, Integer lineno, Integer lexpos) {
        this.type = type;
        this.value = value;
        this.lineno = lineno;
        this.lexpos = lexpos;
    }

    @Override
    public String toString() {
        return "LexToken(" + type + "," + value + "," + lineno + "," + lexpos + ")";
    }
}
