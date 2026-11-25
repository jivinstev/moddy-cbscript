// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import com.moddingfromamod.cbscript.py.Ast.Arg;
import com.moddingfromamod.cbscript.py.Ast.ArgKind;
import com.moddingfromamod.cbscript.py.Ast.CompFor;
import com.moddingfromamod.cbscript.py.Ast.CompKind;
import com.moddingfromamod.cbscript.py.Ast.DisplayKind;
import com.moddingfromamod.cbscript.py.Ast.Node;
import com.moddingfromamod.cbscript.py.Ast.Param;
import com.moddingfromamod.cbscript.py.Ast.ParamKind;
import com.moddingfromamod.cbscript.py.PyLexer.FExpr;
import com.moddingfromamod.cbscript.py.PyLexer.StrLit;
import com.moddingfromamod.cbscript.py.PyLexer.T;
import com.moddingfromamod.cbscript.py.PyLexer.Tok;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recursive-descent parser for CPython 3.13's expression grammar ({@code eval} input). */
final class PyParser {
    /** Python's {@code Ellipsis}. */
    static final PyObject ELLIPSIS = new PyObject() {
        @Override
        public String typeName() {
            return "ellipsis";
        }

        @Override
        public String repr() {
            return "Ellipsis";
        }

        @Override
        public long hash() {
            return Py.identityHash(this);
        }
    };

    private final List<Tok> toks;
    private int p;
    /** Equal constants share one object, as in a CPython code object (so {@code is} agrees). */
    private final Map<String, PyObject> constants;

    private PyParser(List<Tok> toks, Map<String, PyObject> constants) {
        this.toks = toks;
        this.constants = constants;
    }

    /** Parses an eval input. */
    static Node parse(String source) {
        List<Tok> toks = PyLexer.tokenize(source);
        PyParser ps = new PyParser(toks, new HashMap<>());
        if (toks.get(0).type() == T.END) {
            throw PyLexer.syntaxError("invalid syntax", 0);
        }
        Node n = ps.starExpressions();
        while (ps.peek().type() == T.NEWLINE) {
            ps.p++;
        }
        if (ps.peek().type() != T.END) {
            throw ps.error();
        }
        return n;
    }

    // ---- token helpers ----

    private Tok peek() {
        return toks.get(p);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(p + k, toks.size() - 1));
    }

    private boolean isOp(String s) {
        Tok t = peek();
        return t.type() == T.OP && t.text().equals(s);
    }

    private boolean isKw(String s) {
        Tok t = peek();
        return t.type() == T.NAME && t.text().equals(s);
    }

    private boolean acceptOp(String s) {
        if (isOp(s)) {
            p++;
            return true;
        }
        return false;
    }

    private boolean acceptKw(String s) {
        if (isKw(s)) {
            p++;
            return true;
        }
        return false;
    }

    private void expectOp(String s) {
        if (!acceptOp(s)) {
            throw error();
        }
    }

    private PyException error() {
        Tok t = peek();
        if (t.type() == T.NAME && (t.text().equals("yield") || t.text().equals("await"))) {
            if (t.text().equals("await")) {
                return PyLexer.syntaxError("'await' outside function", t.line());
            }
        }
        return PyLexer.syntaxError("invalid syntax", t.line());
    }

    private boolean atExpressionStart() {
        Tok t = peek();
        return switch (t.type()) {
            case NAME -> !PyLexer.KEYWORDS.contains(t.text()) || t.text().equals("not")
                    || t.text().equals("lambda") || t.text().equals("None") || t.text().equals("True")
                    || t.text().equals("False") || t.text().equals("await");
            case NUMBER, STRING -> true;
            case OP -> switch (t.text()) {
                case "(", "[", "{", "-", "+", "~", "...", "*" -> true;
                default -> false;
            };
            default -> false;
        };
    }

    // ---- expressions ----

    /** expressions (eval input): a tuple without parentheses when there is a comma; no starred items. */
    private Node starExpressions() {
        Node first = expression();
        if (!isOp(",")) {
            return first;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (!atExpressionStart() || isOp("*")) {
                break;
            }
            elts.add(expression());
        }
        return foldTuple(elts);
    }

    private Node namedExpression() {
        if (peek().type() == T.NAME && peek(1).type() == T.OP && peek(1).text().equals(":=")) {
            String name = peek().text();
            if (PyLexer.KEYWORDS.contains(name)) {
                throw PyLexer.syntaxError("cannot use assignment expressions with " + name, peek().line());
            }
            p += 2;
            return new Ast.NamedExpr(name, expression());
        }
        return expression();
    }

    private Node starNamedExpression() {
        if (acceptOp("*")) {
            return new Ast.Starred(bitor());
        }
        return namedExpression();
    }

    Node expression() {
        if (isKw("lambda")) {
            return lambdef();
        }
        Node body = disjunction();
        if (isKw("if")) {
            int line = peek().line();
            p++;
            Node test = disjunction();
            if (!acceptKw("else")) {
                throw PyLexer.syntaxError("expected 'else' after 'if' expression", line);
            }
            Node orelse = expression();
            return new Ast.IfExp(test, body, orelse);
        }
        return body;
    }

    private Node lambdef() {
        p++; // lambda
        List<Param> params = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean kwOnly = false;
        boolean sawDefault = false;
        while (!isOp(":")) {
            if (acceptOp("/")) {
                // positional-only marker: same evaluation semantics here
            } else if (acceptOp("**")) {
                String n = paramName(seen);
                params.add(new Param(n, null, ParamKind.VARKW));
            } else if (acceptOp("*")) {
                if (isOp(",") || isOp(":")) {
                    if (isOp(":") || (isOp(",") && peek(1).type() == T.OP && peek(1).text().equals(":"))) {
                        throw PyLexer.syntaxError("named arguments must follow bare *", peek().line());
                    }
                    kwOnly = true;
                } else {
                    String n = paramName(seen);
                    params.add(new Param(n, null, ParamKind.VARARGS));
                    kwOnly = true;
                }
            } else {
                String n = paramName(seen);
                Node dflt = null;
                if (acceptOp("=")) {
                    dflt = expression();
                    sawDefault = true;
                } else if (sawDefault && !kwOnly) {
                    throw PyLexer.syntaxError("parameter without a default follows parameter with a default",
                            peek().line());
                }
                params.add(new Param(n, dflt, kwOnly ? ParamKind.KWONLY : ParamKind.NORMAL));
            }
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp(":");
        Node body = expression();
        return new Ast.Lambda(params, body);
    }

    private String paramName(Set<String> seen) {
        Tok t = peek();
        if (t.type() != T.NAME || PyLexer.KEYWORDS.contains(t.text())) {
            throw error();
        }
        p++;
        if (!seen.add(t.text())) {
            throw PyLexer.syntaxError("duplicate argument '" + t.text() + "' in function definition", t.line());
        }
        return t.text();
    }

    private Node disjunction() {
        Node first = conjunction();
        if (!isKw("or")) {
            return first;
        }
        List<Node> vals = new ArrayList<>();
        vals.add(first);
        while (acceptKw("or")) {
            vals.add(conjunction());
        }
        return new Ast.BoolOp(false, vals);
    }

    private Node conjunction() {
        Node first = inversion();
        if (!isKw("and")) {
            return first;
        }
        List<Node> vals = new ArrayList<>();
        vals.add(first);
        while (acceptKw("and")) {
            vals.add(inversion());
        }
        return new Ast.BoolOp(true, vals);
    }

    private Node inversion() {
        if (acceptKw("not")) {
            return foldUnary("not", inversion());
        }
        return comparison();
    }

    private String compOp() {
        Tok t = peek();
        if (t.type() == T.OP) {
            switch (t.text()) {
                case "<", ">", "==", ">=", "<=", "!=" -> {
                    p++;
                    return t.text();
                }
                default -> {
                    return null;
                }
            }
        }
        if (t.type() == T.NAME) {
            if (t.text().equals("in")) {
                p++;
                return "in";
            }
            if (t.text().equals("not") && peek(1).type() == T.NAME && peek(1).text().equals("in")) {
                p += 2;
                return "not in";
            }
            if (t.text().equals("is")) {
                p++;
                if (acceptKw("not")) {
                    return "is not";
                }
                return "is";
            }
        }
        return null;
    }

    private Node comparison() {
        Node left = bitor();
        List<String> ops = new ArrayList<>();
        List<Node> rights = new ArrayList<>();
        String op;
        while ((op = compOp()) != null) {
            ops.add(op);
            rights.add(bitor());
        }
        return ops.isEmpty() ? left : new Ast.Compare(left, ops, rights);
    }

    private Node bitor() {
        Node n = bitxor();
        while (acceptOp("|")) {
            n = foldBinOp("|", n, bitxor());
        }
        return n;
    }

    private Node bitxor() {
        Node n = bitand();
        while (acceptOp("^")) {
            n = foldBinOp("^", n, bitand());
        }
        return n;
    }

    private Node bitand() {
        Node n = shift();
        while (acceptOp("&")) {
            n = foldBinOp("&", n, shift());
        }
        return n;
    }

    private Node shift() {
        Node n = sum();
        while (isOp("<<") || isOp(">>")) {
            String op = peek().text();
            p++;
            n = foldBinOp(op, n, sum());
        }
        return n;
    }

    private Node sum() {
        Node n = term();
        while (isOp("+") || isOp("-")) {
            String op = peek().text();
            p++;
            n = foldBinOp(op, n, term());
        }
        return n;
    }

    private Node term() {
        Node n = factor();
        while (isOp("*") || isOp("/") || isOp("//") || isOp("%") || isOp("@")) {
            String op = peek().text();
            p++;
            n = foldBinOp(op, n, factor());
        }
        return n;
    }

    private Node factor() {
        if (isOp("-") || isOp("+") || isOp("~")) {
            String op = peek().text();
            p++;
            return foldUnary(op, factor());
        }
        return power();
    }

    private Node power() {
        if (isKw("await")) {
            throw PyLexer.syntaxError("'await' outside function", peek().line());
        }
        Node base = primary();
        if (acceptOp("**")) {
            return foldBinOp("**", base, factor());
        }
        return base;
    }

    private Node primary() {
        Node n = atom();
        while (true) {
            if (acceptOp(".")) {
                Tok t = peek();
                if (t.type() != T.NAME || PyLexer.KEYWORDS.contains(t.text())) {
                    throw error();
                }
                p++;
                n = new Ast.Attr(n, t.text());
            } else if (isOp("(")) {
                p++;
                n = new Ast.Call(n, callArgs());
            } else if (isOp("[")) {
                p++;
                n = new Ast.Subscript(n, slices());
                expectOp("]");
            } else {
                return n;
            }
        }
    }

    private Node slices() {
        if (isOp("]")) {
            throw error();
        }
        Node first = sliceItem();
        if (!isOp(",")) {
            return first;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (isOp("]")) {
                break;
            }
            elts.add(sliceItem());
        }
        return new Ast.Display(DisplayKind.TUPLE, elts);
    }

    private Node sliceItem() {
        Node lower = null;
        if (!isOp(":")) {
            Node e = starNamedExpression();
            if (!isOp(":")) {
                return e;
            }
            lower = e;
        }
        expectOp(":");
        Node upper = null;
        Node step = null;
        if (!isOp(":") && !isOp("]") && !isOp(",")) {
            upper = expression();
        }
        if (acceptOp(":")) {
            if (!isOp("]") && !isOp(",")) {
                step = expression();
            }
        }
        return new Ast.Slice(lower, upper, step);
    }

    private List<Arg> callArgs() {
        List<Arg> args = new ArrayList<>();
        boolean sawKw = false;
        boolean sawDstar = false;
        Set<String> kwNames = new HashSet<>();
        while (!isOp(")")) {
            int line = peek().line();
            if (acceptOp("**")) {
                args.add(new Arg(ArgKind.DSTAR, null, expression()));
                sawDstar = true;
            } else if (acceptOp("*")) {
                if (sawDstar) {
                    throw PyLexer.syntaxError("iterable argument unpacking follows keyword argument unpacking",
                            line);
                }
                args.add(new Arg(ArgKind.STAR, null, expression()));
            } else if (peek().type() == T.NAME && peek(1).type() == T.OP && peek(1).text().equals("=")
                    && !PyLexer.KEYWORDS.contains(peek().text())) {
                String name = peek().text();
                p += 2;
                if (!kwNames.add(name)) {
                    throw PyLexer.syntaxError("keyword argument repeated: " + name, line);
                }
                args.add(new Arg(ArgKind.KW, name, expression()));
                sawKw = true;
            } else {
                Node e = namedExpression();
                if (isKw("for") || isKw("async")) {
                    Node gen = new Ast.Comp(CompKind.GEN, e, null, compFors());
                    if (!args.isEmpty() || !isOp(")")) {
                        if (acceptOp(",") && isOp(")") && args.isEmpty()) {
                            args.add(new Arg(ArgKind.POS, null, gen));
                            break;
                        }
                        throw PyLexer.syntaxError("Generator expression must be parenthesized", line);
                    }
                    args.add(new Arg(ArgKind.POS, null, gen));
                    break;
                }
                if (sawDstar) {
                    throw PyLexer.syntaxError("positional argument follows keyword argument unpacking", line);
                }
                if (sawKw) {
                    throw PyLexer.syntaxError("positional argument follows keyword argument", line);
                }
                args.add(new Arg(ArgKind.POS, null, e));
            }
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp(")");
        return args;
    }

    private List<CompFor> compFors() {
        List<CompFor> gens = new ArrayList<>();
        while (isKw("for") || isKw("async")) {
            if (acceptKw("async")) {
                throw PyLexer.syntaxError("asynchronous comprehension outside of an asynchronous function",
                        peek().line());
            }
            p++; // for
            Node target = targets();
            if (!acceptKw("in")) {
                throw error();
            }
            Node iter = disjunction();
            List<Node> ifs = new ArrayList<>();
            while (acceptKw("if")) {
                ifs.add(disjunction());
            }
            gens.add(new CompFor(target, iter, ifs));
        }
        return gens;
    }

    /** star_targets of a comprehension: names, (nested) tuples/lists of them, one starred. */
    private Node targets() {
        Node first = target();
        if (!isOp(",")) {
            return first;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (isKw("in")) {
                break;
            }
            elts.add(target());
        }
        return new Ast.Display(DisplayKind.TUPLE, elts);
    }

    private Node target() {
        if (acceptOp("*")) {
            return new Ast.Starred(target());
        }
        if (isOp("(") || isOp("[")) {
            boolean paren = isOp("(");
            p++;
            String close = paren ? ")" : "]";
            List<Node> elts = new ArrayList<>();
            boolean comma = false;
            while (!isOp(close)) {
                elts.add(target());
                if (!acceptOp(",")) {
                    break;
                }
                comma = true;
            }
            expectOp(close);
            if (paren && elts.size() == 1 && !comma) {
                return elts.get(0);
            }
            return new Ast.Display(paren ? DisplayKind.TUPLE : DisplayKind.LIST, elts);
        }
        Tok t = peek();
        if (t.type() != T.NAME || PyLexer.KEYWORDS.contains(t.text())) {
            if (t.type() == T.NAME && (t.text().equals("True") || t.text().equals("False")
                    || t.text().equals("None"))) {
                throw PyLexer.syntaxError("cannot assign to " + t.text(), t.line());
            }
            throw error();
        }
        p++;
        // attribute and subscript targets would assign into objects: not supported in this evaluator
        if (isOp(".") || isOp("[")) {
            throw PyLexer.syntaxError("only names can be assigned in comprehensions here", t.line());
        }
        return new Ast.Name(t.text());
    }

    // ---- constant folding, as CPython's AST optimizer does it (Python/ast_opt.c) ----

    private static final int MAX_INT_SIZE = 128;
    private static final int MAX_COLLECTION_SIZE = 256;
    private static final int MAX_STR_SIZE = 4096;

    /** A tuple display, folded into a constant when every item is a constant. */
    private Node foldTuple(List<Node> elts) {
        List<PyObject> vals = new ArrayList<>();
        for (Node e : elts) {
            if (!(e instanceof Ast.Const c)) {
                return new Ast.Display(DisplayKind.TUPLE, elts);
            }
            vals.add(c.value());
        }
        return constant(PyTuple.of(vals));
    }

    private Node foldUnary(String op, Node operand) {
        if (operand instanceof Ast.Const c) {
            try {
                PyObject v = c.value();
                return constant(switch (op) {
                    case "-" -> Py.neg(v);
                    case "+" -> Py.pos(v);
                    case "~" -> Py.invert(v);
                    default -> Py.of(!v.truthy());
                });
            } catch (PyException e) {
                // left for run time, which raises the same error
            }
        }
        return new Ast.Unary(op, operand);
    }

    private static long bits(PyObject o) {
        return ((PyInt) o).value().abs().bitLength();
    }

    private Node foldBinOp(String op, Node left, Node right) {
        if (left instanceof Ast.Const lc && right instanceof Ast.Const rc && !op.equals("@")) {
            PyObject v = lc.value();
            PyObject w = rc.value();
            if (safeToFold(op, v, w)) {
                try {
                    return constant(Py.binop(op, v, w));
                } catch (PyException e) {
                    // not folded: run time raises it
                }
            }
        }
        return new Ast.BinOp(op, left, right);
    }

    private static boolean safeToFold(String op, PyObject v, PyObject w) {
        switch (op) {
            case "*": {
                if (v instanceof PyInt && w instanceof PyInt) {
                    return ((PyInt) v).signum() == 0 || ((PyInt) w).signum() == 0
                            || bits(v) + bits(w) <= MAX_INT_SIZE;
                }
                PyObject n = v instanceof PyInt ? v : w;
                PyObject seq = v instanceof PyInt ? w : v;
                if (n instanceof PyInt ni && (seq instanceof PyTuple || seq instanceof PyStr)) {
                    int size = seq.len();
                    if (size == 0) {
                        return true;
                    }
                    long count = ni.isLong() ? ni.longValue() : -1;
                    int max = seq instanceof PyTuple ? MAX_COLLECTION_SIZE : MAX_STR_SIZE;
                    return count >= 0 && count <= max / size;
                }
                return true;
            }
            case "**":
                if (v instanceof PyInt vi && w instanceof PyInt wi && vi.signum() != 0 && wi.signum() > 0) {
                    return wi.isLong() && bits(v) <= MAX_INT_SIZE / wi.longValue();
                }
                return true;
            case "<<":
                if (v instanceof PyInt vi && w instanceof PyInt wi && vi.signum() != 0 && wi.signum() != 0) {
                    return wi.isLong() && wi.longValue() >= 0 && wi.longValue() <= MAX_INT_SIZE
                            && bits(v) <= MAX_INT_SIZE - wi.longValue();
                }
                return true;
            case "%":
                return !(v instanceof PyStr);
            default:
                return true;
        }
    }

    private Node constant(PyObject v) {
        String key = v.typeName() + ":" + (v instanceof PyFloat f ? Long.toString(
                Double.doubleToRawLongBits(f.value())) : v.repr());
        PyObject shared = constants.computeIfAbsent(key, k -> v);
        return new Ast.Const(shared);
    }

    private Node atom() {
        Tok t = peek();
        switch (t.type()) {
            case NUMBER:
                p++;
                return constant((PyObject) t.value());
            case STRING:
                return strings();
            case NAME: {
                String s = t.text();
                switch (s) {
                    case "None":
                        p++;
                        return new Ast.Const(Py.NONE);
                    case "True":
                        p++;
                        return new Ast.Const(Py.TRUE);
                    case "False":
                        p++;
                        return new Ast.Const(Py.FALSE);
                    default:
                        break;
                }
                if (PyLexer.KEYWORDS.contains(s)) {
                    throw error();
                }
                p++;
                return new Ast.Name(s);
            }
            case OP:
                switch (t.text()) {
                    case "(":
                        p++;
                        return parenthesized();
                    case "[":
                        p++;
                        return listDisplay();
                    case "{":
                        p++;
                        return braceDisplay();
                    case "...":
                        p++;
                        return new Ast.Const(ELLIPSIS);
                    default:
                        throw error();
                }
            default:
                throw error();
        }
    }

    private Node parenthesized() {
        if (acceptOp(")")) {
            return new Ast.Const(PyTuple.EMPTY);
        }
        if (isKw("yield")) {
            throw PyLexer.syntaxError("'yield' outside function", peek().line());
        }
        Node first = starNamedExpression();
        if (isKw("for") || isKw("async")) {
            Node gen = new Ast.Comp(CompKind.GEN, first, null, compFors());
            expectOp(")");
            return gen;
        }
        if (acceptOp(")")) {
            if (first instanceof Ast.Starred) {
                throw PyLexer.syntaxError("cannot use starred expression here", peek().line());
            }
            return first;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (isOp(")")) {
                break;
            }
            elts.add(starNamedExpression());
        }
        expectOp(")");
        return foldTuple(elts);
    }

    private Node listDisplay() {
        if (acceptOp("]")) {
            return new Ast.Display(DisplayKind.LIST, new ArrayList<>());
        }
        Node first = starNamedExpression();
        if (isKw("for") || isKw("async")) {
            Node comp = new Ast.Comp(CompKind.LIST, first, null, compFors());
            expectOp("]");
            return comp;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (isOp("]")) {
                break;
            }
            elts.add(starNamedExpression());
        }
        if (isKw("for")) {
            throw PyLexer.syntaxError("did you forget parentheses around the comprehension target?", peek().line());
        }
        expectOp("]");
        return new Ast.Display(DisplayKind.LIST, elts);
    }

    private Node braceDisplay() {
        if (acceptOp("}")) {
            return new Ast.DictDisplay(new ArrayList<>(), new ArrayList<>());
        }
        if (isOp("**") || isDictStart()) {
            return dictDisplay();
        }
        Node first = starNamedExpression();
        if (isKw("for") || isKw("async")) {
            Node comp = new Ast.Comp(CompKind.SET, first, null, compFors());
            expectOp("}");
            return comp;
        }
        List<Node> elts = new ArrayList<>();
        elts.add(first);
        while (acceptOp(",")) {
            if (isOp("}")) {
                break;
            }
            elts.add(starNamedExpression());
        }
        if (isKw("for")) {
            throw PyLexer.syntaxError("did you forget parentheses around the comprehension target?", peek().line());
        }
        expectOp("}");
        return new Ast.Display(DisplayKind.SET, elts);
    }

    /** Looks ahead for "expression ':'" at bracket depth 0 before ',' or '}' or 'for'. */
    private boolean isDictStart() {
        int depth = 0;
        for (int i = p; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.type() == T.END) {
                return false;
            }
            if (t.type() != T.OP) {
                if (depth == 0 && t.type() == T.NAME && (t.text().equals("for") || t.text().equals("lambda"))) {
                    return false;
                }
                continue;
            }
            switch (t.text()) {
                case "(", "[", "{" -> depth++;
                case ")", "]", "}" -> {
                    if (depth == 0) {
                        return false;
                    }
                    depth--;
                }
                case ":" -> {
                    if (depth == 0) {
                        return true;
                    }
                }
                case "," -> {
                    if (depth == 0) {
                        return false;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    private Node dictDisplay() {
        List<Node> keys = new ArrayList<>();
        List<Node> values = new ArrayList<>();
        boolean first = true;
        while (!isOp("}")) {
            if (acceptOp("**")) {
                keys.add(null);
                values.add(bitor());
            } else {
                Node k = expression();
                if (!acceptOp(":")) {
                    throw PyLexer.syntaxError("':' expected after dictionary key", peek().line());
                }
                Node v = expression();
                if (first && (isKw("for") || isKw("async"))) {
                    Node comp = new Ast.Comp(CompKind.DICT, k, v, compFors());
                    expectOp("}");
                    return comp;
                }
                keys.add(k);
                values.add(v);
            }
            first = false;
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp("}");
        return new Ast.DictDisplay(keys, values);
    }

    // ---- strings ----

    private Node strings() {
        List<Object> parts = new ArrayList<>();
        boolean anyBytes = false;
        boolean anyStr = false;
        boolean anyF = false;
        int line = peek().line();
        while (peek().type() == T.STRING) {
            StrLit lit = (StrLit) peek().value();
            p++;
            anyBytes |= lit.bytes();
            anyStr |= !lit.bytes();
            anyF |= lit.fstring();
            parts.addAll(lit.parts());
        }
        if (anyBytes && anyStr) {
            throw PyLexer.syntaxError("cannot mix bytes and nonbytes literals", line);
        }
        if (anyBytes) {
            throw PyLexer.syntaxError("bytes literals are not supported by this evaluator", line);
        }
        if (!anyF) {
            StringBuilder sb = new StringBuilder();
            for (Object o : parts) {
                sb.append((String) o);
            }
            return constant(PyStr.of(sb.toString()));
        }
        return joined(parts);
    }

    private Node joined(List<Object> parts) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (Object o : parts) {
            if (o instanceof String s) {
                pending.append(s);
                continue;
            }
            FExpr fe = (FExpr) o;
            if (fe.debugText() != null) {
                pending.append(fe.debugText());
            }
            if (pending.length() > 0) {
                nodes.add(new Ast.Const(PyStr.of(pending.toString())));
                pending.setLength(0);
            }
            Node value = parseSub(fe.source(), fe.line());
            Node spec = fe.spec() == null ? null : joined(fe.spec());
            char conv = fe.conversion();
            if (fe.debugText() != null && conv == 0 && fe.spec() == null) {
                conv = 'r';
            }
            nodes.add(new Ast.FormattedValue(value, conv, spec, fe.debugText()));
        }
        if (pending.length() > 0) {
            nodes.add(new Ast.Const(PyStr.of(pending.toString())));
        }
        return new Ast.JoinedStr(nodes);
    }

    /** Parses the expression of an f-string replacement field (as if parenthesized). */
    private Node parseSub(String source, int line) {
        if (source.strip().startsWith("lambda") && (source.strip().length() == 6
                || !Character.isJavaIdentifierPart(source.strip().charAt(6)))) {
            throw PyLexer.syntaxError("f-string: lambda expressions are not allowed without parentheses", line);
        }
        List<Tok> sub;
        try {
            sub = PyLexer.tokenize("(" + source + ")");
        } catch (PyException e) {
            throw PyLexer.syntaxError("f-string: " + e.pyStr.replaceAll(" \\(<string>, line \\d+\\)$", ""), line);
        }
        PyParser ps = new PyParser(sub, constants);
        Node n = ps.starExpressions();
        if (ps.peek().type() != T.END) {
            throw PyLexer.syntaxError("f-string: invalid syntax", line);
        }
        return n;
    }
}
