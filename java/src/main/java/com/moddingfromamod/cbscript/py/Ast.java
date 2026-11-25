// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.util.List;

/** Syntax tree of a Python expression, as {@link PyParser} builds it and {@link PyEval} evaluates it. */
final class Ast {
    private Ast() {}

    sealed interface Node permits Const, Name, Attr, Subscript, Slice, Call, BinOp, Unary, BoolOp, Compare, IfExp,
            Lambda, Display, DictDisplay, Starred, Comp, JoinedStr, FormattedValue, NamedExpr {}

    record Const(PyObject value) implements Node {}

    record Name(String id) implements Node {}

    record Attr(Node obj, String name) implements Node {}

    record Subscript(Node obj, Node index) implements Node {}

    /** A slice inside a subscript; null bounds are absent. */
    record Slice(Node lower, Node upper, Node step) implements Node {}

    enum ArgKind { POS, STAR, KW, DSTAR }

    record Arg(ArgKind kind, String name, Node value) {}

    record Call(Node fn, List<Arg> args) implements Node {}

    record BinOp(String op, Node left, Node right) implements Node {}

    /** Unary operator: "-", "+", "~" or "not". */
    record Unary(String op, Node operand) implements Node {}

    record BoolOp(boolean isAnd, List<Node> values) implements Node {}

    record Compare(Node left, List<String> ops, List<Node> comparators) implements Node {}

    record IfExp(Node test, Node body, Node orelse) implements Node {}

    enum ParamKind { NORMAL, VARARGS, KWONLY, VARKW }

    record Param(String name, Node dflt, ParamKind kind) {}

    record Lambda(List<Param> params, Node body) implements Node {}

    enum DisplayKind { LIST, TUPLE, SET }

    record Display(DisplayKind kind, List<Node> elts) implements Node {}

    /** A dict display; a null key marks a {@code **mapping} entry. */
    record DictDisplay(List<Node> keys, List<Node> values) implements Node {}

    record Starred(Node value) implements Node {}

    enum CompKind { LIST, SET, DICT, GEN }

    record CompFor(Node target, Node iter, List<Node> ifs) {}

    /** A comprehension; for DICT, elt is the key and value the value. */
    record Comp(CompKind kind, Node elt, Node value, List<CompFor> gens) implements Node {}

    record JoinedStr(List<Node> parts) implements Node {}

    record FormattedValue(Node value, char conversion, Node spec, String debugText) implements Node {}

    record NamedExpr(String target, Node value) implements Node {}
}
