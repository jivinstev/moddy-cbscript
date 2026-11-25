// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.compiler.Ast.Block;
import com.moddingfromamod.cbscript.compiler.Ast.Coords;
import com.moddingfromamod.cbscript.compiler.Ast.Expr;
import com.moddingfromamod.cbscript.compiler.Ast.ScoreboardVar;
import com.moddingfromamod.cbscript.compiler.Ast.Value;
import com.moddingfromamod.cbscript.compiler.Ast.Var;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyObject;

/** block_types/, class for class. */
public final class Blocks {
    private Blocks() {
    }

    /** block_base.compile_lines. */
    static void compileLines(McFunction f, List<Block> lines, String blockName, int line) {
        try {
            f.compileBlocks(lines);
        } catch (PyException e) {
            if (e.isA("CompileError")) {
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    return Ctx.compileError("Unable to compile " + blockName + " at line " + line);
                });
            }
            // `except e:` evaluates the unbound name e.
            throw Ctx.except(e, () -> new PyException("NameError", "name 'e' is not defined"));
        }
    }

    /** The try/except shape most blocks use around compiling their contents. */
    static void compileContents(McFunction f, List<Block> lines, String message) {
        try {
            f.compileBlocks(lines);
        } catch (PyException e) {
            if (e.isA("CompileError")) {
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    return Ctx.compileError(message);
                });
            }
            throw Ctx.except(e, () -> {
                Ctx.printTraceback(e);
                return Ctx.compileError(message);
            });
        }
    }

    static String pad3(int n) {
        return String.format("%03d", n);
    }

    /** A Python tuple where a block was expected (an else list handed to a compile-time if). */
    static final class TupleBlock implements Block {
        public int line() {
            throw Ast.noAttr("tuple", "line");
        }

        public void compile(McFunction f) {
            throw Ast.noAttr("tuple", "compile");
        }
    }

    // ── simple blocks ────────────────────────────────────────────────────────

    record CommandBlock(int line, String text) implements Block {
        public void compile(McFunction f) {
            f.addCommand(text);
        }
    }

    record CommentBlock(int line, String text) implements Block {
        public void compile(McFunction f) {
            f.addCommand(text);
        }
    }

    record AdvancementDefinition(int line, String name, String json) implements Block {
        public void compile(McFunction f) {
            f.globalContext().advancements.put(name, f.applyReplacements(json));
        }
    }

    record LootTableDefinition(int line, String type, String name, String json) implements Block {
        public void compile(McFunction f) {
            f.globalContext().lootTables.put(name, new GlobalContext.LootTable(type, json));
        }
    }

    record PredicateDefinition(int line, String name, String json) implements Block {
        public void compile(McFunction f) {
            f.globalContext().predicates.put(name, json);
        }
    }

    record ItemModifierDefinition(int line, String name, String json) implements Block {
        public void compile(McFunction f) {
            f.globalContext().itemModifiers.put(name, json);
        }
    }

    record BlockTagBlock(int line, String name, List<String> blocks) implements Block {
        public void compile(McFunction f) {
            f.globalContext().registerBlockTag(name, blocks);
        }
    }

    record EntityTagBlock(int line, String name, List<String> entities) implements Block {
        public void compile(McFunction f) {
            f.globalContext().registerEntityTag(name, entities);
        }
    }

    record ItemTagBlock(int line, String name, List<String> items) implements Block {
        public void compile(McFunction f) {
            f.globalContext().registerItemTag(name, items);
        }
    }

    record DefineNameBlock(int line, String id, String str) implements Block {
        public void compile(McFunction f) {
            f.registerNameDefinition(id, str);
        }
    }

    record PointerDeclBlock(int line, String id, String selector) implements Block {
        public void compile(McFunction f) {
            f.environment.pointers.put(id, selector);
        }
    }

    record SelectorAssignmentBlock(int line, String id, String fullselector) implements Block {
        public void compile(McFunction f) {
            f.setAtid(id, fullselector);
        }
    }

    record MoveBlock(int line, String selector, Coords coords) implements Block {
        public void compile(McFunction f) {
            if (selector.equals("@s") || selector.equals("@s[]")) {
                f.addCommand("execute at @s run tp @s " + coords.getValue(f));
            } else {
                f.addCommand("execute as " + selector + " at @s run tp @s " + coords.getValue(f));
            }
        }
    }

    record NbtDataBlock(int line, Ast.NbtPath dest, String op, Ast.NbtSource source) implements Block {
        public void compile(McFunction f) {
            f.addCommand("data modify " + dest.getDestPath(f) + " " + op + " " + source.getSourcePath(f));
        }
    }

    record NbtRemoveBlock(int line, Ast.NbtPath path) implements Block {
        public void compile(McFunction f) {
            f.addCommand("data remove " + path.getDestPath(f));
        }
    }

    record PrintBlock(int line, Value val) implements Block {
        public void compile(McFunction f) {
            PyObject v;
            try {
                v = val.getValue(f);
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> new PyException("Exception",
                        "Unable to get print value at line " + line + ". Exception: \"" + e.str() + "\""));
            }
            Ctx.print(v.str());
        }
    }

    record PythonAssignmentBlock(int line, String id, Value val) implements Block {
        public void compile(McFunction f) {
            f.setDollarid(id, val.getValue(f));
        }
    }

    record PythonTupleAssignmentBlock(int line, List<String> ids, Value val) implements Block {
        public void compile(McFunction f) {
            PyObject v = val.getValue(f);
            int n;
            try {
                n = v.len();
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    return Ctx.compileError("Expression at line " + line + " is not a tuple.");
                });
            }
            if (n != ids.size()) {
                throw Ctx.compileError("Expected " + ids.size() + " values for tuple expression at line " + line + ", got " + v.len());
            }
            for (int i = 0; i < n; i++) {
                f.setDollarid(ids.get(i), v.getitem(Py.of(i)));
            }
        }
    }

    record ImportBlock(int line, String filename) implements Block {
        public void compile(McFunction f) {
            try {
                f.importFile(filename + ".cblib");
            } catch (PyException e) {
                String msg = "Importing file \"" + filename + "\" failed at line " + line + ":\n" + e.str();
                if (e.isA("SyntaxError") || e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError(msg);
                    });
                }
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> {
                    Ctx.printTraceback(e);
                    return Ctx.compileError(msg);
                });
            }
        }
    }

    record PythonImportBlock(int line, String filename) implements Block {
        public void compile(McFunction f) {
            try {
                f.importPythonFile(filename + ".py");
            } catch (PyException e) {
                String msg = "Importing file \"" + filename + "\" failed at line " + line + ":\n" + e.str();
                if (e.isA("SyntaxError") || e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError(msg);
                    });
                }
                throw Ctx.except(e, () -> {
                    Ctx.printTraceback(e);
                    return Ctx.compileError(msg);
                });
            }
        }
    }

    record TellBlock(int line, String selector, String unformatted) implements Block {
        public void compile(McFunction f) {
            String text = Tellraw.formatJsonText(f, f.applyReplacements(unformatted));
            f.addCommand("/tellraw " + selector + " " + text);
        }
    }

    record TitleBlock(int line, String subtype, String selector, Value[] times, String unformatted) implements Block {
        public void compile(McFunction f) {
            if (times != null) {
                List<String> t = new ArrayList<>();
                for (Value v : times) {
                    t.add(v.getValue(f).str());
                }
                f.addCommand("/title " + selector + " times " + String.join(" ", t));
            }
            String text = Tellraw.formatJsonText(f, unformatted);
            f.addCommand("/title " + selector + " " + subtype + " " + text);
        }
    }

    // ── sections ─────────────────────────────────────────────────────────────

    record ResetSection(int line, List<Block> lines) implements Block {
        public void compile(McFunction f) {
            McFunction reset = f.getResetFunction();
            if (reset == null) {
                reset = f.createChildFunction();
                f.registerFunction("reset", reset);
            }
            reset.copyEnvironmentFrom(f);
            compileLines(reset, lines, "reset section", line);
        }
    }

    record ClockSection(int line, String id, List<Block> lines) implements Block {
        public void compile(McFunction f) {
            McFunction clock = f.createChildFunction(id, false, List.of());
            f.registerClock(id);
            f.registerFunction(id, clock);
            compileLines(clock, lines, "clock \"" + id + "\"", line);
        }
    }

    static final class FunctionSection implements Block {
        final int line;
        final String name;
        final List<String> params;
        final List<Block> lines;
        String selfSelector;

        FunctionSection(int line, String name, List<String> params, List<Block> lines) {
            this.line = line;
            this.name = name;
            this.params = params;
            this.lines = lines;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            McFunction func = f.createChildFunction(name, true, params);
            f.registerFunction(name, func);
            if (selfSelector != null && !selfSelector.isEmpty()) {
                func.updateSelfSelector(selfSelector);
            }
            compileLines(func, lines, "function \"" + name + "\"", line);
        }

        public void register(GlobalContext gc) {
            gc.registerFunctionParams(name, params);
        }
    }

    record MacroSection(int line, String name, List<String> params, List<Block> lines) implements Block {
        public void compile(McFunction f) {
        }

        public void register(GlobalContext gc) {
            gc.macros.put(name, new GlobalContext.Macro(params, lines));
        }
    }

    record TemplateFunctionSection(int line, String name, List<String> macroParams, List<String> params, List<Block> lines) implements Block {
        public void compile(McFunction f) {
        }

        public void register(GlobalContext gc) {
            gc.templateFunctions.put(name, new GlobalContext.Template(macroParams, params, lines));
            gc.registerFunctionParams(name, params);
        }
    }

    // ── definitions ──────────────────────────────────────────────────────────

    static final class ArrayDefinitionBlock implements Block {
        final int line;
        final String name;
        final Value fromVal;
        final Value toVal;
        boolean selectorBased;

        ArrayDefinitionBlock(int line, String name, Value fromVal, Value toVal, boolean selectorBased) {
            this.line = line;
            this.name = name;
            this.fromVal = fromVal;
            this.toVal = toVal;
            this.selectorBased = selectorBased;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            BigInteger from;
            BigInteger to;
            try {
                from = McFunction.intOf(fromVal.getValue(f));
                to = McFunction.intOf(toVal.getValue(f));
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> Ctx.compileError("Unable to get array range for \"" + name + "\" at line " + line));
            }
            List<BigInteger> vals = new ArrayList<>();
            for (BigInteger i = from; i.compareTo(to) < 0; i = i.add(BigInteger.ONE)) {
                vals.add(i);
            }
            for (BigInteger i : vals) {
                f.registerObjective(name + i);
            }
            String valvar = name + "Val";
            f.registerObjective(valvar);
            String selector = selectorBased ? "@s" : "Global";
            ScoreboardVar indexvar = new ScoreboardVar(selector, name + "Idx");
            McFunction get = f.createChildFunction();
            f.registerFunction("array_" + name.toLowerCase(java.util.Locale.ROOT) + "_get", get);
            List<McFunction.Case> cases = new ArrayList<>();
            for (BigInteger i : vals) {
                cases.add(new McFunction.Case(i, i, List.of(new CommandBlock(line,
                        "/scoreboard players operation " + selector + " " + valvar + " = " + selector + " " + name + i)), line, null));
            }
            if (!get.switchCases(indexvar, cases, "arrayget", "arraygetidx")) {
                throw new PyException("Exception", "Error creating getter for array at line " + line);
            }
            McFunction set = f.createChildFunction();
            f.registerFunction("array_" + name.toLowerCase(java.util.Locale.ROOT) + "_set", set);
            cases = new ArrayList<>();
            for (BigInteger i : vals) {
                cases.add(new McFunction.Case(i, i, List.of(new CommandBlock(line,
                        "/scoreboard players operation " + selector + " " + name + i + " = " + selector + " " + valvar)), line, null));
            }
            if (!set.switchCases(indexvar, cases, "arrayset", "arraysetidx")) {
                throw new PyException("Exception", "Error creating setter for array at line " + line);
            }
            f.registerArray(name, from, to, selectorBased);
        }
    }

    /** A selector definition's items: ('Tag', json), ('Path', ...), ('VectorPath', ...), ('Method', section)... */
    record SelectorItem(String type, Object[] val) {
    }

    record SelectorDefinitionBlock(int line, String id, String fullselector, String uuid, List<SelectorItem> items) implements Block {
        public void compile(McFunction f) {
            SelectorDefinition selector = f.setAtid(id, fullselector);
            selector.uuid = uuid;
            for (SelectorItem item : items) {
                Object[] v = item.val();
                switch (item.type()) {
                    case "Tag" -> selector.tag = (String) v[0];
                    case "Path" -> {
                        PyObject scale = v[3] == null ? f.getScale() : ((Value) v[3]).getValue(f);
                        selector.paths.put((String) v[0], new SelectorDefinition.Path((String) v[1], (String) v[2], scale));
                    }
                    case "VectorPath" -> {
                        PyObject scale = v[3] == null ? f.getScale() : ((Value) v[3]).getValue(f);
                        selector.vectorPaths.put((String) v[0], new SelectorDefinition.Path((String) v[1], (String) v[2], scale));
                    }
                    case "Method" -> {
                        FunctionSection section = (FunctionSection) v[0];
                        section.selfSelector = "@" + id;
                        section.compile(f);
                    }
                    case "Pointer" -> selector.pointers.put((String) v[0], (String) v[1]);
                    case "Array" -> ((Block) v[0]).compile(f);
                    case "Predicate" -> {
                        PredicateDefinition def = (PredicateDefinition) v[0];
                        def.compile(f);
                        selector.predicates.put(def.name(), true);
                    }
                    default -> throw new PyException("NameError", "name 'get_line' is not defined");
                }
            }
        }
    }

    record ShapedRecipeBlock(int line, List<String> recipeLines, List<String[]> keys, Value count, String item) implements Block {
        public void compile(McFunction f) {
            PyObject c = count.getValue(f);
            List<String[]> withNs = new ArrayList<>();
            for (String[] k : keys) {
                if (k[1].equals("tag") && f.globalContext().itemTags.containsKey(k[2])) {
                    withNs.add(new String[] {k[0], k[1], f.namespace() + ":" + k[2]});
                } else {
                    withNs.add(new String[] {k[0], k[1], "minecraft:" + k[2]});
                }
            }
            f.globalContext().recipes.add(new Ast.ShapedRecipe(recipeLines, withNs, c, item, f.namespace()));
        }
    }

    // ── control flow ─────────────────────────────────────────────────────────

    /** execute_base. */
    abstract static class ExecuteBase implements Block {
        final int line;
        final List<Ast.ExecItem> execItems;
        final List<Block> sub;

        ExecuteBase(int line, List<Ast.ExecItem> execItems, List<Block> sub) {
            this.line = line;
            this.execItems = execItems;
            this.sub = sub;
        }

        public int line() {
            return line;
        }

        boolean forceSubFunction() {
            return false;
        }

        void addContinuationCommand(McFunction f, McFunction execFunc) {
        }

        void prepareScratch(McFunction f) {
        }

        abstract String displayName();

        void compileElse(McFunction f) {
        }

        void performExecute(McFunction f) {
            prepareScratch(f);
            McFunction execFunc = f.createChildFunction();
            String items = f.getExecuteItems(execItems, execFunc);
            if (items == null) {
                throw new PyException("TypeError", "can only concatenate str (not \"NoneType\") to str");
            }
            String cmd = "execute " + items;
            compileContents(execFunc, sub, "Unable to compile " + displayName() + " block contents at line " + line);
            String single = execFunc.singleCommand();
            if (single == null || forceSubFunction()) {
                int unique = f.getUniqueId();
                String name = "line" + pad3(line) + "/" + displayName() + unique;
                f.registerFunction(name, execFunc);
                addContinuationCommand(f, execFunc);
                f.addCommand(cmd + "run " + execFunc.getCall());
            } else {
                if (single.startsWith("/")) {
                    single = single.substring(1);
                }
                boolean macro = false;
                if (single.startsWith("$")) {
                    single = single.substring(1);
                    macro = true;
                }
                if (single.startsWith("execute ")) {
                    cmd = cmd + single.substring("execute ".length());
                } else {
                    cmd = cmd + "run " + single;
                }
                if (macro) {
                    cmd = "$" + cmd;
                }
                f.addCommand(cmd);
            }
            compileElse(f);
        }
    }

    /** An else item: (execute items or None, lines). */
    record ElseItem(List<Ast.ExecItem> items, List<Block> sub) {
    }

    static final class ExecuteBlock extends ExecuteBase {
        final List<ElseItem> elseList;
        String scratch;

        ExecuteBlock(int line, List<Ast.ExecItem> execItems, List<Block> sub, List<ElseItem> elseList) {
            super(line, execItems, sub);
            this.elseList = elseList;
        }

        public void compile(McFunction f) {
            performExecute(f);
        }

        String displayName() {
            return "execute";
        }

        void addContinuationCommand(McFunction f, McFunction execFunc) {
            if (!elseList.isEmpty()) {
                f.addCommand("scoreboard players set Global " + scratch + " 1");
                execFunc.addCommand("scoreboard players set Global " + scratch + " 0");
            }
        }

        boolean forceSubFunction() {
            return !elseList.isEmpty();
        }

        void prepareScratch(McFunction f) {
            if (!elseList.isEmpty()) {
                scratch = f.getScratch();
            }
        }

        void compileElse(McFunction f) {
            if (elseList.isEmpty()) {
                return;
            }
            for (int idx = 0; idx < elseList.size(); idx++) {
                ElseItem item = elseList.get(idx);
                McFunction execFunc = f.createChildFunction();
                compileContents(execFunc, item.sub(), "Unable to compile else block contents at line " + displayName());
                String execText = item.items() == null ? "" : f.getExecuteItems(item.items(), execFunc);
                if (execText == null) {
                    throw new PyException("TypeError", "can only concatenate str (not \"NoneType\") to str");
                }
                String prefix = "execute if score Global " + scratch + " matches 1.. " + execText;
                if (idx < elseList.size() - 1) {
                    execFunc.addCommand("scoreboard players set Global " + scratch + " 0");
                }
                String single = execFunc.singleCommand();
                if (single == null) {
                    int unique = f.getUniqueId();
                    String name = "line" + pad3(line) + "/else" + unique;
                    f.registerFunction(name, execFunc);
                    f.addCommand(prefix + "run " + execFunc.getCall());
                } else {
                    if (single.startsWith("/")) {
                        single = single.substring(1);
                    }
                    String cmd = "";
                    if (single.startsWith("$")) {
                        single = single.substring(1);
                        cmd = "$";
                    }
                    if (single.startsWith("execute ")) {
                        cmd = cmd + prefix + single.substring("execute ".length());
                    } else {
                        cmd = cmd + prefix + "run " + single;
                    }
                    f.addCommand(cmd);
                }
            }
            f.freeScratch(scratch);
        }
    }

    static final class WhileBlock extends ExecuteBase {
        WhileBlock(int line, List<Ast.ExecItem> execItems, List<Block> sub) {
            super(line, execItems, sub);
        }

        public void compile(McFunction f) {
            performExecute(f);
        }

        boolean forceSubFunction() {
            return true;
        }

        void addContinuationCommand(McFunction f, McFunction execFunc) {
            McFunction dummy = execFunc.createChildFunction();
            String items = execFunc.getExecuteItems(execItems, dummy);
            if (items == null) {
                throw new PyException("TypeError", "can only concatenate str (not \"NoneType\") to str");
            }
            String subCmd = "execute " + items;
            if (subCmd.contains("$(")) {
                execFunc.hasMacros = true;
            }
            execFunc.addCommand(subCmd + "run " + execFunc.getCall());
        }

        String displayName() {
            return "while";
        }
    }

    record ForIndexBlock(int line, Var var, Expr fr, Expr to, Expr by, List<Block> sub) implements Block {
        public void compile(McFunction f) {
            Var fromVar = fr.compile(f, var.getAssignto(f));
            var.copyFrom(f, fromVar);
            ScoreboardVar toVar = to.compile(f, null).getScoreboardVar(f, null);
            Var byVar = null;
            BigInteger byConst = null;
            if (by != null) {
                byVar = by.compile(f, null);
                PyObject bc = byVar.getConstValue(f);
                if (bc == null) {
                    byVar = byVar.getScoreboardVar(f, null);
                } else {
                    byConst = McFunction.intOf(bc);
                }
            }
            int unique = f.getUniqueId();
            String loopName = "line" + pad3(line) + "/for" + pad3(unique);
            McFunction loop = f.createChildFunction();
            f.registerFunction(loopName, loop);
            if (var.usesMacro(f)) {
                loop.hasMacros = true;
            }
            if (toVar.usesMacro(f)) {
                loop.hasMacros = true;
            }
            if (by != null && byConst == null && byVar.usesMacro(f)) {
                loop.hasMacros = true;
            }
            try {
                loop.compileBlocks(sub);
            } catch (PyException e) {
                if (e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError("Unable to compile for block contents at line " + line);
                    });
                }
                throw Ctx.except(e, () -> {
                    // print(traceback.print_exc()): the traceback goes to stderr and None to stdout.
                    Ctx.print("None");
                    return Ctx.compileError("Unable to compile for block contents at line " + line);
                });
            }
            if (by == null) {
                ScoreboardVar temp = var.getScoreboardVar(f, null);
                String cont = "execute if score " + Ast.none(temp.getSelvar(f)) + " <= " + Ast.none(toVar.getSelvar(f)) + " run " + loop.getCall();
                f.addCommand(cont);
                loop.addCommand("scoreboard players add " + Ast.none(temp.getSelvar(f)) + " 1");
                var.copyFrom(f, temp);
                loop.addCommand(cont);
            } else {
                ScoreboardVar temp = var.getScoreboardVar(f, null);
                boolean truthy = byConst != null && byConst.signum() != 0;
                String cont = null;
                String neg = null;
                String pos = null;
                if (truthy) {
                    cont = "execute if score " + Ast.none(temp.getSelvar(f)) + " " + (byConst.signum() >= 0 ? "<=" : ">=") + " "
                            + Ast.none(toVar.getSelvar(f)) + " run " + loop.getCall();
                    f.addCommand(cont);
                    loop.addCommand("scoreboard players " + (byConst.signum() > 0 ? "add" : "remove") + " " + Ast.none(temp.getSelvar(f)) + " " + byConst.abs());
                } else {
                    if (byVar == null || byConst != null) {
                        // by_const is 0: Python takes the non-constant path with by_var still the constant var.
                        byVar = byVar == null ? Ast.NONE : byVar;
                    }
                    neg = "execute if score " + Ast.none(byVar.getSelvar(f)) + " matches ..-1 if score " + Ast.none(temp.getSelvar(f)) + " >= "
                            + Ast.none(toVar.getSelvar(f)) + " run " + loop.getCall();
                    pos = "execute if score " + Ast.none(byVar.getSelvar(f)) + " matches 1.. if score " + Ast.none(temp.getSelvar(f)) + " <= "
                            + Ast.none(toVar.getSelvar(f)) + " run " + loop.getCall();
                    f.addCommand(neg);
                    f.addCommand(pos);
                    loop.addCommand("scoreboard players operation " + Ast.none(temp.getSelvar(f)) + " += " + Ast.none(byVar.getSelvar(f)));
                }
                var.copyFrom(f, temp);
                if (truthy) {
                    loop.addCommand(cont);
                } else {
                    loop.addCommand(neg);
                    loop.addCommand(pos);
                }
            }
            toVar.freeScratch(f);
            fromVar.freeScratch(f);
            if (by != null) {
                byVar.freeScratch(f);
            }
        }
    }

    record ForSelectorBlock(int line, String id, String selector, List<Block> sub) implements Block {
        public void compile(McFunction f) {
            String scratchId = f.getScratch();
            McFunction exec = f.createChildFunction();
            SelectorDefinition combined = new SelectorDefinition(selector, f.environment);
            combined.scoresMin.put(scratchId, BigInteger.ONE);
            combined.setPart("limit", "1");
            exec.selectors().put(id, combined);
            exec.updateSelfSelector(selector);
            f.addCommand("scoreboard players set " + selector + " " + scratchId + " 0");
            exec.addCommand("scoreboard players set @s " + scratchId + " 1");
            compileContents(exec, sub, "Unable to compile for block contents at line " + line);
            exec.addCommand("scoreboard players set @s " + scratchId + " 0");
            f.freeScratch(scratchId);
            int unique = f.getUniqueId();
            f.registerFunction("for" + unique + "_ln" + line, exec);
            f.addCommand("execute as " + selector + " run " + exec.getCall());
        }
    }

    record PythonForBlock(int line, List<String> ids, Value val, List<Block> sub) implements Block {
        public void compile(McFunction f) {
            PyObject set = val.getValue(f);
            Iterator<PyObject> it;
            try {
                it = set.iter();
            } catch (PyException e) {
                throw Ctx.except(e, () -> Ctx.compileError("\"" + set.str() + "\" in \"for\" block at line " + line + " is not an iterable set."));
            }
            it = set.iter();
            while (it.hasNext()) {
                PyObject v = it.next();
                if (ids.size() == 1) {
                    f.setDollarid(ids.get(0), v);
                } else {
                    int n;
                    try {
                        n = v.len();
                    } catch (PyException e) {
                        if (!e.isA("Exception")) {
                            throw e;
                        }
                        throw Ctx.except(e, () -> {
                            Ctx.print(e.str());
                            return Ctx.compileError("Set is not a tuple at line " + line);
                        });
                    }
                    if (n != ids.size()) {
                        throw Ctx.compileError("Expected " + ids.size() + " tuple items at line " + line + ", got " + n + ".");
                    }
                    for (int i = 0; i < n; i++) {
                        f.setDollarid(ids.get(i), v.getitem(Py.of(i)));
                    }
                }
                try {
                    f.compileBlocks(sub);
                } catch (PyException e) {
                    if (e.isA("CompileError")) {
                        throw Ctx.except(e, () -> {
                            Ctx.print(e.str());
                            return Ctx.compileError("Unable to compile python for block contents at line " + line);
                        });
                    }
                    // `traceback` is not imported in python_for_block.py.
                    throw Ctx.except(e, () -> new PyException("NameError", "name 'traceback' is not defined"));
                }
            }
        }
    }

    record PythonIfBlock(int line, Value val, List<Block> sub, List<Block> elseSub) implements Block {
        public void compile(McFunction f) {
            PyObject condition = val.getValue(f);
            List<Block> body = condition.truthy() ? sub : elseSub;
            if (body == null) {
                return;
            }
            try {
                f.compileBlocks(body);
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> Ast.noAttr(pyTypeName(e), "message"));
            } catch (RuntimeException e) {
                PyException w = Ctx.wrap(e);
                throw Ctx.except(w, () -> Ast.noAttr(pyTypeName(w), "message"));
            }
        }
    }

    /** The class name an AttributeError on an exception object shows. */
    static String pyTypeName(PyException e) {
        return e.type;
    }

    record SwitchCase(String type, Object[] content, int line) {
    }

    record SwitchBlock(int line, Expr expr, List<SwitchCase> casesRaw) implements Block {
        public void compile(McFunction f) {
            if (casesRaw.isEmpty()) {
                return;
            }
            ScoreboardVar result = expr.compile(f, null).getScoreboardVar(f, null);
            List<McFunction.Case> cases = new ArrayList<>();
            int lastLine = 0;
            for (SwitchCase c : casesRaw) {
                int cline = c.line();
                lastLine = cline;
                if (c.type().equals("range")) {
                    Object[] content = c.content();
                    BigInteger vmin;
                    BigInteger vmax;
                    try {
                        vmin = McFunction.intOf(((Value) content[0]).getValue(f));
                        vmax = McFunction.intOf(((Value) content[1]).getValue(f));
                    } catch (PyException e) {
                        if (!e.isA("Exception")) {
                            throw e;
                        }
                        throw Ctx.except(e, () -> {
                            Ctx.print(e.str());
                            return new PyException("Exception", "Unable to get values of range for case at line " + cline);
                        });
                    }
                    @SuppressWarnings("unchecked")
                    List<Block> sub = (List<Block>) content[2];
                    cases.add(new McFunction.Case(vmin, vmax, sub, cline, null));
                } else {
                    Object[] content = c.content();
                    try {
                        ((Value) content[1]).getValue(f);
                    } catch (PyException e) {
                        throw Ctx.except(e, () -> new PyException("Exception", "Could not evaluate case value at line " + cline));
                    }
                    throw new PyException("AttributeError", "module 'collections' has no attribute 'Iterable'");
                }
            }
            cases.sort((a, b) -> a.vmin().compareTo(b.vmin()));
            BigInteger prevmax = cases.get(0).vmin().subtract(BigInteger.ONE);
            for (McFunction.Case c : cases) {
                if (c.vmin().compareTo(c.vmax()) > 0) {
                    throw new PyException("ValueError", "\"case " + c.vmin() + "-" + c.vmax() + "\" has invalid range at line " + c.line());
                }
                if (c.vmin().compareTo(prevmax) <= 0) {
                    String range = c.vmin().equals(c.vmax()) ? c.vmin().toString() : c.vmin() + "-" + c.vmax();
                    throw Ctx.compileError("\"case " + range + "\" overlaps another case at line " + c.line());
                }
                prevmax = c.vmax();
            }
            if (!f.switchCases(result, cases)) {
                throw new PyException("Exception", "Unable to compile switch block at line " + cases.get(cases.size() - 1).line());
            }
            result.freeScratch(f);
        }
    }

    record ScoreboardAssignmentBlock(int line, Var var, String op, Expr expr) implements Block {
        public void compile(McFunction f) {
            String o = op;
            Var assignto = o.equals("=") ? var.getAssignto(f) : null;
            Var exprVar = expr.compile(f, assignto);
            if (o.equals("=")) {
                var.copyFrom(f, exprVar);
            } else {
                ScoreboardVar temp = var.getScoreboardVar(f, null);
                PyObject c = exprVar.getConstValue(f);
                if (c != null && (o.equals("+=") || o.equals("-="))) {
                    if (Py.binop("<", c, Py.of(0)).truthy()) {
                        c = Py.neg(c);
                        o = o.equals("+=") ? "-=" : "+=";
                    }
                    f.addCommand("scoreboard players " + (o.equals("+=") ? "add" : "remove") + " " + temp.selector + " " + temp.objective + " " + c.str());
                } else {
                    ScoreboardVar sv = exprVar.getScoreboardVar(f, null);
                    f.addCommand("scoreboard players operation " + temp.selector + " " + temp.objective + " " + op + " " + sv.selector + " " + sv.objective);
                    sv.freeScratch(f);
                }
                var.copyFrom(f, temp);
                temp.freeScratch(f);
            }
            exprVar.freeScratch(f);
        }
    }

    /** vector_assignment_base (+ the vector and scalar subclasses). */
    record VectorAssignmentBlock(int line, Ast.VectorVar var, String op, Object expr, boolean scalar) implements Block {
        List<Var> computeAssignment(McFunction f, List<Var> assignto) {
            if (scalar) {
                Var v = ((Expr) expr).compile(f, null);
                return List.of(v, v, v);
            }
            List<Var> vals = ((Ast.VectorExpr) expr).compile(f, assignto);
            if (vals == null) {
                throw new PyException("Exception", "Unable to compute vector assignment at line " + line);
            }
            return vals;
        }

        public void compile(McFunction f) {
            List<Var> components;
            switch (var.type()) {
                case "VAR_COMPONENTS" -> {
                    @SuppressWarnings("unchecked")
                    List<Var> c = (List<Var>) var.content();
                    components = c;
                }
                case "VAR_ID" -> {
                    components = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                        components.add(new ScoreboardVar("Global", "_" + var.content() + "_" + i));
                    }
                }
                case "SEL_VAR_ID" -> {
                    String[] sv = (String[]) var.content();
                    components = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                        components.add(new ScoreboardVar(sv[0], "_" + sv[1] + "_" + i));
                    }
                }
                default -> throw Ctx.compileError("Cannot assign to vector constant at line " + line + ".");
            }
            if (op.equals("=")) {
                List<Var> assignto = new ArrayList<>();
                for (Var c : components) {
                    Var a = c.getAssignto(f);
                    if (a == null) {
                        assignto = null;
                        break;
                    }
                    assignto.add(a);
                }
                List<Var> vals = computeAssignment(f, assignto);
                for (int i = 0; i < 3; i++) {
                    components.get(i).copyFrom(f, vals.get(i));
                    vals.get(i).freeScratch(f);
                }
            } else {
                List<Var> vals = computeAssignment(f, null);
                if (var.type().equals("SEL_VAR_ID")) {
                    String[] sv = (String[]) var.content();
                    f.getVectorPath(sv[0], sv[1]);
                }
                for (int i = 0; i < 3; i++) {
                    ScoreboardVar temp = components.get(i).getScoreboardVar(f, null);
                    ScoreboardVar rv = vals.get(i).getScoreboardVar(f, null);
                    f.addCommand("scoreboard players operation " + temp.selector + " " + temp.objective + " " + op + " " + rv.selector + " " + rv.objective);
                    components.get(i).copyFrom(f, temp);
                    temp.freeScratch(f);
                    rv.freeScratch(f);
                }
            }
            if (var.type().equals("SEL_VAR_ID")) {
                String[] sv = (String[]) var.content();
                f.setVectorPath(sv[0], sv[1], components);
            }
        }
    }

    // ── calls ────────────────────────────────────────────────────────────────

    /** with_items: the `with $(x) = ...` prefix. */
    record WithItems(int line, List<Object[]> items) {
        void compile(McFunction f) {
            for (Object[] item : items) {
                String id = (String) item[1];
                if (item[0].equals("expr")) {
                    Var v = ((Expr) item[2]).compile(f, null);
                    PyObject c = v.getConstValue(f);
                    if (c != null) {
                        f.addCommand("data modify storage " + f.namespace() + ":global args." + id + " set value " + c.str());
                    } else {
                        f.addCommand("execute store result storage " + f.namespace() + ":global args." + id + " int 1 run scoreboard players get "
                                + v.selectorAttr() + " " + v.objectiveAttr());
                    }
                } else {
                    f.addCommand("data modify storage " + f.namespace() + ":global args." + id + " set value " + item[2]);
                }
            }
        }
    }

    static final class FunctionCallBlock implements Block {
        final int line;
        final String dest;
        final List<Expr> args;
        WithItems withMacroItems;

        FunctionCallBlock(int line, String dest, List<Expr> args, WithItems withMacroItems) {
            this.line = line;
            this.dest = dest;
            this.args = args;
            this.withMacroItems = withMacroItems;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            List<Var> locals = null;
            if (dest.equals(f.name())) {
                locals = f.getLocalVariables();
                f.pushLocals(locals);
            }
            if (withMacroItems != null) {
                withMacroItems.compile(f);
            }
            if (!f.evaluateParams(args)) {
                throw new PyException("Exception", "Unable to evaluate function call parameters at line " + line);
            }
            String cmd = dest.contains(":") ? "function " + dest : "function " + f.namespace() + ":" + dest;
            if (withMacroItems != null) {
                cmd += " with storage " + f.namespace() + ":global args";
                f.hasMacros = true;
            }
            f.addCommand(cmd);
            if (dest.equals(f.name())) {
                f.popLocals(locals);
            }
        }
    }

    static final class MethodCallBlock implements Block {
        final int line;
        final String selector;
        final String dest;
        final List<Expr> params;
        WithItems withMacroItems;

        MethodCallBlock(int line, String selector, String dest, List<Expr> params, WithItems withMacroItems) {
            this.line = line;
            this.selector = selector;
            this.dest = dest;
            this.params = params;
            this.withMacroItems = withMacroItems;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            if (withMacroItems != null) {
                withMacroItems.compile(f);
            }
            if (!f.evaluateParams(params)) {
                throw new PyException("Exception", "Unable to evaluate method call parameters at line " + line);
            }
            String cmd = selector.equals("@s") || selector.equals("@s[]")
                    ? "function " + f.namespace() + ":" + dest
                    : "execute as " + selector + " run function " + f.namespace() + ":" + dest;
            if (withMacroItems != null) {
                cmd += " with storage " + f.namespace() + ":global args";
            }
            f.addCommand(cmd);
        }
    }

    static final class TemplateFunctionCallBlock implements Block {
        final int line;
        final String function;
        final List<Value> templateArgs;
        final List<Expr> args;
        WithItems withMacroItems;

        TemplateFunctionCallBlock(int line, String function, List<Value> templateArgs, List<Expr> args, WithItems withMacroItems) {
            this.line = line;
            this.function = function;
            this.templateArgs = templateArgs;
            this.args = args;
            this.withMacroItems = withMacroItems;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            GlobalContext.Template t = f.globalContext().templateFunctions.get(function);
            if (t == null) {
                throw new PyException("ValueError", "Tried to call non-existant template function \"" + function + "\" at line " + line);
            }
            if (templateArgs.size() != t.macroParams().size()) {
                throw new PyException("ValueError", "Tried to call template function \"" + function + "\" with " + templateArgs.size()
                        + " template arguments (" + t.macroParams().size() + " expected) at line " + line);
            }
            if (args.size() != t.params().size()) {
                throw new PyException("ValueError", "Tried to call template function \"" + function + "\" with " + args.size()
                        + " function arguments (" + t.params().size() + " expected) at line " + line);
            }
            StringBuilder name = new StringBuilder(function);
            for (Value a : templateArgs) {
                name.append('_').append(a.getValue(f).str().replace(" ", "_"));
            }
            String funcName = name.toString();
            if (funcName.equals(f.name())) {
                throw Ast.noAttr("mcfunction", "get_all_locals");
            }
            if (withMacroItems != null) {
                withMacroItems.compile(f);
            }
            for (int i = 0; i < args.size(); i++) {
                ScoreboardVar assignto = new ScoreboardVar("Global", "Param{i}");
                Var arg = args.get(i).compile(f, assignto);
                assignto.copyFrom(f, arg);
                arg.freeScratch(f);
            }
            if (!f.globalContext().functions.containsKey(funcName)) {
                f.globalContext().functions.put(funcName, null);
                Environment env = f.cloneEnvironment(null);
                for (int p = 0; p < templateArgs.size(); p++) {
                    env.setDollarid(t.macroParams().get(p), templateArgs.get(p).getValue(f));
                }
                McFunction nf = new McFunction(env, true, t.params());
                try {
                    nf.compileBlocks(t.lines());
                } catch (PyException e) {
                    if (!e.isA("Exception")) {
                        throw e;
                    }
                    throw Ctx.except(e, () -> Ast.noAttr(pyTypeName(e), "message"));
                }
                f.registerFunction(funcName, nf);
            }
            String cmd = "function " + f.namespace() + ":" + funcName;
            if (withMacroItems != null) {
                cmd = cmd + "with storage " + f.namespace() + ":global args";
            }
            f.addCommand(cmd);
        }
    }

    record MacroCallBlock(int line, String macro, List<Value> args) implements Block {
        public void compile(McFunction f) {
            GlobalContext.Macro m = f.globalContext().macros.get(macro);
            if (m == null) {
                throw Ctx.compileError("Line " + line + ": macro \"" + macro + "\" does not exist");
            }
            if (args.size() != m.params().size()) {
                PyException e = new PyException("NameError", "name 'macro' is not defined");
                e.tracebackNote = ". Did you mean: 'self.macro'?";
                throw e;
            }
            Environment env = f.cloneEnvironment(null);
            for (int p = 0; p < m.params().size(); p++) {
                env.setDollarid(m.params().get(p), args.get(p).getValue(f));
            }
            f.pushEnvironment(env);
            compileContents(f, m.lines(), "Unable to compile macro contents at line " + line);
            f.popEnvironment();
        }
    }

    record WithAnonymousBlock(int line, WithItems withItems, List<Block> sub) implements Block {
        public void compile(McFunction f) {
            if (withItems != null) {
                withItems.compile(f);
            }
            McFunction anon = f.createChildFunction();
            compileContents(anon, sub, "Unable to compile with block contents at line " + line);
            int unique = f.getUniqueId();
            f.registerFunction("line" + pad3(line) + "/with" + pad3(unique), anon);
            f.addCommand(anon.getCall());
        }
    }

    // ── block switches ───────────────────────────────────────────────────────

    /** block_switch_base. */
    abstract static class BlockSwitchBase implements Block {
        final int line;
        final List<Ast.BlockCase> cases;
        final boolean includeBlockStates;
        Ast.BlockCase defaultCase;
        Map<String, Ast.BlockCase> blockStateList = new LinkedHashMap<>();
        Map<String, List<String>> blockList = new LinkedHashMap<>();
        Map<String, Object> blockStateIds = new LinkedHashMap<>();
        Map<Object, String> idBlockStates = new LinkedHashMap<>();
        Map<String, String> fallingBlockNbt = new LinkedHashMap<>();
        Map<String, Object> blocks;

        BlockSwitchBase(int line, List<Ast.BlockCase> cases, boolean includeBlockStates) {
            this.line = line;
            this.cases = cases;
            this.includeBlockStates = includeBlockStates;
            for (Ast.BlockCase c : cases) {
                if (c.isDefault) {
                    if (defaultCase != null) {
                        throw Ctx.compileError("Block switch at line " + line + " has multiple default cases.");
                    }
                    defaultCase = c;
                }
            }
        }

        public int line() {
            return line;
        }

        void compileInitialization(McFunction f) {
        }

        abstract void compileBlockCase(McFunction f, Object id);

        abstract String getRangeCondition(McFunction f, List<Object> ids);

        abstract List<Object> getCaseIds();

        public void compile(McFunction f) {
            compileInitialization(f);
            blocks = f.globalContext().getBlockStateList(includeBlockStates);
            getBlockStateList(f, blocks);
            List<Object> caseIds = getCaseIds();
            if (defaultCase != null) {
                compileBlockCases(f, caseIds);
            } else {
                McFunction sw = f.createChildFunction();
                compileBlockCases(sw, caseIds);
                f.callFunction(sw, "line" + pad3(line) + "/switch_block", "execute if " + getRangeCondition(f, caseIds) + " run ");
            }
        }

        static List<List<Object>> quartiles(List<Object> l) {
            int size = l.size();
            return List.of(l.subList(0, size / 4), l.subList(size / 4, size / 2), l.subList(size / 2, size * 3 / 4),
                    l.subList(size * 3 / 4, size));
        }

        void compileBlockCases(McFunction f, List<Object> blockCases) {
            for (List<Object> q : quartiles(blockCases)) {
                if (q.isEmpty()) {
                    continue;
                }
                if (q.size() == 1) {
                    compileBlockCase(f, q.get(0));
                } else {
                    McFunction range = f.createChildFunction();
                    compileBlockCases(range, new ArrayList<>(q));
                    f.callFunction(range, "line" + pad3(line) + "/switch_" + q.get(0).toString().replace("minecraft:", "") + "-"
                            + q.get(q.size() - 1).toString().replace("minecraft:", ""),
                            "execute if " + getRangeCondition(f, new ArrayList<>(q)) + " run ");
                }
            }
        }

        @SuppressWarnings("unchecked")
        static String blockStateName(String block, Map<String, Object> state) {
            if (state.containsKey("properties")) {
                Map<String, Object> props = (Map<String, Object>) state.get("properties");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    parts.add(e.getKey() + "=" + pyStr(e.getValue()));
                }
                return block + "[" + String.join(",", parts) + "]";
            }
            return block;
        }

        @SuppressWarnings("unchecked")
        static String fallingBlockNbt(String block, Map<String, Object> state) {
            if (state.containsKey("properties")) {
                Map<String, Object> props = (Map<String, Object>) state.get("properties");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    parts.add(e.getKey() + ":\"" + pyStr(e.getValue()) + "\"");
                }
                return "Name:\"" + block + "\",Properties:{" + String.join(",", parts) + "}";
            }
            return "Name:\"" + block + "\"";
        }

        static String pyStr(Object v) {
            return v instanceof String s ? s : String.valueOf(v);
        }

        @SuppressWarnings("unchecked")
        void getBlockStateList(McFunction f, Map<String, Object> blocks) {
            blockStateList = new LinkedHashMap<>();
            blockList = new LinkedHashMap<>();
            for (Map.Entry<String, Object> b : blocks.entrySet()) {
                String block = b.getKey();
                for (Object so : (List<Object>) ((Map<String, Object>) b.getValue()).get("states")) {
                    Map<String, Object> state = (Map<String, Object>) so;
                    String bs = blockStateName(block, state);
                    Ast.BlockCase c = getMatchingCase(f, block, state);
                    if (c != null) {
                        blockStateList.put(bs, c);
                        blockList.computeIfAbsent(block, k -> new ArrayList<>()).add(bs);
                        blockStateIds.put(bs, state.get("id"));
                        fallingBlockNbt.put(bs, fallingBlockNbt(block, state));
                    }
                }
            }
            idBlockStates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : blockStateIds.entrySet()) {
                idBlockStates.put(e.getValue(), e.getKey());
            }
        }

        Ast.BlockCase getMatchingCase(McFunction f, String block, Map<String, Object> state) {
            for (Ast.BlockCase c : cases) {
                if (!c.isDefault && c.matches(block, state)) {
                    return c;
                }
                if (!c.isDefault) {
                    List<String> tag = f.globalContext().blockTags.get(c.blockName);
                    if (tag != null && tag.contains(block.replace("minecraft:", ""))) {
                        return c;
                    }
                }
            }
            return defaultCase;
        }
    }

    static final class BlockSwitchBlock extends BlockSwitchBase {
        final Coords coords;

        BlockSwitchBlock(int line, Coords coords, List<Ast.BlockCase> cases, boolean includeBlockStates) {
            super(line, cases, includeBlockStates);
            this.coords = coords;
        }

        String caseCondition(McFunction f, String blockState) {
            return "block " + coords.getValue(f) + " " + blockState;
        }

        @SuppressWarnings("unchecked")
        void compileBlockCase(McFunction f, Object blockObj) {
            String block = (String) blockObj;
            Ast.BlockCase single = getMatchingCase(f, block, Map.of());
            Map<String, Object> info = (Map<String, Object>) blocks.get(block);
            String shortName = block.replace("minecraft:", "");
            if (single != null && !single.isDefault) {
                String blockState = block;
                if (info.containsKey("properties")) {
                    Map<String, Object> props = (Map<String, Object>) info.get("properties");
                    List<String> parts = new ArrayList<>();
                    for (Map.Entry<String, Object> e : props.entrySet()) {
                        parts.add(e.getKey() + "=" + pyStr(((List<Object>) e.getValue()).get(0)));
                    }
                    blockState += "[" + String.join(",", parts) + "]";
                }
                compileSingleCase(f, block, block, blockState, "line" + pad3(line) + "/case_" + shortName);
            } else if (info.containsKey("properties")) {
                McFunction caseFunc = f.createChildFunction();
                Map<String, Object> props = (Map<String, Object>) info.get("properties");
                compilePropertyCase(caseFunc, block, new ArrayList<>(props.keySet()), props, block + "[");
                f.callFunction(caseFunc, "line" + pad3(line) + "/switch_" + shortName + "/" + shortName,
                        "execute if block " + coords.getValue(f) + " " + block + " run ");
            } else if (defaultCase != null) {
                compileSingleCase(f, block, block, block, "line" + pad3(line) + "/case_" + shortName);
            }
        }

        @SuppressWarnings("unchecked")
        void compilePropertyCase(McFunction f, String block, List<String> propNames, Map<String, Object> props, String partial) {
            if (propNames.isEmpty()) {
                throw new PyException("TypeError", "'dict_keys' object is not subscriptable");
            }
            // Python indexes props.keys() (a dict_keys view), which is not subscriptable.
            throw new PyException("TypeError", "'dict_keys' object is not subscriptable");
        }

        void compileSingleCase(McFunction f, String block, String blockTest, String blockState, String caseFuncName) {
            Object id = blockStateIds.get(blockState);
            Ast.BlockCase c = blockStateList.get(blockState);
            String nbt = fallingBlockNbt.get(blockState);
            if (id == null || c == null) {
                throw new PyException("KeyError", PyException.reprString(blockState));
            }
            McFunction caseFunc = f.createChildFunction();
            try {
                c.compile(block.replace("minecraft:", ""), blockState, id, caseFunc, nbt);
            } catch (PyException e) {
                if (e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError("Unable to compile block switch case \"" + blockState + "\" at line " + line);
                    });
                }
                throw e;
            }
            f.callFunction(caseFunc, caseFuncName, "execute if " + caseCondition(f, blockTest) + " run ");
        }

        String getRangeCondition(McFunction f, List<Object> blocks) {
            int unique = f.getUniqueId();
            String tag = "switch_" + unique;
            List<String> names = new ArrayList<>();
            for (Object b : blocks) {
                names.add(((String) b).replace("minecraft:", ""));
            }
            f.globalContext().registerBlockTag(tag, names);
            return "block " + coords.getValue(f) + " #" + f.namespace() + ":" + tag;
        }

        List<Object> getCaseIds() {
            List<Object> ids = new ArrayList<>(blockList.keySet());
            ids.sort((a, b) -> Environment.compareCodePoints((String) a, (String) b));
            return ids;
        }
    }

    static final class BlockIdSwitchBlock extends BlockSwitchBase {
        final Expr expr;
        ScoreboardVar conditionVar;

        BlockIdSwitchBlock(int line, Expr expr, List<Ast.BlockCase> cases, boolean includeBlockStates) {
            super(line, cases, includeBlockStates);
            this.expr = expr;
        }

        void compileInitialization(McFunction f) {
            try {
                Var v = expr.compile(f, null);
                conditionVar = v.getScoreboardVar(f, null);
            } catch (PyException e) {
                if (e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError("Unable to compile switch expression at line " + line + ".");
                    });
                }
                throw e;
            }
        }

        String caseCondition(Object id) {
            return "score " + conditionVar.selector + " " + conditionVar.objective + " matches " + id;
        }

        void compileBlockCase(McFunction f, Object id) {
            McFunction caseFunc = f.createChildFunction();
            String blockState = idBlockStates.get(id);
            if (blockState == null) {
                throw new PyException("KeyError", String.valueOf(id));
            }
            if (!blockStateList.containsKey(blockState)) {
                return;
            }
            Ast.BlockCase c = blockStateList.get(blockState);
            String nbt = fallingBlockNbt.get(blockState);
            String block = blockState.split("\\[", -1)[0].replace("minecraft:", "");
            try {
                c.compile(block, blockState, id, caseFunc, nbt);
            } catch (PyException e) {
                if (e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError("Unable to compile block switch at line " + line);
                    });
                }
                throw e;
            }
            f.callFunction(caseFunc, "line" + pad3(line) + "/case" + id, "execute if " + caseCondition(id) + " run ");
        }

        String getRangeCondition(McFunction f, List<Object> ids) {
            return "score " + conditionVar.selector + " " + conditionVar.objective + " matches " + ids.get(0) + ".." + ids.get(ids.size() - 1);
        }

        List<Object> getCaseIds() {
            List<Object> ids = new ArrayList<>(idBlockStates.keySet());
            ids.sort((a, b) -> ((BigInteger) a).compareTo((BigInteger) b));
            return ids;
        }
    }
}
