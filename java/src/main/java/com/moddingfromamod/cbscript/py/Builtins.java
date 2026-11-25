// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The built-in functions and types available to evaluated expressions. Names that reach outside the compiler
 * ({@code __import__}, {@code open}, {@code exec}, {@code eval}, {@code compile}, {@code globals}, {@code locals},
 * {@code vars}, {@code getattr}, {@code setattr}, {@code delattr}, {@code input}, {@code breakpoint}, {@code exit},
 * {@code quit}, {@code help}, {@code __builtins__}) are deliberately absent, so they raise NameError.
 */
final class Builtins {
    private Builtins() {}

    static final Map<String, PyObject> BUILTINS = new HashMap<>();
    /** Where print() writes; set by {@link PyEval#eval} for the duration of an evaluation. */
    static final ThreadLocal<Consumer<String>> PRINT = new ThreadLocal<>();

    static void ensureLoaded() {
        // Class initialization does the work.
    }

    private static void def(String name, BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> body) {
        BUILTINS.put(name, new PyFunction(name, body));
    }

    private static void type(String name, String base,
            BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> ctor) {
        BUILTINS.put(name, PyType.define(name, base, ctor));
    }

    static {
        defineTypes();
        defineFunctions();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------------------------------------------------

    private static void defineTypes() {
        type("object", null, null);
        type("int", "object", (a, kw) -> {
            Args.onlyKw("int", kw, "base");
            if (a.size() > 2) {
                throw PyException.typeError("int expected at most 2 arguments, got " + a.size());
            }
            PyObject base = Args.get(a, kw, 1, "base", null);
            if (a.isEmpty()) {
                if (base != null) {
                    throw PyException.typeError("int() missing string argument");
                }
                return PyInt.of(0);
            }
            if (base == null) {
                return Py.toInt(a.get(0));
            }
            return Py.toInt(a.get(0), (int) Py.indexLong(base));
        });
        type("bool", "int", (a, kw) -> {
            Args.noKwargs("bool", kw);
            Args.count("bool", a, 0, 1);
            return Py.of(!a.isEmpty() && a.get(0).truthy());
        });
        type("float", "object", (a, kw) -> {
            Args.noKwargs("float", kw);
            Args.count("float", a, 0, 1);
            return a.isEmpty() ? Py.of(0.0) : Py.toFloat(a.get(0));
        });
        type("str", "object", (a, kw) -> {
            Args.onlyKw("str", kw, "object");
            if (a.size() > 1) {
                throw PyException.typeError("decoding to str is not supported here");
            }
            PyObject o = Args.get(a, kw, 0, "object", null);
            return o == null ? PyStr.of("") : PyStr.of(o.str());
        });
        type("list", "object", (a, kw) -> {
            Args.noKwargs("list", kw);
            Args.count("list", a, 0, 1);
            return a.isEmpty() ? new PyList() : new PyList(Py.toList(a.get(0)));
        });
        type("tuple", "object", (a, kw) -> {
            Args.noKwargs("tuple", kw);
            Args.count("tuple", a, 0, 1);
            if (a.isEmpty()) {
                return PyTuple.EMPTY;
            }
            return a.get(0) instanceof PyTuple t ? t : PyTuple.of(Py.toList(a.get(0)));
        });
        type("dict", "object", (a, kw) -> {
            if (a.size() > 1) {
                throw PyException.typeError("dict expected at most 1 argument, got " + a.size());
            }
            PyDict d = new PyDict();
            if (!a.isEmpty()) {
                Methods.dictUpdate(d, a.get(0), "dict");
            }
            if (kw != null) {
                for (Map.Entry<String, PyObject> e : kw.entrySet()) {
                    d.put(PyStr.of(e.getKey()), e.getValue());
                }
            }
            return d;
        });
        type("set", "object", (a, kw) -> {
            Args.noKwargs("set", kw);
            Args.count("set", a, 0, 1);
            return a.isEmpty() ? new PySet() : PySet.fromIterable(a.get(0), false);
        });
        type("frozenset", "object", (a, kw) -> {
            Args.noKwargs("frozenset", kw);
            Args.count("frozenset", a, 0, 1);
            if (!a.isEmpty() && a.get(0) instanceof PySet s && s.isFrozen()) {
                return s;
            }
            return a.isEmpty() ? new PySet(true) : PySet.fromIterable(a.get(0), true);
        });
        type("range", "object", (a, kw) -> {
            Args.noKwargs("range", kw);
            if (a.isEmpty()) {
                throw PyException.typeError("range expected at least 1 argument, got 0");
            }
            if (a.size() > 3) {
                throw PyException.typeError("range expected at most 3 arguments, got " + a.size());
            }
            if (a.size() == 1) {
                return new PyRange(0, Py.indexLong(a.get(0)), 1);
            }
            return new PyRange(Py.indexLong(a.get(0)), Py.indexLong(a.get(1)),
                    a.size() > 2 ? Py.indexLong(a.get(2)) : 1);
        });
        type("slice", "object", (a, kw) -> {
            Args.noKwargs("slice", kw);
            Args.count("slice", a, 1, 3);
            if (a.size() == 1) {
                return new PySlice(Py.NONE, a.get(0), Py.NONE);
            }
            return new PySlice(a.get(0), a.get(1), a.size() > 2 ? a.get(2) : Py.NONE);
        });
        type("type", "object", (a, kw) -> {
            if (a.size() != 1 || (kw != null && !kw.isEmpty())) {
                throw PyException.typeError("type() takes 1 or 3 arguments");
            }
            return PyType.of(a.get(0).typeName());
        });
        PyType.define("NoneType", "object", (a, kw) -> {
            Args.noKwargs("NoneType", kw);
            if (!a.isEmpty()) {
                throw PyException.typeError("NoneType takes no arguments");
            }
            return Py.NONE;
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Functions
    // ------------------------------------------------------------------------------------------------------------

    private static void defineFunctions() {
        def("abs", (a, kw) -> Py.abs(Args.one("abs", a, kw)));
        def("all", (a, kw) -> {
            Iterator<PyObject> it = Args.one("all", a, kw).iter();
            while (it.hasNext()) {
                if (!it.next().truthy()) {
                    return Py.FALSE;
                }
            }
            return Py.TRUE;
        });
        def("any", (a, kw) -> {
            Iterator<PyObject> it = Args.one("any", a, kw).iter();
            while (it.hasNext()) {
                if (it.next().truthy()) {
                    return Py.TRUE;
                }
            }
            return Py.FALSE;
        });
        def("ascii", (a, kw) -> PyStr.of(PyStr.ascii(Args.one("ascii", a, kw).repr())));
        def("bin", (a, kw) -> PyStr.of(radix(Args.one("bin", a, kw), 2, "0b")));
        def("oct", (a, kw) -> PyStr.of(radix(Args.one("oct", a, kw), 8, "0o")));
        def("hex", (a, kw) -> PyStr.of(radix(Args.one("hex", a, kw), 16, "0x")));
        def("callable", (a, kw) -> {
            PyObject o = Args.one("callable", a, kw);
            return Py.of(o instanceof PyFunction || (o instanceof PyType));
        });
        def("chr", (a, kw) -> {
            PyInt i = Py.index(Args.one("chr", a, kw));
            if (!i.isLong() || i.longValue() < 0 || i.longValue() > 0x10ffff) {
                if (!i.isLong()) {
                    throw new PyException("OverflowError", "Python int too large to convert to C int");
                }
                throw PyException.valueError("chr() arg not in range(0x110000)");
            }
            return PyStr.ofCodePoint((int) i.longValue());
        });
        def("ord", (a, kw) -> {
            PyObject o = Args.one("ord", a, kw);
            if (!(o instanceof PyStr s)) {
                throw PyException.typeError("ord() expected string of length 1, but " + o.typeName() + " found");
            }
            if (s.len() != 1) {
                throw PyException.typeError("ord() expected a character, but string of length " + s.len()
                        + " found");
            }
            return PyInt.of(s.codePointAt(0));
        });
        def("divmod", (a, kw) -> {
            Args.noKwargs("divmod", kw);
            if (a.size() != 2) {
                throw PyException.typeError("divmod expected 2 arguments, got " + a.size());
            }
            return Py.divmod(a.get(0), a.get(1));
        });
        def("enumerate", (a, kw) -> {
            Args.onlyKw("enumerate", kw, "iterable", "start");
            PyObject iterable = Args.get(a, kw, 0, "iterable", null);
            if (iterable == null) {
                throw PyException.typeError("enumerate() missing required argument 'iterable'");
            }
            if (a.size() > 2) {
                throw PyException.typeError("enumerate() takes at most 2 arguments (" + a.size() + " given)");
            }
            PyInt start = Py.index(Args.get(a, kw, 1, "start", PyInt.of(0)));
            Iterator<PyObject> it = iterable.iter();
            return new PyIter("enumerate", new Iterator<>() {
                BigInteger n = start.value();

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public PyObject next() {
                    PyObject v = it.next();
                    PyObject r = Py.tuple(PyInt.of(n), v);
                    n = n.add(BigInteger.ONE);
                    return r;
                }
            });
        });
        def("filter", (a, kw) -> {
            Args.noKwargs("filter", kw);
            if (a.size() != 2) {
                throw PyException.typeError("filter expected 2 arguments, got " + a.size());
            }
            PyObject fn = a.get(0);
            Iterator<PyObject> it = a.get(1).iter();
            return new PyIter("filter", new Iterator<>() {
                PyObject pending;

                @Override
                public boolean hasNext() {
                    while (pending == null && it.hasNext()) {
                        PyObject v = it.next();
                        boolean keep = fn == Py.NONE ? v.truthy() : fn.call(List.of(v), Map.of()).truthy();
                        if (keep) {
                            pending = v;
                        }
                    }
                    return pending != null;
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
            });
        });
        def("format", (a, kw) -> {
            Args.noKwargs("format", kw);
            Args.count("format", a, 1, 2);
            String spec = a.size() > 1 ? Args.str("format", a.get(1), 2) : "";
            return PyStr.of(Formatter.format(a.get(0), spec));
        });
        def("hash", (a, kw) -> PyInt.of(Args.one("hash", a, kw).hash()));
        def("hasattr", (a, kw) -> {
            Args.noKwargs("hasattr", kw);
            if (a.size() != 2) {
                throw PyException.typeError("hasattr expected 2 arguments, got " + a.size());
            }
            if (!(a.get(1) instanceof PyStr name)) {
                throw PyException.typeError("attribute name must be string, not '" + a.get(1).typeName() + "'");
            }
            try {
                a.get(0).getattr(name.value());
                return Py.TRUE;
            } catch (PyException e) {
                if (e.type.equals("AttributeError")) {
                    return Py.FALSE;
                }
                throw e;
            }
        });
        BUILTINS.put("Ellipsis", PyParser.ELLIPSIS);
        def("id", (a, kw) -> PyInt.of(Py.identityHash(Args.one("id", a, kw))));
        def("isinstance", (a, kw) -> {
            Args.noKwargs("isinstance", kw);
            if (a.size() != 2) {
                throw PyException.typeError("isinstance expected 2 arguments, got " + a.size());
            }
            return Py.of(isInstance(a.get(0), a.get(1), "isinstance() arg 2 must be a type, a tuple of types, "
                    + "or a union"));
        });
        def("issubclass", (a, kw) -> {
            Args.noKwargs("issubclass", kw);
            if (a.size() != 2) {
                throw PyException.typeError("issubclass expected 2 arguments, got " + a.size());
            }
            if (!(a.get(0) instanceof PyType t)) {
                throw PyException.typeError("issubclass() arg 1 must be a class");
            }
            PyObject sample = sampleInstance(t.name());
            return Py.of(sample != null ? isInstance(sample, a.get(1), "issubclass() arg 2 must be a class, a "
                    + "tuple of classes, or a union") : a.get(1) == t);
        });
        def("iter", (a, kw) -> {
            Args.noKwargs("iter", kw);
            Args.count("iter", a, 1, 1);
            PyObject o = a.get(0);
            if (o instanceof PyIter) {
                return o;
            }
            return new PyIter(iterTypeName(o), o.iter());
        });
        def("len", (a, kw) -> {
            PyObject o = Args.one("len", a, kw);
            return PyInt.of(o instanceof PyRange r ? r.length() : o.len());
        });
        def("map", (a, kw) -> {
            Args.noKwargs("map", kw);
            if (a.size() < 2) {
                throw PyException.typeError("map() must have at least two arguments.");
            }
            PyObject fn = a.get(0);
            List<Iterator<PyObject>> its = new ArrayList<>();
            for (PyObject o : a.subList(1, a.size())) {
                its.add(o.iter());
            }
            return new PyIter("map", new Iterator<>() {
                @Override
                public boolean hasNext() {
                    for (Iterator<PyObject> it : its) {
                        if (!it.hasNext()) {
                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public PyObject next() {
                    List<PyObject> args = new ArrayList<>();
                    for (Iterator<PyObject> it : its) {
                        args.add(it.next());
                    }
                    return fn.call(args, Map.of());
                }
            });
        });
        def("max", (a, kw) -> minMax("max", a, kw, true));
        def("min", (a, kw) -> minMax("min", a, kw, false));
        def("next", (a, kw) -> {
            Args.noKwargs("next", kw);
            Args.count("next", a, 1, 2);
            if (!(a.get(0) instanceof PyIter it)) {
                throw PyException.typeError("'" + a.get(0).typeName() + "' object is not an iterator");
            }
            PyObject v = it.next();
            if (v == null) {
                if (a.size() > 1) {
                    return a.get(1);
                }
                throw new PyException("StopIteration", "");
            }
            return v;
        });
        def("pow", (a, kw) -> {
            Args.onlyKw("pow", kw, "base", "exp", "mod");
            PyObject base = Args.get(a, kw, 0, "base", null);
            PyObject exp = Args.get(a, kw, 1, "exp", null);
            PyObject mod = Args.get(a, kw, 2, "mod", Py.NONE);
            if (a.size() > 3) {
                throw PyException.typeError("pow() takes at most 3 arguments (" + a.size() + " given)");
            }
            if (base == null) {
                throw PyException.typeError("pow() missing required argument 'base' (pos 1)");
            }
            if (exp == null) {
                throw PyException.typeError("pow() missing required argument 'exp' (pos 2)");
            }
            return mod == Py.NONE ? Py.pow(base, exp) : Py.modPow(base, exp, mod);
        });
        def("print", (a, kw) -> {
            Args.onlyKw("print", kw, "sep", "end", "file", "flush");
            PyObject file = kw == null ? null : kw.get("file");
            if (file != null && file != Py.NONE) {
                throw new PyException("AttributeError", "'" + file.typeName() + "' object has no attribute 'write'");
            }
            PyObject sep = kw == null ? null : kw.get("sep");
            PyObject end = kw == null ? null : kw.get("end");
            String sepS = sep == null || sep == Py.NONE ? " " : printArg("sep", sep);
            String endS = end == null || end == Py.NONE ? "\n" : printArg("end", end);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) {
                    sb.append(sepS);
                }
                sb.append(a.get(i).str());
            }
            sb.append(endS);
            Consumer<String> out = PRINT.get();
            if (out != null) {
                out.accept(sb.toString());
            }
            return Py.NONE;
        });
        def("repr", (a, kw) -> PyStr.of(Args.one("repr", a, kw).repr()));
        def("reversed", (a, kw) -> {
            Args.noKwargs("reversed", kw);
            if (a.size() != 1) {
                throw PyException.typeError("reversed expected 1 argument, got " + a.size());
            }
            PyObject o = a.get(0);
            List<PyObject> items;
            if (o instanceof PySequence s) {
                items = s.items();
            } else if (o instanceof PyStr || o instanceof PyRange || o instanceof PyDict
                    || o instanceof PyDictView) {
                items = Py.toList(o);
            } else {
                throw PyException.typeError("'" + o.typeName() + "' object is not reversible");
            }
            List<PyObject> snapshot = items;
            String tn = o instanceof PyList ? "list_reverseiterator" : o instanceof PyRange ? "range_iterator"
                    : "reversed";
            return new PyIter(tn, new Iterator<>() {
                int i = snapshot.size() - 1;

                @Override
                public boolean hasNext() {
                    return i >= 0 && i < snapshot.size();
                }

                @Override
                public PyObject next() {
                    return snapshot.get(i--);
                }
            });
        });
        def("round", (a, kw) -> {
            Args.onlyKw("round", kw, "number", "ndigits");
            PyObject number = Args.get(a, kw, 0, "number", null);
            PyObject nd = Args.get(a, kw, 1, "ndigits", Py.NONE);
            if (number == null) {
                throw PyException.typeError("round() missing required argument 'number' (pos 1)");
            }
            if (a.size() > 2) {
                throw PyException.typeError("round() takes at most 2 arguments (" + a.size() + " given)");
            }
            return round(number, nd);
        });
        def("sorted", (a, kw) -> {
            Args.onlyKw("sort", kw, "key", "reverse");
            if (a.size() != 1) {
                throw PyException.typeError(a.isEmpty() ? "sorted expected 1 argument, got 0"
                        : "sorted expected 1 argument, got " + a.size());
            }
            PyObject key = kw == null ? null : kw.get("key");
            boolean reverse = kw != null && kw.containsKey("reverse") && kw.get("reverse").truthy();
            return new PyList(sort(Py.toList(a.get(0)), key, reverse));
        });
        def("sum", (a, kw) -> {
            Args.onlyKw("sum", kw, "start");
            if (a.isEmpty()) {
                throw PyException.typeError("sum() takes at least 1 positional argument (0 given)");
            }
            if (a.size() > 2) {
                throw PyException.typeError("sum() takes at most 2 arguments (" + a.size() + " given)");
            }
            return sum(a.get(0), Args.get(a, kw, 1, "start", null));
        });
        def("zip", (a, kw) -> {
            Args.onlyKw("zip", kw, "strict");
            boolean strict = kw != null && kw.containsKey("strict") && kw.get("strict").truthy();
            List<Iterator<PyObject>> its = new ArrayList<>();
            for (PyObject o : a) {
                its.add(o.iter());
            }
            return new PyIter("zip", new Iterator<>() {
                List<PyObject> pending;
                boolean done = its.isEmpty();

                @Override
                public boolean hasNext() {
                    if (pending != null) {
                        return true;
                    }
                    if (done) {
                        return false;
                    }
                    List<PyObject> row = new ArrayList<>();
                    for (int i = 0; i < its.size(); i++) {
                        if (!its.get(i).hasNext()) {
                            done = true;
                            if (strict) {
                                if (i > 0) {
                                    throw PyException.valueError("zip() argument " + (i + 1) + " is shorter than "
                                            + "argument" + (i == 1 ? " 1" : "s 1-" + i));
                                }
                                for (int j = 1; j < its.size(); j++) {
                                    if (its.get(j).hasNext()) {
                                        throw PyException.valueError("zip() argument " + (j + 1)
                                                + " is longer than argument" + (j == 1 ? " 1" : "s 1-" + j));
                                    }
                                }
                            }
                            return false;
                        }
                        row.add(its.get(i).next());
                    }
                    pending = row;
                    return true;
                }

                @Override
                public PyObject next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    PyObject t = PyTuple.of(pending);
                    pending = null;
                    return t;
                }
            });
        });
    }

    private static String printArg(String name, PyObject o) {
        if (o instanceof PyStr s) {
            return s.value();
        }
        throw PyException.typeError(name + " must be None or a string, not " + o.typeName());
    }

    static String iterTypeName(PyObject o) {
        return switch (o.typeName()) {
            case "list" -> "list_iterator";
            case "tuple" -> "tuple_iterator";
            case "str" -> "str_ascii_iterator";
            case "range" -> "range_iterator";
            case "dict", "dict_keys" -> "dict_keyiterator";
            case "dict_values" -> "dict_valueiterator";
            case "dict_items" -> "dict_itemiterator";
            case "set", "frozenset" -> "set_iterator";
            default -> "iterator";
        };
    }

    private static PyObject sampleInstance(String typeName) {
        return switch (typeName) {
            case "bool" -> Py.TRUE;
            case "int" -> PyInt.of(0);
            case "float" -> Py.of(0.0);
            case "str" -> PyStr.of("");
            case "list" -> new PyList();
            case "tuple" -> PyTuple.EMPTY;
            case "dict" -> new PyDict();
            case "set" -> new PySet();
            case "frozenset" -> new PySet(true);
            case "NoneType" -> Py.NONE;
            default -> null;
        };
    }

    private static boolean isInstance(PyObject o, PyObject cls, String error) {
        if (cls instanceof PyType t) {
            return t.isInstance(o);
        }
        if (cls instanceof PyTuple tup) {
            for (PyObject c : tup.items()) {
                if (isInstance(o, c, error)) {
                    return true;
                }
            }
            return false;
        }
        throw PyException.typeError(error);
    }

    private static String radix(PyObject o, int radix, String prefix) {
        BigInteger v = Py.index(o).value();
        return (v.signum() < 0 ? "-" : "") + prefix + v.abs().toString(radix);
    }

    private static PyObject minMax(String fn, List<PyObject> a, Map<String, PyObject> kw, boolean max) {
        Args.onlyKw(fn, kw, "key", "default");
        PyObject key = kw == null ? null : kw.get("key");
        if (key == Py.NONE) {
            key = null;
        }
        PyObject dflt = kw == null ? null : kw.get("default");
        Iterator<PyObject> it;
        if (a.isEmpty()) {
            throw PyException.typeError(fn + " expected at least 1 argument, got 0");
        }
        if (a.size() == 1) {
            it = a.get(0).iter();
        } else {
            if (dflt != null) {
                throw PyException.typeError("Cannot specify a default for " + fn + "() with multiple positional "
                        + "arguments");
            }
            it = a.iterator();
        }
        PyObject best = null;
        PyObject bestKey = null;
        while (it.hasNext()) {
            PyObject v = it.next();
            PyObject k = key == null ? v : key.call(List.of(v), Map.of());
            if (best == null) {
                best = v;
                bestKey = k;
            } else if (max ? Py.compareOp(">", k, bestKey) : Py.compareOp("<", k, bestKey)) {
                best = v;
                bestKey = k;
            }
        }
        if (best == null) {
            if (dflt != null) {
                return dflt;
            }
            throw PyException.valueError(fn + "() iterable argument is empty");
        }
        return best;
    }

    /** sorted(): a stable merge sort that, like CPython's, only ever asks {@code <}. */
    static List<PyObject> sort(List<PyObject> items, PyObject key, boolean reverse) {
        List<PyObject> vals = new ArrayList<>(items);
        if (key == Py.NONE) {
            key = null;
        }
        if (reverse) {
            Collections.reverse(vals);
        }
        List<PyObject> keys = new ArrayList<>(vals.size());
        for (PyObject v : vals) {
            keys.add(key == null ? v : key.call(List.of(v), Map.of()));
        }
        Integer[] idx = new Integer[vals.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Integer[] tmp = new Integer[idx.length];
        mergeSort(idx, tmp, 0, idx.length, keys);
        List<PyObject> out = new ArrayList<>(vals.size());
        for (Integer i : idx) {
            out.add(vals.get(i));
        }
        if (reverse) {
            Collections.reverse(out);
        }
        return out;
    }

    private static void mergeSort(Integer[] a, Integer[] tmp, int lo, int hi, List<PyObject> keys) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, keys);
        mergeSort(a, tmp, mid, hi, keys);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (Py.lt(keys.get(a[j]), keys.get(a[i]))) {
                tmp[k++] = a[j++];
            } else {
                tmp[k++] = a[i++];
            }
        }
        while (i < mid) {
            tmp[k++] = a[i++];
        }
        while (j < hi) {
            tmp[k++] = a[j++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }

    /** CPython's builtin_sum_impl, including its exact float summation (Neumaier compensation). */
    static PyObject sum(PyObject iterable, PyObject start) {
        Iterator<PyObject> it = iterable.iter();
        PyObject result = start == null ? PyInt.of(0) : start;
        if (result instanceof PyStr) {
            throw PyException.typeError("sum() can't sum strings [use ''.join(seq) instead]");
        }
        if (result instanceof PyInt ri && !(result instanceof PyBool) && ri.isLong()) {
            long acc = ri.longValue();
            result = null;
            while (result == null) {
                if (!it.hasNext()) {
                    return PyInt.of(acc);
                }
                PyObject item = it.next();
                if (item instanceof PyInt ii && ii.isLong()) {
                    long b = ii.longValue();
                    long r = acc + b;
                    if (((acc ^ r) & (b ^ r)) >= 0) {
                        acc = r;
                        continue;
                    }
                }
                result = Py.binop("+", PyInt.of(acc), item);
            }
        }
        if (result instanceof PyFloat rf) {
            double f = rf.value();
            double c = 0.0;
            result = null;
            while (result == null) {
                if (!it.hasNext()) {
                    if (c != 0 && Double.isFinite(c)) {
                        f += c;
                    }
                    return Py.of(f);
                }
                PyObject item = it.next();
                if (item instanceof PyFloat xf) {
                    double x = xf.value();
                    double t = f + x;
                    if (Math.abs(f) >= Math.abs(x)) {
                        c += (f - t) + x;
                    } else {
                        c += (x - t) + f;
                    }
                    f = t;
                    continue;
                }
                if (item instanceof PyInt ii && ii.isLong()) {
                    f += (double) ii.longValue();
                    continue;
                }
                if (c != 0 && Double.isFinite(c)) {
                    f += c;
                }
                result = Py.binop("+", Py.of(f), item);
            }
        }
        while (it.hasNext()) {
            result = Py.binop("+", result, it.next());
        }
        return result;
    }

    /** round(number, ndigits). */
    static PyObject round(PyObject number, PyObject nd) {
        if (number instanceof PyInt i) {
            if (nd == Py.NONE) {
                return PyInt.of(i.value());
            }
            long n = Py.indexLong(nd);
            if (n >= 0) {
                return PyInt.of(i.value());
            }
            if (n < -100000) {
                return PyInt.of(0);
            }
            BigDecimal v = new BigDecimal(i.value()).setScale((int) n, RoundingMode.HALF_EVEN);
            return PyInt.of(v.toBigInteger());
        }
        if (number instanceof PyFloat f) {
            double x = f.value();
            if (nd == Py.NONE) {
                double r = Math.rint(x);
                return Py.floatToInt(r);
            }
            long n = Py.indexLong(nd);
            if (!Double.isFinite(x)) {
                return f;
            }
            if (n > 323) {
                return f;
            }
            if (n < -308) {
                return Py.of(0.0 * x);
            }
            if (x == 0) {
                return f;
            }
            BigDecimal v = new BigDecimal(x).setScale((int) n, RoundingMode.HALF_EVEN);
            double r = v.doubleValue();
            if (Double.isInfinite(r)) {
                throw new PyException("OverflowError", "rounded value too large to represent");
            }
            if (r == 0) {
                r = Math.copySign(0.0, x);
            }
            return Py.of(r);
        }
        throw PyException.typeError("type " + number.typeName() + " doesn't define __round__ method");
    }
}
