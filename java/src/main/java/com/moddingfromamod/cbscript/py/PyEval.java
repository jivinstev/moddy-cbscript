// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import com.moddingfromamod.cbscript.py.Ast.Arg;
import com.moddingfromamod.cbscript.py.Ast.CompFor;
import com.moddingfromamod.cbscript.py.Ast.Node;
import com.moddingfromamod.cbscript.py.Ast.Param;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Evaluates one Python expression the way the Python compiler's {@code eval(expr, mcfunction_globals, dollar_ids)}
 * does: names are looked up in the locals map, then the globals ({@code math}, {@code json}), then the builtins;
 * comprehensions, generator expressions and lambdas get CPython 3.13's scoping (a generator expression or lambda
 * cannot see the locals map, an inlined list/set/dict comprehension can).
 *
 * <p>Python exceptions surface as {@link PyException} with CPython's type name and message; syntax errors as
 * {@code SyntaxError}. Names that reach outside the compiler ({@code __import__}, {@code open}, {@code eval}, ...)
 * and attributes starting with an underscore do not exist here.
 */
public final class PyEval {
    private static final Map<String, PyObject> GLOBALS = Map.of("math", PyMath.MODULE, "json", PyJson.MODULE);
    private static final int CACHE_SIZE = 2048;
    private static final Map<String, Node> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Node> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private final Map<String, PyObject> locals;

    private PyEval(Map<String, PyObject> locals) {
        this.locals = locals;
    }

    /**
     * Evaluates {@code source} as Python's {@code eval} would.
     *
     * @param source the expression text
     * @param locals the local variables (read; written only by an assignment expression {@code (x := ...)})
     * @param print receives exactly what {@code print(...)} writes, end string included (normally a trailing
     *     newline); may be null to discard
     */
    public static PyObject eval(String source, Map<String, PyObject> locals, Consumer<String> print) {
        Node node = parse(source);
        Consumer<String> saved = Builtins.PRINT.get();
        Builtins.PRINT.set(print);
        try {
            return new PyEval(locals == null ? new HashMap<>() : locals).eval(node, null);
        } catch (StackOverflowError e) {
            throw new PyException("RecursionError", "maximum recursion depth exceeded");
        } finally {
            Builtins.PRINT.set(saved);
        }
    }

    /** {@link #eval(String, Map, Consumer)} discarding print output. */
    public static PyObject eval(String source, Map<String, PyObject> locals) {
        return eval(source, locals, null);
    }

    /** Parses (with a cache) without evaluating; throws SyntaxError. */
    static Node parse(String source) {
        Node cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        if (source.indexOf('\0') >= 0) {
            throw new PyException("SyntaxError", "source code string cannot contain null bytes");
        }
        int i = 0;
        while (i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
            i++;
        }
        Node node;
        try {
            node = PyParser.parse(source.substring(i));
        } catch (StackOverflowError e) {
            throw new PyException("SyntaxError", "too many nested parentheses (<string>, line 1)");
        }
        CACHE.put(source, node);
        return node;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scopes
    // ------------------------------------------------------------------------------------------------------------

    /** A nested scope: comprehension variables, or a function (lambda / generator expression) frame. */
    private static final class Scope {
        final Map<String, PyObject> vars = new HashMap<>();
        final Scope parent;
        final boolean function;

        Scope(Scope parent, boolean function) {
            this.parent = parent;
            this.function = function;
        }
    }

    private PyObject lookup(String name, Scope scope) {
        boolean crossedFunction = false;
        for (Scope s = scope; s != null; s = s.parent) {
            PyObject v = s.vars.get(name);
            if (v != null) {
                return v;
            }
            crossedFunction |= s.function;
        }
        if (!crossedFunction) {
            PyObject v = locals.get(name);
            if (v != null) {
                return v;
            }
        }
        PyObject v = GLOBALS.get(name);
        if (v != null) {
            return v;
        }
        v = Builtins.BUILTINS.get(name);
        if (v != null) {
            return v;
        }
        throw new PyException("NameError", "name '" + name + "' is not defined");
    }

    private void bindNamed(String name, PyObject value, Scope scope) {
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.function) {
                s.vars.put(name, value);
                return;
            }
        }
        locals.put(name, value);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------------------------------------------------

    private PyObject eval(Node n, Scope scope) {
        if (n instanceof Ast.Const c) {
            return c.value();
        } else if (n instanceof Ast.Name nm) {
            return lookup(nm.id(), scope);
        } else if (n instanceof Ast.BinOp b) {
            PyObject l = eval(b.left(), scope);
            PyObject r = eval(b.right(), scope);
            return Py.binop(b.op(), l, r);
        } else if (n instanceof Ast.Attr a) {
            return eval(a.obj(), scope).getattr(a.name());
        } else if (n instanceof Ast.Call c) {
            return call(c, scope);
        } else if (n instanceof Ast.Subscript s) {
            PyObject obj = eval(s.obj(), scope);
            return obj.getitem(index(s.index(), scope));
        } else if (n instanceof Ast.Unary u) {
            PyObject v = eval(u.operand(), scope);
            return switch (u.op()) {
                case "-" -> Py.neg(v);
                case "+" -> Py.pos(v);
                case "~" -> Py.invert(v);
                default -> Py.of(!v.truthy());
            };
        } else if (n instanceof Ast.BoolOp b) {
            PyObject v = null;
            for (Node x : b.values()) {
                v = eval(x, scope);
                if (v.truthy() != b.isAnd()) {
                    return v;
                }
            }
            return v;
        } else if (n instanceof Ast.Compare c) {
            PyObject left = eval(c.left(), scope);
            PyObject result = Py.TRUE;
            for (int i = 0; i < c.ops().size(); i++) {
                PyObject right = eval(c.comparators().get(i), scope);
                result = Py.binop(c.ops().get(i), left, right);
                if (!result.truthy()) {
                    return result;
                }
                left = right;
            }
            return result;
        } else if (n instanceof Ast.IfExp e) {
            return eval(e.test(), scope).truthy() ? eval(e.body(), scope) : eval(e.orelse(), scope);
        } else if (n instanceof Ast.Display d) {
            List<PyObject> items = new ArrayList<>();
            for (Node x : d.elts()) {
                if (x instanceof Ast.Starred st) {
                    PyObject v = eval(st.value(), scope);
                    items.addAll(starItems(v, "Value after * must be an iterable, not " + v.typeName()));
                } else {
                    items.add(eval(x, scope));
                }
            }
            return switch (d.kind()) {
                case LIST -> new PyList(items);
                case TUPLE -> PyTuple.of(items);
                case SET -> {
                    if (items.size() > 2 && d.elts().stream().allMatch(x -> x instanceof Ast.Const)) {
                        // CPython compiles a display of more than two constants to BUILD_SET 0 plus SET_UPDATE
                        // of a constant frozenset, built from the items, then rebuilt from its own iteration
                        // order when the compiler merges constants (merge_consts_recursive); each step can
                        // change the order of colliding elements.
                        PySet first = PySet.fromIterable(PyTuple.of(items), true);
                        PySet frozen = PySet.fromIterable(PyTuple.of(first.elements()), true);
                        PySet s = new PySet();
                        s.update(frozen);
                        yield s;
                    }
                    PySet s = new PySet();
                    for (PyObject o : items) {
                        s.add(o);
                    }
                    yield s;
                }
            };
        } else if (n instanceof Ast.DictDisplay d) {
            PyDict dict = new PyDict();
            for (int i = 0; i < d.keys().size(); i++) {
                Node k = d.keys().get(i);
                if (k == null) {
                    PyObject m = eval(d.values().get(i), scope);
                    if (!(m instanceof PyDict md)) {
                        throw PyException.typeError("'" + m.typeName() + "' object is not a mapping");
                    }
                    for (Map.Entry<PyObject, PyObject> e : md.entries()) {
                        dict.put(e.getKey(), e.getValue());
                    }
                } else {
                    PyObject key = eval(k, scope);
                    PyObject value = eval(d.values().get(i), scope);
                    dict.put(key, value);
                }
            }
            return dict;
        } else if (n instanceof Ast.Comp c) {
            return comprehension(c, scope);
        } else if (n instanceof Ast.Lambda l) {
            return lambda(l, scope);
        } else if (n instanceof Ast.JoinedStr j) {
            StringBuilder sb = new StringBuilder();
            for (Node part : j.parts()) {
                sb.append(eval(part, scope).str());
            }
            return PyStr.of(sb.toString());
        } else if (n instanceof Ast.FormattedValue f) {
            PyObject v = Formatter.convert(eval(f.value(), scope), f.conversion());
            String spec = f.spec() == null ? "" : eval(f.spec(), scope).str();
            return PyStr.of(Formatter.format(v, spec));
        } else if (n instanceof Ast.NamedExpr ne) {
            PyObject v = eval(ne.value(), scope);
            bindNamed(ne.target(), v, scope);
            return v;
        } else if (n instanceof Ast.Starred) {
            throw new PyException("SyntaxError", "can't use starred expression here (<string>, line 1)");
        } else if (n instanceof Ast.Slice s) {
            return index(s, scope);
        }
        throw new IllegalStateException("unknown node " + n);
    }

    private PyObject index(Node n, Scope scope) {
        if (n instanceof Ast.Slice s) {
            return new PySlice(s.lower() == null ? Py.NONE : eval(s.lower(), scope),
                    s.upper() == null ? Py.NONE : eval(s.upper(), scope),
                    s.step() == null ? Py.NONE : eval(s.step(), scope));
        }
        if (n instanceof Ast.Display d && d.kind() == Ast.DisplayKind.TUPLE) {
            List<PyObject> items = new ArrayList<>();
            for (Node x : d.elts()) {
                if (x instanceof Ast.Starred st) {
                    PyObject v = eval(st.value(), scope);
                    items.addAll(starItems(v, "Value after * must be an iterable, not " + v.typeName()));
                } else {
                    items.add(index(x, scope));
                }
            }
            return PyTuple.of(items);
        }
        return eval(n, scope);
    }

    private static List<PyObject> starItems(PyObject v, String error) {
        try {
            v.iter();
        } catch (PyException e) {
            if (e.type.equals("TypeError") && e.pyStr.endsWith("is not iterable")) {
                throw PyException.typeError(error);
            }
            throw e;
        }
        return Py.toList(v);
    }

    /** _PyObject_FunctionStr: how CPython names a callable in call errors. */
    private static String functionStr(PyObject fn) {
        if (fn instanceof PyFunction f) {
            return f.name() + "()";
        }
        if (fn instanceof PyType t) {
            return t.name() + "()";
        }
        return fn.repr();
    }

    private PyObject call(Ast.Call c, Scope scope) {
        PyObject fn = eval(c.fn(), scope);
        List<PyObject> args = new ArrayList<>();
        Map<String, PyObject> kwargs = null;
        for (Arg a : c.args()) {
            switch (a.kind()) {
                case POS -> args.add(eval(a.value(), scope));
                case STAR -> {
                    PyObject v = eval(a.value(), scope);
                    args.addAll(starItems(v, functionStr(fn) + " argument after * must be an iterable, not "
                            + v.typeName()));
                }
                case KW -> {
                    if (kwargs == null) {
                        kwargs = new LinkedHashMap<>();
                    }
                    PyObject v = eval(a.value(), scope);
                    if (kwargs.put(a.name(), v) != null) {
                        throw PyException.typeError(functionStr(fn) + " got multiple values for keyword argument '"
                                + a.name() + "'");
                    }
                }
                case DSTAR -> {
                    PyObject m = eval(a.value(), scope);
                    if (!(m instanceof PyDict md)) {
                        throw PyException.typeError(functionStr(fn) + " argument after ** must be a mapping, not "
                                + m.typeName());
                    }
                    if (kwargs == null) {
                        kwargs = new LinkedHashMap<>();
                    }
                    for (Map.Entry<PyObject, PyObject> e : md.entries()) {
                        if (!(e.getKey() instanceof PyStr ks)) {
                            throw PyException.typeError("keywords must be strings");
                        }
                        if (kwargs.put(ks.value(), e.getValue()) != null) {
                            throw PyException.typeError(functionStr(fn) + " got multiple values for keyword "
                                    + "argument '" + ks.value() + "'");
                        }
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        return fn.call(args, kwargs == null ? Map.of() : kwargs);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Assignment targets
    // ------------------------------------------------------------------------------------------------------------

    private void assign(Node target, PyObject value, Scope scope) {
        if (target instanceof Ast.Name nm) {
            scope.vars.put(nm.id(), value);
            return;
        }
        if (!(target instanceof Ast.Display d)) {
            throw new PyException("SyntaxError", "cannot assign to expression (<string>, line 1)");
        }
        List<Node> elts = d.elts();
        int star = -1;
        for (int i = 0; i < elts.size(); i++) {
            if (elts.get(i) instanceof Ast.Starred) {
                if (star >= 0) {
                    throw new PyException("SyntaxError", "multiple starred expressions in assignment "
                            + "(<string>, line 1)");
                }
                star = i;
            }
        }
        Iterator<PyObject> it;
        try {
            it = value.iter();
        } catch (PyException e) {
            if (e.type.equals("TypeError")) {
                throw PyException.typeError("cannot unpack non-iterable " + value.typeName() + " object");
            }
            throw e;
        }
        List<PyObject> vals = new ArrayList<>();
        if (star < 0) {
            while (it.hasNext()) {
                vals.add(it.next());
                if (vals.size() > elts.size()) {
                    throw PyException.valueError("too many values to unpack (expected " + elts.size() + ")");
                }
            }
            if (vals.size() < elts.size()) {
                throw PyException.valueError("not enough values to unpack (expected " + elts.size() + ", got "
                        + vals.size() + ")");
            }
            for (int i = 0; i < elts.size(); i++) {
                assign(elts.get(i), vals.get(i), scope);
            }
            return;
        }
        while (it.hasNext()) {
            vals.add(it.next());
        }
        int need = elts.size() - 1;
        if (vals.size() < need) {
            throw PyException.valueError("not enough values to unpack (expected at least " + need + ", got "
                    + vals.size() + ")");
        }
        int after = elts.size() - star - 1;
        for (int i = 0; i < star; i++) {
            assign(elts.get(i), vals.get(i), scope);
        }
        assign(((Ast.Starred) elts.get(star)).value(), new PyList(new ArrayList<>(vals.subList(star,
                vals.size() - after))), scope);
        for (int i = 0; i < after; i++) {
            assign(elts.get(star + 1 + i), vals.get(vals.size() - after + i), scope);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Comprehensions and generator expressions
    // ------------------------------------------------------------------------------------------------------------

    /** Lazily runs the for/if clauses of a comprehension; each element is produced by {@code produce}. */
    private final class CompIterator implements Iterator<PyObject> {
        private final List<CompFor> gens;
        private final Scope scope;
        private final Iterator<PyObject>[] its;
        private final java.util.function.Function<Scope, PyObject> produce;
        private int level;
        private PyObject pending;

        @SuppressWarnings("unchecked")
        CompIterator(List<CompFor> gens, Scope scope, Iterator<PyObject> first,
                java.util.function.Function<Scope, PyObject> produce) {
            this.gens = gens;
            this.scope = scope;
            this.its = new Iterator[gens.size()];
            this.its[0] = first;
            this.produce = produce;
        }

        @Override
        public boolean hasNext() {
            if (pending != null) {
                return true;
            }
            while (level >= 0) {
                if (its[level] == null) {
                    its[level] = eval(gens.get(level).iter(), scope).iter();
                }
                Iterator<PyObject> it = its[level];
                if (!it.hasNext()) {
                    its[level] = null;
                    level--;
                    continue;
                }
                CompFor g = gens.get(level);
                assign(g.target(), it.next(), scope);
                boolean ok = true;
                for (Node cond : g.ifs()) {
                    if (!eval(cond, scope).truthy()) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (level == gens.size() - 1) {
                    pending = produce.apply(scope);
                    return true;
                }
                level++;
            }
            return false;
        }

        @Override
        public PyObject next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            PyObject v = pending;
            pending = null;
            return v;
        }
    }

    private PyObject comprehension(Ast.Comp c, Scope outer) {
        // The first iterable is evaluated in the enclosing scope, before the comprehension's own scope exists.
        Iterator<PyObject> first = eval(c.gens().get(0).iter(), outer).iter();
        Scope scope = new Scope(outer, c.kind() == Ast.CompKind.GEN);
        switch (c.kind()) {
            case GEN:
                return new PyIter("generator", new CompIterator(c.gens(), scope, first, s -> eval(c.elt(), s)));
            case LIST: {
                List<PyObject> out = new ArrayList<>();
                CompIterator it = new CompIterator(c.gens(), scope, first, s -> eval(c.elt(), s));
                while (it.hasNext()) {
                    out.add(it.next());
                    if (out.size() > Py.MAX_SIZE) {
                        throw new PyException("MemoryError", "");
                    }
                }
                return new PyList(out);
            }
            case SET: {
                PySet out = new PySet();
                CompIterator it = new CompIterator(c.gens(), scope, first, s -> eval(c.elt(), s));
                while (it.hasNext()) {
                    out.add(it.next());
                }
                return out;
            }
            default: {
                PyDict out = new PyDict();
                CompIterator it = new CompIterator(c.gens(), scope, first, s -> {
                    PyObject k = eval(c.elt(), s);
                    PyObject v = eval(c.value(), s);
                    out.put(k, v);
                    return Py.NONE;
                });
                while (it.hasNext()) {
                    it.next();
                }
                return out;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lambdas
    // ------------------------------------------------------------------------------------------------------------

    private PyObject lambda(Ast.Lambda l, Scope defining) {
        List<PyObject> defaults = new ArrayList<>();
        for (Param p : l.params()) {
            defaults.add(p.dflt() == null ? null : eval(p.dflt(), defining));
        }
        return new PyFunction("<lambda>", (args, kwargs) -> {
            Scope s = new Scope(defining, true);
            bindParams(l.params(), defaults, args, kwargs, s.vars);
            return eval(l.body(), s);
        }, null, true);
    }

    private static void bindParams(List<Param> params, List<PyObject> defaults, List<PyObject> args,
            Map<String, PyObject> kwargs, Map<String, PyObject> vars) {
        final String fn = "<lambda>()";
        List<Integer> positional = new ArrayList<>();
        String varargs = null;
        String varkw = null;
        for (int i = 0; i < params.size(); i++) {
            switch (params.get(i).kind()) {
                case NORMAL -> positional.add(i);
                case VARARGS -> varargs = params.get(i).name();
                case VARKW -> varkw = params.get(i).name();
                default -> { }
            }
        }
        if (args.size() > positional.size() && varargs == null) {
            int required = 0;
            for (int i : positional) {
                if (defaults.get(i) == null) {
                    required++;
                }
            }
            String takes = required == positional.size() ? String.valueOf(positional.size())
                    : "from " + required + " to " + positional.size();
            throw PyException.typeError(fn + " takes " + takes + " positional argument"
                    + (positional.size() == 1 && required == positional.size() ? "" : "s") + " but " + args.size()
                    + (args.size() == 1 ? " was" : " were") + " given");
        }
        for (int k = 0; k < positional.size() && k < args.size(); k++) {
            vars.put(params.get(positional.get(k)).name(), args.get(k));
        }
        if (varargs != null) {
            vars.put(varargs, PyTuple.of(args.size() > positional.size()
                    ? new ArrayList<>(args.subList(positional.size(), args.size())) : new ArrayList<>()));
        }
        PyDict extra = new PyDict();
        for (Map.Entry<String, PyObject> e : kwargs.entrySet()) {
            Param match = null;
            for (Param p : params) {
                if ((p.kind() == Ast.ParamKind.NORMAL || p.kind() == Ast.ParamKind.KWONLY)
                        && p.name().equals(e.getKey())) {
                    match = p;
                }
            }
            if (match == null) {
                if (varkw == null) {
                    throw PyException.typeError(fn + " got an unexpected keyword argument '" + e.getKey() + "'");
                }
                extra.put(PyStr.of(e.getKey()), e.getValue());
                continue;
            }
            if (vars.containsKey(match.name())) {
                throw PyException.typeError(fn + " got multiple values for argument '" + match.name() + "'");
            }
            vars.put(match.name(), e.getValue());
        }
        List<String> missingPos = new ArrayList<>();
        List<String> missingKw = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if ((p.kind() == Ast.ParamKind.NORMAL || p.kind() == Ast.ParamKind.KWONLY) && !vars.containsKey(p.name())) {
                if (defaults.get(i) != null) {
                    vars.put(p.name(), defaults.get(i));
                } else if (p.kind() == Ast.ParamKind.NORMAL) {
                    missingPos.add(p.name());
                } else {
                    missingKw.add(p.name());
                }
            }
        }
        if (!missingPos.isEmpty()) {
            throw PyException.typeError(fn + " missing " + missingPos.size() + " required positional argument"
                    + (missingPos.size() == 1 ? "" : "s") + ": " + nameList(missingPos));
        }
        if (!missingKw.isEmpty()) {
            throw PyException.typeError(fn + " missing " + missingKw.size() + " required keyword-only argument"
                    + (missingKw.size() == 1 ? "" : "s") + ": " + nameList(missingKw));
        }
        if (varkw != null) {
            vars.put(varkw, extra);
        }
    }

    private static String nameList(List<String> names) {
        List<String> q = new ArrayList<>();
        for (String n : names) {
            q.add("'" + n + "'");
        }
        if (q.size() == 1) {
            return q.get(0);
        }
        if (q.size() == 2) {
            return q.get(0) + " and " + q.get(1);
        }
        return String.join(", ", q.subList(0, q.size() - 1)) + ", and " + q.get(q.size() - 1);
    }
}
