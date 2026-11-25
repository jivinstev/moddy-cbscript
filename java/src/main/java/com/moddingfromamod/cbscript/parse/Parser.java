// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.moddingfromamod.cbscript.lex.Lexer;
import com.moddingfromamod.cbscript.lex.Token;
import com.moddingfromamod.cbscript.py.PyException;

/**
 * PLY 3.11's {@code LRParser.parseopt} with {@code tracking=True}, line for line, over scriptparse.py's tables.
 *
 * <p>Kept literal on purpose, including its error recovery: an action that raises {@code SyntaxError} does not stop
 * the parse, it enters recovery (pop to state 0, discard input) and the parse may still return a result or None; and
 * the error message names the state the parser was in. Only {@link Actions} is CBScript's own.
 */
public final class Parser {
    private static final int ERROR_COUNT = 3;

    /** What a semantic action sees: PLY's YaccProduction over the current slice. */
    public static final class P {
        Token[] slice;
        public final Parser parser;

        P(Parser parser) {
            this.parser = parser;
        }

        public Object get(int n) {
            return slice[n].value;
        }

        public String s(int n) {
            return (String) slice[n].value;
        }

        public void set(Object v) {
            slice[0].value = v;
        }

        public int len() {
            return slice.length;
        }

        /** {@code p.lineno(n)}: 0 where the symbol has none. */
        public int lineno(int n) {
            Integer l = slice[n].lineno;
            return l == null ? 0 : l;
        }
    }

    private final Tables tables;
    private final Lexer lexer;
    private final Actions actions;
    private final Consumer<String> print;
    /** {@code bparser.state}: the state at the last action or error call. */
    public int state;
    /** {@code bparser.data}. */
    public String data;

    public Parser(Consumer<String> print, Actions.Factory factory) {
        this.tables = Tables.get();
        this.print = print;
        this.lexer = new Lexer(tables.masters, tables.ignore, print);
        this.actions = new Actions(this, factory, print);
    }

    public Lexer lexer() {
        return lexer;
    }

    /**
     * scriptparse.parse(data): the parse result, or null where PLY returns None. A syntax error raises a
     * PyException of type SyntaxError (after printing it); any other exception a CompileError, as Python does.
     */
    public Object parse(String text) {
        lexer.lineno = 1;
        data = text;
        try {
            return parseopt(text);
        } catch (PyException e) {
            if (e.isA("SyntaxError")) {
                print.accept(e.str());
                throw e;
            }
            PyException.handling(e);
            try {
                print.accept(com.moddingfromamod.cbscript.compiler.Log.traceback(e));
                throw new PyException("CompileError", "CBScript compiler error while parsing: " + e.repr());
            } finally {
                PyException.done();
            }
        }
    }

    private Object parseopt(String input) {
        Token lookahead = null;
        List<Token> lookaheadstack = new ArrayList<>();
        P pslice = new P(this);
        int errorcount = 0;
        boolean errorok = false;
        lexer.input(input);

        List<Integer> statestack = new ArrayList<>();
        List<Token> symstack = new ArrayList<>();
        statestack.add(0);
        symstack.add(new Token("$end", null, null, null));
        int st = 0;
        while (true) {
            Integer t;
            Integer def = st < tables.defaulted.length ? tables.defaulted[st] : null;
            if (def == null) {
                if (lookahead == null) {
                    if (lookaheadstack.isEmpty()) {
                        lookahead = lexer.token();
                    } else {
                        lookahead = lookaheadstack.remove(lookaheadstack.size() - 1);
                    }
                    if (lookahead == null) {
                        lookahead = new Token("$end", null, null, null);
                    }
                }
                t = tables.action.get(st).get(lookahead.type);
            } else {
                t = def;
            }

            if (t != null) {
                if (t > 0) {
                    statestack.add(t);
                    st = t;
                    symstack.add(lookahead);
                    lookahead = null;
                    if (errorcount > 0) {
                        errorcount--;
                    }
                    continue;
                }
                if (t < 0) {
                    Tables.Production p = tables.productions.get(-t);
                    String pname = p.name();
                    int plen = p.len();
                    Token sym = new Token(pname, null, null, null);
                    if (plen > 0) {
                        int base = symstack.size() - plen - 1;
                        Token[] targ = new Token[plen + 1];
                        for (int i = 0; i <= plen; i++) {
                            targ[i] = symstack.get(base + i);
                        }
                        targ[0] = sym;
                        Token t1 = targ[1];
                        sym.lineno = t1.lineno;
                        sym.lexpos = t1.lexpos;
                        pslice.slice = targ;
                        for (int i = 0; i < plen; i++) {
                            symstack.remove(symstack.size() - 1);
                        }
                        state = st;
                        try {
                            actions.reduce(p, pslice);
                        } catch (PyException e) {
                            if (!e.isA("SyntaxError")) {
                                throw e;
                            }
                            lookaheadstack.add(lookahead);
                            for (int i = 1; i < plen; i++) {
                                symstack.add(targ[i]);
                            }
                            statestack.remove(statestack.size() - 1);
                            st = statestack.get(statestack.size() - 1);
                            sym.type = "error";
                            sym.value = "error";
                            lookahead = sym;
                            errorcount = ERROR_COUNT;
                            errorok = false;
                            continue;
                        }
                        for (int i = 0; i < plen; i++) {
                            statestack.remove(statestack.size() - 1);
                        }
                        symstack.add(sym);
                        st = tables.gotoTable.get(statestack.get(statestack.size() - 1)).get(pname);
                        statestack.add(st);
                        continue;
                    } else {
                        sym.lineno = lexer.lineno;
                        sym.lexpos = lexer.lexpos();
                        Token[] targ = {sym};
                        pslice.slice = targ;
                        state = st;
                        try {
                            actions.reduce(p, pslice);
                        } catch (PyException e) {
                            if (!e.isA("SyntaxError")) {
                                throw e;
                            }
                            lookaheadstack.add(lookahead);
                            statestack.remove(statestack.size() - 1);
                            st = statestack.get(statestack.size() - 1);
                            sym.type = "error";
                            sym.value = "error";
                            lookahead = sym;
                            errorcount = ERROR_COUNT;
                            errorok = false;
                            continue;
                        }
                        symstack.add(sym);
                        st = tables.gotoTable.get(statestack.get(statestack.size() - 1)).get(pname);
                        statestack.add(st);
                        continue;
                    }
                }
                // t == 0: accept
                return symstack.get(symstack.size() - 1).value;
            }

            // t is None: a syntax error.
            if (errorcount == 0 || errorok) {
                errorcount = ERROR_COUNT;
                errorok = false;
                Token errtoken = lookahead;
                if (errtoken.type.equals("$end")) {
                    errtoken = null;
                }
                state = st;
                throw pError(errtoken);
            } else {
                errorcount = ERROR_COUNT;
            }

            if (statestack.size() <= 1 && !lookahead.type.equals("$end")) {
                lookahead = null;
                st = 0;
                lookaheadstack.clear();
                continue;
            }

            if (lookahead.type.equals("$end")) {
                return null;
            }

            if (!lookahead.type.equals("error")) {
                Token sym = symstack.get(symstack.size() - 1);
                if (sym.type.equals("error")) {
                    lookahead = null;
                    continue;
                }
                Token et = new Token("error", lookahead, lookahead.lineno, lookahead.lexpos);
                lookaheadstack.add(lookahead);
                lookahead = et;
            } else {
                Token sym = symstack.remove(symstack.size() - 1);
                lookahead.lineno = sym.lineno;
                lookahead.lexpos = sym.lexpos;
                statestack.remove(statestack.size() - 1);
                st = statestack.get(statestack.size() - 1);
            }
        }
    }

    /** scriptparse.p_error: always raises. */
    private PyException pError(Token p) {
        if (p == null) {
            return new PyException("SyntaxError", "Syntax error: unexpected End of File");
        }
        if (p.lexpos == null) {
            return new PyException("AttributeError", "'YaccSymbol' object has no attribute 'lexpos'");
        }
        String value = p.value instanceof String s ? s : String.valueOf(p.value);
        if (!(p.value instanceof String)) {
            return new PyException("AttributeError", "'" + "LexToken" + "' object has no attribute 'replace'");
        }
        return new PyException("SyntaxError", "Syntax error at line " + p.lineno + " column " + findColumn(data, p.lexpos)
                + ". Unexpected " + p.type + " symbol \"" + value.replace("\n", "\\n") + "\" in state " + state + ".");
    }

    /** scriptlex.find_column, counted in code points as Python counts. */
    static int findColumn(String input, int lexpos) {
        int lineStart = input.lastIndexOf('\n', lexpos - 1) + 1;
        if (lexpos == 0) {
            lineStart = 0;
        }
        return input.codePointCount(lineStart, lexpos) + 1;
    }
}
