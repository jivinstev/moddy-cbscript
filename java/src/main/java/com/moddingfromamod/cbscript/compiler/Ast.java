// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyFloat;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyObject;
import com.moddingfromamod.cbscript.py.PyStr;

/**
 * The parsed program: variable_types/, scalar_expressions/, vector_expressions/, data_types/ and nbt_types/,
 * class for class. (block_types/ is {@link Blocks}.) Python's duck typing shows through where it matters for
 * parity: a method a Python class lacks raises the AttributeError Python would.
 */
public final class Ast {
    private Ast() {
    }

    /** A value Python formats with str(): None prints as "None". */
    static String none(String s) {
        return s == null ? "None" : s;
    }

    static PyException noAttr(String pyClass, String attr) {
        return new PyException("AttributeError", "'" + pyClass + "' object has no attribute '" + attr + "'");
    }

    // ── interfaces ───────────────────────────────────────────────────────────

    /** A block_types/ object. */
    public interface Block {
        int line();

        void compile(McFunction func);

        default void register(GlobalContext gc) {
        }
    }

    /** Anything with get_value(func) returning a Python value (data_types/). */
    public interface Value {
        PyObject getValue(McFunction func);
    }

    /** relcoord / relcoords / relcoord_vector: get_value(func) returning text. */
    public interface Coords {
        String getValue(McFunction func);
    }

    /** An expression: compile(func, assignto) gives a variable. */
    public interface Expr {
        String pyName();

        Var compile(McFunction func, Var assignto);

        default boolean referencesScoreboardVar(McFunction func, Var var) {
            throw noAttr(pyName(), "references_scoreboard_var");
        }
    }

    /** var_base and its subclasses. Defaults are var_base's. */
    public interface Var extends Expr {
        default ScoreboardVar getScoreboardVar(McFunction func, Var assignto) {
            throw new PyException("NotImplementedError", "");
        }

        default String getCommand(McFunction func) {
            throw new PyException("NotImplementedError", "");
        }

        default PyObject getConstValue(McFunction func) {
            return null;
        }

        default boolean isObjective(McFunction func, String selector, String objective) {
            return false;
        }

        default Var getAssignto(McFunction func) {
            return null;
        }

        default void copyFrom(McFunction func, Var var) {
            throw new PyException("NotImplementedError", "");
        }

        default Var getModifiableVar(McFunction func, Var assignto) {
            throw new PyException("NotImplementedError", "");
        }

        default void freeScratch(McFunction func) {
        }

        default String getSelvar(McFunction func) {
            return null;
        }

        @Override
        default boolean referencesScoreboardVar(McFunction func, Var var) {
            return false;
        }

        @Override
        default Var compile(McFunction func, Var assignto) {
            return this;
        }

        default boolean usesMacro(McFunction func) {
            throw noAttr(pyName(), "uses_macro");
        }

        /** The {@code .selector} attribute. */
        default String selectorAttr() {
            throw noAttr(pyName(), "selector");
        }

        /** The {@code .objective} attribute. */
        default String objectiveAttr() {
            throw noAttr(pyName(), "objective");
        }

        /** {@code .selvar} property. */
        default String selvar() {
            throw noAttr(pyName(), "selvar");
        }
    }

    /** Python None where a variable was expected: every use is an AttributeError on 'NoneType'. */
    public static final Var NONE = new Var() {
        public String pyName() {
            return "NoneType";
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            throw noAttr("NoneType", "get_scoreboard_var");
        }

        public String getCommand(McFunction f) {
            throw noAttr("NoneType", "get_command");
        }

        public PyObject getConstValue(McFunction f) {
            throw noAttr("NoneType", "get_const_value");
        }

        public boolean isObjective(McFunction f, String s, String o) {
            throw noAttr("NoneType", "is_objective");
        }

        public Var getAssignto(McFunction f) {
            throw noAttr("NoneType", "get_assignto");
        }

        public void copyFrom(McFunction f, Var v) {
            throw noAttr("NoneType", "copy_from");
        }

        public Var getModifiableVar(McFunction f, Var a) {
            throw noAttr("NoneType", "get_modifiable_var");
        }

        public void freeScratch(McFunction f) {
            throw noAttr("NoneType", "free_scratch");
        }

        public String getSelvar(McFunction f) {
            throw noAttr("NoneType", "get_selvar");
        }

        public boolean referencesScoreboardVar(McFunction f, Var v) {
            throw noAttr("NoneType", "references_scoreboard_var");
        }

        public Var compile(McFunction f, Var a) {
            throw noAttr("NoneType", "compile");
        }
    };

    /** A Python str where a variable was expected (array_expr_var passes its index name as assignto). */
    public static final class StrVar implements Var {
        final String s;

        StrVar(String s) {
            this.s = s;
        }

        public String pyName() {
            return "str";
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            throw noAttr("str", "get_scoreboard_var");
        }

        public String getCommand(McFunction f) {
            throw noAttr("str", "get_command");
        }

        public PyObject getConstValue(McFunction f) {
            throw noAttr("str", "get_const_value");
        }

        public boolean isObjective(McFunction f, String sel, String o) {
            throw noAttr("str", "is_objective");
        }

        public Var getAssignto(McFunction f) {
            throw noAttr("str", "get_assignto");
        }

        public void copyFrom(McFunction f, Var v) {
            throw noAttr("str", "copy_from");
        }

        public Var getModifiableVar(McFunction f, Var a) {
            throw noAttr("str", "get_modifiable_var");
        }

        public void freeScratch(McFunction f) {
            throw noAttr("str", "free_scratch");
        }

        public String getSelvar(McFunction f) {
            throw noAttr("str", "get_selvar");
        }

        public boolean referencesScoreboardVar(McFunction f, Var v) {
            throw noAttr("str", "references_scoreboard_var");
        }

        public Var compile(McFunction f, Var a) {
            throw noAttr("str", "compile");
        }
    }

    /** A str in an expression list (push of names). */
    public record StrExpr(String s) implements Expr {
        public String pyName() {
            return "str";
        }

        public Var compile(McFunction f, Var a) {
            throw noAttr("str", "compile");
        }
    }

    // ── conditions and execute items (tuples in Python) ─────────────────────

    public record Condition(String type, Object value) {
    }

    public record ScoreCondition(Expr lhs, String op, Expr rhs) {
    }

    public record BlockCondition(Coords coords, String block) {
    }

    /** ('VAR_ID', id) / ('SEL_VAR_ID', (sel, id)) / ('VAR_COMPONENTS', [v, v, v]) / ('VAR_CONST', value). */
    public record VectorVar(String type, Object content) {
    }

    public record ExecItem(String type, Object value) {
    }

    // ── data_types ───────────────────────────────────────────────────────────

    public record ConstNumber(PyObject val) implements Value {
        static ConstNumber of(PyObject v) {
            if (!Environment.isNumber(v)) {
                throw new PyException("Exception", "Non-numeric value \"" + v.str() + "\" for number.");
            }
            return new ConstNumber(Environment.isInt(v) ? Py.toInt(v) : Py.toFloat(v));
        }

        public PyObject getValue(McFunction f) {
            return val;
        }
    }

    public record ConstString(String val) implements Value {
        public PyObject getValue(McFunction f) {
            return Py.of(val);
        }
    }

    /** interpreted_python: a Python expression evaluated against the $-ids. */
    public record InterpretedPython(String expr, int line) implements Value {
        public PyObject getValue(McFunction f) {
            return f.eval(expr, line);
        }
    }

    public record RelCoord(String type, Value coord) implements Coords {
        public String getValue(McFunction f) {
            return type + coord.getValue(f).str();
        }
    }

    public static final class RelCoords implements Coords {
        final Coords[] coords;

        public RelCoords() {
            this.coords = new Coords[] {
                    new RelCoord("~", new ConstString("")), new RelCoord("~", new ConstString("")),
                    new RelCoord("~", new ConstString(""))};
        }

        public RelCoords(Coords a, Coords b, Coords c) {
            this.coords = new Coords[] {a, b, c};
        }

        public String getValue(McFunction f) {
            List<String> out = new ArrayList<>();
            for (Coords c : coords) {
                out.add(c.getValue(f));
            }
            return String.join(" ", out);
        }
    }

    public record RelCoordVector(int line, String type, Value value) implements Coords {
        public String getValue(McFunction f) {
            PyObject coords = value.getValue(f);
            List<String> out = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    out.add(type + coords.getitem(Py.of(i)).str());
                }
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> Ctx.compileError("Unable to get three coordinates from constant value at line " + line + "."));
            }
            return String.join(" ", out);
        }
    }

    /** data_types/number_macro_path: one path of a [block] definition. */
    public static final class NumberMacroPath {
        final String id;
        final List<String> params;
        final String path;
        final String type;
        final Value scale;

        NumberMacroPath(String id, List<String> params, String path, String type, Value scale) {
            this.id = id;
            this.params = params;
            this.path = path;
            this.type = type;
            this.scale = scale;
        }

        PyObject getScale(McFunction f) {
            return scale == null ? f.getScale() : scale.getValue(f);
        }

        String getPath(McFunction f, List<Value> macroArgs) {
            if (macroArgs.size() != params.size()) {
                throw Ctx.compileError("\"[" + id + "]." + path + "\" expects " + params.size() + " macro arguments, received " + macroArgs.size() + ".");
            }
            Map<String, PyObject> overrides = new LinkedHashMap<>();
            for (int i = 0; i < macroArgs.size(); i++) {
                overrides.put(params.get(i), macroArgs.get(i).getValue(f));
            }
            return f.applyReplacements(path, overrides);
        }

        void copyFrom(McFunction f, Coords coords, List<Value> macroArgs, Var var) {
            PyObject c = var.getConstValue(f);
            if (c != null && c.truthy()) {
                f.addCommand("data modify block " + coords.getValue(f) + " " + getPath(f, macroArgs) + " set value "
                        + Py.binop("/", Py.toFloat(c), Py.toFloat(getScale(f))).str());
            } else {
                f.addCommand("execute store result block " + coords.getValue(f) + " " + getPath(f, macroArgs) + " " + type + " "
                        + McFunction.inverse(getScale(f)) + " run " + var.getCommand(f));
            }
        }

        String getCommand(McFunction f, Coords coords, List<Value> macroArgs) {
            return "data get block " + coords.getValue(f) + " " + getPath(f, macroArgs) + " " + getScale(f).str();
        }
    }

    /** data_types/block_case. */
    public static final class BlockCase {
        final String blockName;
        final List<String[]> props;
        final List<Block> lines;
        final boolean isDefault;

        BlockCase(String blockName, List<String[]> props, List<Block> lines, boolean isDefault) {
            this.blockName = blockName;
            this.props = props;
            this.lines = lines;
            this.isDefault = isDefault;
        }

        @SuppressWarnings("unchecked")
        boolean matches(String block, Map<String, Object> state) {
            String name = blockName;
            if (!name.equals("*")) {
                if (!name.startsWith("minecraft:")) {
                    name = "minecraft:" + name;
                }
                if (!name.equals(block)) {
                    return false;
                }
            }
            if (!props.isEmpty()) {
                if (!state.containsKey("properties")) {
                    return false;
                }
                Map<String, Object> bp = (Map<String, Object>) state.get("properties");
                for (String[] p : props) {
                    if (!bp.containsKey(p[0])) {
                        return false;
                    }
                    if (!(bp.get(p[0]) instanceof String s && s.equals(p[1]))) {
                        return false;
                    }
                }
            }
            return true;
        }

        void compile(String block, String blockState, Object blockId, McFunction f, String fallingBlockNbt) {
            f.setDollarid("block_name", Py.of(block));
            f.setDollarid("block_state", Py.of(blockState));
            f.setDollarid("block_id", Py.of((BigInteger) blockId));
            f.setDollarid("falling_block_nbt", Py.of(fallingBlockNbt));
            f.compileBlocks(lines);
        }
    }

    /** data_types/shaped_recipe. */
    public static final class ShapedRecipe {
        final List<String> recipeLines;
        final List<String[]> keys;
        final PyObject count;
        final String item;
        final String group;

        ShapedRecipe(List<String> recipeLines, List<String[]> keys, PyObject count, String item, String group) {
            this.recipeLines = recipeLines;
            this.keys = keys;
            this.count = count;
            this.item = item;
            this.group = group;
        }

        Map<String, Object> getJsonStruct() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "minecraft:crafting_shaped");
            s.put("group", group);
            s.put("pattern", recipeLines);
            Map<String, Object> key = new LinkedHashMap<>();
            for (String[] k : keys) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put(k[1], k[2]);
                key.put(k[0], one);
            }
            s.put("key", key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("item", "minecraft:" + item);
            result.put("count", count);
            s.put("result", result);
            return s;
        }

        String getType() {
            return "shaped";
        }
    }

    // ── nbt_types ────────────────────────────────────────────────────────────

    public interface NbtPath {
        String getDestPath(McFunction f);

        String getSourcePath(McFunction f);
    }

    public interface NbtSource {
        String getSourcePath(McFunction f);
    }

    public record EntityNbtPath(String selector, String path) implements NbtPath, NbtSource {
        public String getDestPath(McFunction f) {
            return "entity " + selector + " " + path;
        }

        public String getSourcePath(McFunction f) {
            return "from " + getDestPath(f);
        }
    }

    public record BlockNbtPath(Coords coords, String path) implements NbtPath, NbtSource {
        public String getDestPath(McFunction f) {
            return "block " + coords.getValue(f) + " " + path;
        }

        public String getSourcePath(McFunction f) {
            return "from " + getDestPath(f);
        }
    }

    public record StorageNbtPath(String target, String path) implements NbtPath, NbtSource {
        public String getDestPath(McFunction f) {
            return "storage " + (target == null ? f.namespace() : target) + " " + path;
        }

        public String getSourcePath(McFunction f) {
            return "from " + getDestPath(f);
        }
    }

    public record NbtJson(String json) implements NbtSource {
        public String getSourcePath(McFunction f) {
            return "value " + json;
        }
    }

    // ── variable_types ───────────────────────────────────────────────────────

    public static final class ScoreboardVar implements Var {
        public final String selector;
        public final String objective;

        public ScoreboardVar(String selector, String objective) {
            this.selector = selector;
            this.objective = objective;
        }

        public String pyName() {
            return "scoreboard_var";
        }

        public String selectorAttr() {
            return selector;
        }

        public String objectiveAttr() {
            return objective;
        }

        SelectorDefinition.Path getPath(McFunction f) {
            SelectorDefinition seldef = selector.startsWith("@s") ? f.getSelfSelectorDefinition()
                    : f.environment.getSelectorDefinition(selector);
            if (seldef != null && seldef.paths.containsKey(objective)) {
                return seldef.paths.get(objective);
            }
            return null;
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var assignto) {
            SelectorDefinition.Path pathData = getPath(f);
            if (pathData != null) {
                if (assignto == null) {
                    assignto = new ScoreboardVar("Global", f.getScratch());
                }
                assignto.copyFrom(f, this);
                return (ScoreboardVar) assignto;
            }
            f.registerObjective(objective);
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                return new ScoreboardVar(nameDef, objective);
            }
            return this;
        }

        public Var compile(McFunction f, Var assignto) {
            String nameDef = f.getNameDefinition(selector);
            return nameDef != null ? new ScoreboardVar(nameDef, objective) : this;
        }

        public String getCommand(McFunction f) {
            SelectorDefinition.Path p = getPath(f);
            if (p != null) {
                return "data get entity " + selector + " " + p.path() + " " + p.scale().str();
            }
            f.registerObjective(objective);
            String sel = selector;
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                sel = nameDef;
            }
            return "scoreboard players get " + sel + " " + objective;
        }

        String setCommand(McFunction f) {
            SelectorDefinition.Path p = getPath(f);
            if (p != null) {
                return "execute store result entity " + selector + " " + p.path() + " " + p.dataType() + " " + McFunction.inverse(p.scale());
            }
            f.registerObjective(objective);
            String sel = selector;
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                sel = nameDef;
            }
            return "execute store result score " + sel + " " + objective;
        }

        public boolean isObjective(McFunction f, String sel, String obj) {
            SelectorDefinition.Path p = getPath(f);
            String my = selector;
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                my = nameDef;
            }
            return p == null && my.equals(sel) && objective.equals(obj);
        }

        boolean sameAs(McFunction f, Var var) {
            if (var == null) {
                return false;
            }
            String my = selector;
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                my = nameDef;
            }
            return var.isObjective(f, my, objective);
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return sameAs(f, var);
        }

        boolean isFastSelector() {
            return !selector.startsWith("@") || selector.equals("@s");
        }

        public Var getAssignto(McFunction f) {
            if (getPath(f) == null && isFastSelector()) {
                f.registerObjective(objective);
                return this;
            }
            return null;
        }

        static final Map<String, String> SUFFIX = Map.of("byte", "b", "short", "s", "int", "", "long", "L", "float", "f", "double", "d");

        public void copyFrom(McFunction f, Var var) {
            SelectorDefinition.Path p = getPath(f);
            PyObject c = var.getConstValue(f);
            if (p != null) {
                if (c != null) {
                    String suffix = SUFFIX.get(p.dataType());
                    if (suffix == null) {
                        throw new PyException("KeyError", PyException.reprString(p.dataType()));
                    }
                    PyObject val;
                    if (!p.dataType().equals("float") && !p.dataType().equals("double")) {
                        val = Py.toInt(Py.binop("/", c, p.scale()));
                    } else {
                        val = Py.binop("/", Py.toFloat(c), Py.toFloat(p.scale()));
                    }
                    f.addCommand("data modify entity " + selector + " " + p.path() + " set value " + val.str() + suffix);
                } else {
                    f.addCommand(setCommand(f) + " run " + var.getCommand(f));
                }
                return;
            }
            f.registerObjective(objective);
            String sel = selector;
            String nameDef = f.getNameDefinition(selector);
            if (nameDef != null) {
                sel = nameDef;
            }
            if (c != null) {
                f.addCommand("scoreboard players set " + sel + " " + objective + " " + c.str());
            } else if (!var.isObjective(f, sel, objective)) {
                String sv = var.getSelvar(f);
                if (sv == null) {
                    f.addCommand(setCommand(f) + " run " + var.getCommand(f));
                } else {
                    f.addCommand("scoreboard players operation " + sel + " " + objective + " = " + sv);
                }
            }
        }

        public Var getModifiableVar(McFunction f, Var assignto) {
            if (getPath(f) != null) {
                return getScoreboardVar(f, assignto);
            }
            f.registerObjective(objective);
            if (selector.equals("Global") && f.isScratch(objective)) {
                return this;
            } else if (sameAs(f, assignto)) {
                return this;
            }
            ScoreboardVar m = new ScoreboardVar("Global", f.getScratch());
            m.copyFrom(f, this);
            return m;
        }

        public void freeScratch(McFunction f) {
            f.freeScratch(objective);
        }

        public boolean usesMacro(McFunction f) {
            return f.getNameDefinition(selector) != null || selector.contains("$(");
        }

        public String getSelvar(McFunction f) {
            if (getPath(f) != null) {
                return null;
            }
            f.registerObjective(objective);
            String nameDef = f.getNameDefinition(selector);
            return nameDef != null ? nameDef + " " + objective : selector + " " + objective;
        }

        public String selvar() {
            return selector + " " + objective;
        }

        /** get_selvar on a known scoreboard var (Python calls it with func). */
        String selvar(McFunction f) {
            return getSelvar(f);
        }
    }

    /** var_base.get_modifiable_var shared by the array/scale/virtual-int vars: copy into a scratch. */
    static Var scratchCopy(McFunction f, Var self) {
        ScoreboardVar s = new ScoreboardVar("Global", f.getScratch());
        s.copyFrom(f, self);
        return s;
    }

    public static final class ScaleVar implements Var {
        public String pyName() {
            return "scale_var";
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            return new ScoreboardVar(f.addConstant(f.getScale()), "Constant");
        }

        public String getCommand(McFunction f) {
            return "scoreboard players get Global " + f.addConstant(f.getScale());
        }

        public PyObject getConstValue(McFunction f) {
            return f.getScale();
        }

        public void copyFrom(McFunction f, Var v) {
            throw Ctx.compileError("Cannot set the value of the scale variable.");
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }
    }

    public static final class VirtualIntVar implements Var {
        final Object val;  // a str (token text) or a Python int

        public VirtualIntVar(Object val) {
            this.val = val;
        }

        public String pyName() {
            return "virtualint_var";
        }

        PyObject getValue(McFunction f) {
            String text = val instanceof String s ? s : ((PyObject) val).str();
            return Py.toInt(Py.of(f.applyReplacements(text)));
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            return new ScoreboardVar(f.addConstant(getValue(f)), "Constant");
        }

        public String getCommand(McFunction f) {
            return "scoreboard players get " + f.addConstant(getValue(f)) + " Constant";
        }

        public PyObject getConstValue(McFunction f) {
            return getValue(f);
        }

        public void copyFrom(McFunction f, Var v) {
            throw Ctx.compileError("Cannot set the value of a constant.");
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }
    }

    public static final class ArrayConstVar implements Var {
        final String selector;
        final String array;
        final String idx;

        ArrayConstVar(String selector, String array, String idx) {
            this.selector = selector;
            this.array = array;
            this.idx = idx;
        }

        public String pyName() {
            return "array_const_var";
        }

        public String selectorAttr() {
            return selector;
        }

        void checkArray(McFunction f) {
            String idxStr;
            BigInteger val;
            try {
                idxStr = f.applyReplacements(idx);
                val = McFunction.intOf(Py.of(idxStr));
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    String shown;
                    try {
                        shown = f.applyReplacements(idx);
                    } catch (PyException e2) {
                        return new PyException("UnboundLocalError",
                                "cannot access local variable 'idx_str' where it is not associated with a value");
                    }
                    return Ctx.compileError("\"" + shown + "\" is not an integer for array \"" + array + "\" index");
                });
            }
            GlobalContext.ArrayDef def = f.globalContext().arrays.get(array);
            if (def == null) {
                throw Ctx.compileError("Tried to use undefined array " + array);
            }
            if (val.compareTo(def.from()) < 0 || val.compareTo(def.to()) > 0) {
                throw Ctx.compileError("Index " + val + " for \"array " + array + "[" + def.from() + " to " + def.to() + "]\" is out of bounds.");
            }
            if (def.selectorBased() && selector.equals("Global")) {
                throw Ctx.compileError("Tried to use selector-based array " + array + " without a selector.");
            }
            if (!def.selectorBased() && !selector.equals("Global")) {
                throw Ctx.compileError("Tried to use global array " + array + " with a selector.");
            }
        }

        String getObjective(McFunction f) {
            checkArray(f);
            return array + f.applyReplacements(idx);
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            return new ScoreboardVar(selector, getObjective(f));
        }

        public String getCommand(McFunction f) {
            return "scoreboard players get " + selector + " " + getObjective(f);
        }

        public boolean isObjective(McFunction f, String sel, String obj) {
            return sel.equals(selector) && obj.equals(getObjective(f));
        }

        public Var getAssignto(McFunction f) {
            return new ScoreboardVar("Global", getObjective(f));
        }

        public void copyFrom(McFunction f, Var var) {
            PyObject c = var.getConstValue(f);
            if (c != null) {
                f.addCommand("scoreboard players set " + selector + " " + getObjective(f) + " " + c.str());
            } else {
                f.addCommand("execute store result score " + selector + " " + getObjective(f) + " run " + var.getCommand(f));
            }
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return getScoreboardVar(f, null).referencesScoreboardVar(f, var);
        }
    }

    public static final class ArrayExprVar implements Var {
        final String selector;
        final String array;
        final Expr idxExpr;

        ArrayExprVar(String selector, String array, Expr idxExpr) {
            this.selector = selector;
            this.array = array;
            this.idxExpr = idxExpr;
        }

        public String pyName() {
            return "array_expr_var";
        }

        public String selectorAttr() {
            return selector;
        }

        void checkDefined(McFunction f) {
            GlobalContext.ArrayDef def = f.globalContext().arrays.get(array);
            if (def == null) {
                Ctx.print("Tried to use undefined array \"" + array + "\"");
                return;
            }
            if (def.selectorBased() && selector.equals("Global")) {
                throw new PyException("NameError", "name 'CompileError' is not defined");
            }
            if (!def.selectorBased() && !selector.equals("Global")) {
                throw new PyException("NameError", "name 'CompileError' is not defined");
            }
        }

        private String prefix() {
            return selector.equals("@s") || !selector.startsWith("@") ? "" : "execute as " + selector + " run ";
        }

        void evaluate(McFunction f) {
            checkDefined(f);
            String indexName = array + "Idx";
            Var exprVar = idxExpr.compile(f, new StrVar(indexName));
            ScoreboardVar indexVar = new ScoreboardVar(selector, indexName);
            indexVar.copyFrom(f, exprVar);
            f.addCommand(prefix() + "function " + f.namespace() + ":array_" + array.toLowerCase(java.util.Locale.ROOT) + "_get");
            exprVar.freeScratch(f);
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            evaluate(f);
            return new ScoreboardVar(selector, array + "Val");
        }

        public String getCommand(McFunction f) {
            return getScoreboardVar(f, null).getCommand(f);
        }

        public boolean isObjective(McFunction f, String sel, String obj) {
            checkDefined(f);
            return sel.equals(selector) && obj.equals(array + "Idx");
        }

        public Var getAssignto(McFunction f) {
            checkDefined(f);
            return new ScoreboardVar("Global", "{self.array}Idx");
        }

        public void copyFrom(McFunction f, Var var) {
            checkDefined(f);
            String indexName = array + "Idx";
            ScoreboardVar indexVar = new ScoreboardVar(selector, indexName);
            Var exprVar = idxExpr.compile(f, new StrVar(indexName));
            indexVar.copyFrom(f, exprVar);
            ScoreboardVar valVar = new ScoreboardVar(selector, array + "Val");
            valVar.copyFrom(f, var);
            f.addCommand(prefix() + "function " + f.namespace() + ":array_" + array.toLowerCase(java.util.Locale.ROOT) + "_set");
            exprVar.freeScratch(f);
            valVar.freeScratch(f);
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return idxExpr.referencesScoreboardVar(f, var);
        }
    }

    public static final class BlockPathVar implements Var {
        final String blockId;
        final String pathName;
        final Coords coords;
        final List<Value> macroArgs;

        BlockPathVar(String blockId, String pathName, Coords coords, List<Value> macroArgs) {
            this.blockId = blockId;
            this.pathName = pathName;
            this.coords = coords;
            this.macroArgs = macroArgs;
        }

        public String pyName() {
            return "block_path_var";
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var assignto) {
            f.environment.getBlockDefinition(blockId);
            if (assignto == null) {
                assignto = new ScoreboardVar("Global", f.getScratch());
            }
            f.addCommand("execute store result score " + none(assignto.getSelvar(f)) + " run " + getCommand(f));
            return (ScoreboardVar) assignto;
        }

        public String getCommand(McFunction f) {
            return f.environment.getBlockDefinition(blockId).getCommand(f, pathName, coords, macroArgs);
        }

        public void copyFrom(McFunction f, Var var) {
            f.environment.getBlockDefinition(blockId).copyFrom(f, pathName, coords, macroArgs, var);
            var.freeScratch(f);
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }
    }

    public static final class CommandVar implements Var {
        final String output;
        final String command;

        CommandVar(String output, String command) {
            this.output = output;
            this.command = command.startsWith("/") ? command.substring(1) : command;
        }

        public String pyName() {
            return "command_var";
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var assignto) {
            if (assignto == null) {
                assignto = new ScoreboardVar("Global", f.getScratch());
            }
            f.addCommand("execute store " + output + " score " + none(assignto.getSelvar(f)) + " run " + command);
            return (ScoreboardVar) assignto;
        }

        public String getCommand(McFunction f) {
            return command;
        }

        public void copyFrom(McFunction f, Var v) {
            throw Ctx.compileError("Cannot set the value of a command.");
        }

        public Var getModifiableVar(McFunction f, Var assignto) {
            return getScoreboardVar(f, assignto);
        }
    }

    public static final class SelectorIdVar implements Var {
        final String selector;

        SelectorIdVar(String selector) {
            this.selector = selector;
        }

        public String pyName() {
            return "selector_id_var";
        }

        public String selectorAttr() {
            return selector;
        }

        void initializeId(McFunction f) {
            if (!f.checkSingleEntity(selector)) {
                throw Ctx.compileError("Selector \"" + selector + "\" does not specify an individual entity.");
            }
            f.registerObjective("_unique");
            f.registerObjective("_id");
            f.addCommand("scoreboard players add Global _unique 1");
            f.addCommand("execute unless score " + selector + " _id matches 1.. run scoreboard players operation 0 _id = Global _unique");
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var a) {
            initializeId(f);
            return new ScoreboardVar(selector, "_id");
        }

        public String getCommand(McFunction f) {
            initializeId(f);
            return new ScoreboardVar(selector, "_id").getCommand(f);
        }

        public boolean isObjective(McFunction f, String sel, String obj) {
            return sel.equals(selector) && obj.equals("_id");
        }

        public void copyFrom(McFunction f, Var var) {
            throw new PyException("TypeError", "scoreboard_var.copy_from() missing 1 required positional argument: 'var'");
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }
    }

    public static final class StoragePathVar implements Var {
        final String target;
        final String pathName;

        StoragePathVar(String target, String pathName) {
            this.target = target;
            this.pathName = pathName;
        }

        public String pyName() {
            return "storage_path_var";
        }

        String target(McFunction f) {
            return target == null ? f.namespace() : target;
        }

        public ScoreboardVar getScoreboardVar(McFunction f, Var assignto) {
            if (assignto == null) {
                assignto = new ScoreboardVar("Global", f.getScratch());
            }
            f.addCommand("execute store result score " + none(assignto.getSelvar(f)) + " run " + getCommand(f));
            return (ScoreboardVar) assignto;
        }

        public String getCommand(McFunction f) {
            return "data get storage " + target(f) + " " + pathName + " 1";
        }

        public void copyFrom(McFunction f, Var var) {
            PyObject c = var.getConstValue(f);
            if (c != null) {
                f.addCommand("data modify storage " + target(f) + " " + pathName + " set value " + c.str());
            } else {
                f.addCommand("execute store result storage " + target(f) + " " + pathName + " int 1 run " + var.getCommand(f));
            }
            var.freeScratch(f);
        }

        public Var getModifiableVar(McFunction f, Var a) {
            return scratchCopy(f, this);
        }
    }

    // ── scalar_expressions ───────────────────────────────────────────────────

    public static final class BinopExpr implements Expr {
        final Expr lhs;
        final String op;
        final Expr rhs;

        BinopExpr(Expr lhs, String op, Expr rhs) {
            this.lhs = lhs;
            this.op = op;
            this.rhs = rhs;
        }

        public String pyName() {
            return "binop_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return lhs.referencesScoreboardVar(f, var) || rhs.referencesScoreboardVar(f, var);
        }

        public Var compile(McFunction f, Var assignto) {
            if (op.length() == 1 && "+-*/%".contains(op)) {
                Var left = lhs.compile(f, assignto);
                PyObject leftConst = left.getConstValue(f);
                Var right = rhs.compile(f, null);
                PyObject rightConst = right.getConstValue(f);
                if ((op.equals("+") || op.equals("*")) && leftConst != null && rightConst == null) {
                    Var t = left;
                    left = right;
                    right = t;
                    PyObject tc = leftConst;
                    leftConst = rightConst;
                    rightConst = tc;
                }
                Var temp;
                if (assignto != null && !rhs.referencesScoreboardVar(f, assignto)) {
                    assignto.copyFrom(f, left);
                    temp = assignto;
                } else {
                    temp = left.getModifiableVar(f, assignto);
                }
                if (rightConst != null && rightConst.truthy() && (op.equals("+") || op.equals("-"))) {
                    String o = op;
                    if (Py.binop("<", rightConst, Py.of(0)).truthy()) {
                        rightConst = Py.neg(rightConst);
                        o = o.equals("+") ? "-" : "+";
                    }
                    f.addCommand("scoreboard players " + (o.equals("+") ? "add" : "remove") + " " + none(temp.getSelvar(f)) + " " + rightConst.str());
                } else {
                    right = right.getScoreboardVar(f, null);
                    f.addCommand("scoreboard players operation " + none(temp.getSelvar(f)) + " " + op + "= " + none(right.getSelvar(f)));
                }
                right.freeScratch(f);
                return temp;
            }
            if (op.equals("^")) {
                Var left = lhs.compile(f, assignto);
                Var temp = left.getModifiableVar(f, assignto);
                Var right = rhs.compile(f, null);
                PyObject power = right.getConstValue(f);
                if (power == null) {
                    Ctx.print("Exponentiation must have constant operand.");
                    return NONE;
                }
                BigInteger pw = McFunction.intOf(power);
                if (pw.compareTo(BigInteger.ONE) < 0) {
                    Ctx.print("Powers less than 1 are not supported");
                    return NONE;
                }
                if (pw.equals(BigInteger.ONE)) {
                    throw new PyException("NameError", "name 'target' is not defined");
                }
                String multiplier = f.getScratch();
                f.addCommand("scoreboard players operation Global " + multiplier + " = " + temp.selectorAttr() + " " + temp.objectiveAttr());
                for (BigInteger i = BigInteger.ZERO; i.compareTo(pw.subtract(BigInteger.ONE)) < 0; i = i.add(BigInteger.ONE)) {
                    f.addCommand("scoreboard players operation " + temp.selectorAttr() + " " + temp.objectiveAttr() + " *= Global " + multiplier);
                }
                f.freeScratch(multiplier);
                return temp;
            }
            Ctx.print("Binary operation '" + op + "' isn't implemented");
            return NONE;
        }
    }

    public static final class UnaryExpr implements Expr {
        final String type;
        final Expr expr;

        UnaryExpr(String type, Expr expr) {
            this.type = type;
            this.expr = expr;
        }

        public String pyName() {
            return "unary_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return expr.referencesScoreboardVar(f, var);
        }

        public Var compile(McFunction f, Var assignto) {
            if (type.equals("-")) {
                Var var = expr.compile(f, assignto);
                if (var == NONE) {
                    return NONE;
                }
                PyObject c = var.getConstValue(f);
                if (c != null && c.truthy()) {
                    return new VirtualIntVar(Py.neg(Py.toInt(c)));
                }
                Var temp;
                if (assignto != null && !referencesScoreboardVar(f, assignto)) {
                    assignto.copyFrom(f, var);
                    temp = assignto;
                } else {
                    temp = var.getModifiableVar(f, assignto);
                }
                String minus = f.addConstant(Py.of(-1));
                f.addCommand("scoreboard players operation " + none(temp.getSelvar(f)) + " *= " + minus + " Constant");
                return temp;
            }
            Ctx.print("Unary operation '" + type + "' isn't implemented.");
            return NONE;
        }
    }

    public static final class FuncExpr implements Expr {
        final Blocks.FunctionCallBlock call;

        FuncExpr(Blocks.FunctionCallBlock call) {
            this.call = call;
        }

        public String pyName() {
            return "func_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return false;
        }

        public Var compile(McFunction f, Var assignto) {
            call.compile(f);
            return new ScoreboardVar("Global", "ReturnValue");
        }
    }

    public static final class MethodExpr implements Expr {
        final Blocks.MethodCallBlock call;

        MethodExpr(Blocks.MethodCallBlock call) {
            this.call = call;
        }

        public String pyName() {
            return "method_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return false;
        }

        public Var compile(McFunction f, Var assignto) {
            call.compile(f);
            return new ScoreboardVar("Global", "ReturnValue");
        }
    }

    public static final class CreateExpr implements Expr {
        final CreateBlock create;

        CreateExpr(CreateBlock create) {
            this.create = create;
        }

        public String pyName() {
            return "create_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return false;
        }

        public Var compile(McFunction f, Var assignto) {
            f.registerObjective("_age");
            f.registerObjective("_unique");
            f.registerObjective("_id");
            f.addCommand("scoreboard players set @" + create.atid + " _age 1");
            try {
                create.compile(f);
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    return new PyException("Exception", "Could not run create operation.");
                });
            }
            f.addCommand("scoreboard players add @" + create.atid + " _age 1");
            return new SelectorIdVar("@" + create.atid + "[_age==1,limit=1]");
        }
    }

    public static final class DotExpr implements Expr {
        final VectorExpr lhs;
        final VectorExpr rhs;

        DotExpr(VectorExpr lhs, VectorExpr rhs) {
            this.lhs = lhs;
            this.rhs = rhs;
        }

        public String pyName() {
            return "dot_expr";
        }

        public boolean referencesScoreboardVar(McFunction f, Var var) {
            return false;
        }

        public Var compile(McFunction f, Var assignto) {
            List<Var> l = lhs.compile(f, null);
            List<Var> r = rhs.compile(f, null);
            if (l == null || r == null) {
                throw new PyException("TypeError", "'NoneType' object is not subscriptable");
            }
            List<Var> prods = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Var prod = l.get(i).getModifiableVar(f, assignto);
                assignto = null;
                Var mult = r.get(i).getScoreboardVar(f, null);
                f.addCommand("scoreboard players operation " + prod.selvar() + " *= " + mult.selvar());
                prods.add(prod);
            }
            f.addCommand("scoreboard players operation " + prods.get(0).selvar() + " += " + prods.get(1).selvar());
            f.addCommand("scoreboard players operation " + prods.get(0).selvar() + " += " + prods.get(2).selvar());
            for (int i = 0; i < 3; i++) {
                for (Var v : new Var[] {l.get(i), r.get(i), prods.get(i)}) {
                    if (v != prods.get(0)) {
                        v.freeScratch(f);
                    }
                }
            }
            return prods.get(0);
        }
    }

    // ── vector_expressions ───────────────────────────────────────────────────

    /** compile(func, assignto) gives three component variables, or null where Python returns None. */
    public interface VectorExpr {
        List<Var> compile(McFunction f, List<Var> assignto);
    }

    public record VectorTriple(Expr[] exprs) implements VectorExpr {
        public List<Var> compile(McFunction f, List<Var> assignto) {
            List<Var> vars = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Var v = exprs[i].compile(f, assignto == null ? null : assignto.get(i));
                if (v == NONE) {
                    return null;
                }
                vars.add(v);
            }
            return vars;
        }
    }

    public record VectorVarExpr(String vectorId) implements VectorExpr {
        public List<Var> compile(McFunction f, List<Var> assignto) {
            List<Var> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String name = "_" + vectorId + "_" + i;
                out.add(new ScoreboardVar("Global", name));
                f.registerObjective(name);
            }
            return out;
        }
    }

    public record SelVectorVarExpr(String sel, String id) implements VectorExpr {
        public List<Var> compile(McFunction f, List<Var> assignto) {
            List<Var> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                out.add(new ScoreboardVar(sel, "_" + id + "_" + i));
            }
            f.getVectorPath(sel, id);
            return out;
        }
    }

    public record VectorVarConstVector(Value value) implements VectorExpr {
        public List<Var> compile(McFunction f, List<Var> assignto) {
            PyObject components = value.getValue(f);
            try {
                List<Var> vars = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    vars.add(new VirtualIntVar(Py.toInt(components.getitem(Py.of(i)))));
                }
                return vars;
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                throw Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    return Ctx.compileError("Unable to get three components from constant vector expression.");
                });
            }
        }
    }

    public record VectorHereExpr(Value scale) implements VectorExpr {
        public List<Var> compile(McFunction f, List<Var> assignto) {
            PyObject s = scale == null ? f.getScale() : scale.getValue(f);
            f.registerObjective("_age");
            f.addCommand("scoreboard players add @e _age 1");
            f.addCommand("summon area_effect_cloud");
            f.addCommand("scoreboard players add @e _age 1");
            List<Var> out = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (assignto == null) {
                    out.add(new ScoreboardVar("Global", f.getScratch()));
                } else {
                    out.add(assignto.get(i));
                }
                f.addCommand("execute store result score Global " + out.get(i).objectiveAttr() + " run data get entity @e[_age==1,limit=1] Pos[" + i + "] " + s.str());
            }
            f.addCommand("/kill @e[_age==1]");
            return out;
        }
    }

    /** vector_binop_base: (lhs op rhs), with the right side a vector or a scalar. */
    public static final class VectorBinop implements VectorExpr {
        final VectorExpr lhs;
        final String op;
        final Object rhs;  // VectorExpr or Expr
        final boolean scalar;

        VectorBinop(VectorExpr lhs, String op, Object rhs, boolean scalar) {
            this.lhs = lhs;
            this.op = op;
            this.rhs = rhs;
            this.scalar = scalar;
        }

        public List<Var> compile(McFunction f, List<Var> assignto) {
            List<Var> left = lhs.compile(f, assignto);
            if (left == null) {
                return null;
            }
            List<Var> ret = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ret.add(left.get(i).getModifiableVar(f, assignto != null ? assignto.get(i) : null));
            }
            if (scalar) {
                Var right = ((Expr) rhs).compile(f, null).getScoreboardVar(f, null);
                for (int i = 0; i < 3; i++) {
                    f.addCommand("scoreboard players operation " + ret.get(i).selectorAttr() + " " + ret.get(i).objectiveAttr() + " " + op + "= "
                            + right.selectorAttr() + " " + right.objectiveAttr());
                }
                right.freeScratch(f);
            } else {
                List<Var> right = ((VectorExpr) rhs).compile(f, null);
                if (right != null) {
                    for (int i = 0; i < 3; i++) {
                        Var rv = right.get(i).getScoreboardVar(f, null);
                        f.addCommand("scoreboard players operation " + ret.get(i).selectorAttr() + " " + ret.get(i).objectiveAttr() + " " + op + "= "
                                + rv.selectorAttr() + " " + rv.objectiveAttr());
                        right.get(i).freeScratch(f);
                    }
                }
            }
            return ret;
        }
    }

    // ── a few blocks the expression classes need ─────────────────────────────

    public static final class CreateBlock implements Block {
        final int line;
        final String atid;
        final Coords relcoords;
        final Value index;

        CreateBlock(int line, String atid, Coords relcoords, Value index) {
            this.line = line;
            this.atid = atid;
            this.relcoords = relcoords;
            this.index = index;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            if (!f.runCreate(atid, relcoords, index)) {
                throw new PyException("Exception", "Error creating entity at line " + line);
            }
        }
    }

    public static final class BlockDefinition implements Block {
        final int line;
        final String blockId;
        final List<NumberMacroPath> items;
        final Coords coords;
        final Map<String, NumberMacroPath> paths = new LinkedHashMap<>();

        BlockDefinition(int line, String blockId, List<NumberMacroPath> items, Coords coords) {
            this.line = line;
            this.blockId = blockId;
            this.items = items;
            this.coords = coords;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            f.environment.blockDefinitions.put(blockId, this);
            for (NumberMacroPath item : items) {
                paths.put(item.id, item);
            }
        }

        void copyFrom(McFunction f, String path, Coords c, List<Value> macroArgs, Var var) {
            if (c == null) {
                c = coords;
            }
            if (!paths.containsKey(path)) {
                throw Ctx.compileError("No path \"" + path + "\" defined for [" + blockId + "].");
            }
            paths.get(path).copyFrom(f, c, macroArgs, var);
        }

        String getCommand(McFunction f, String path, Coords c, List<Value> macroArgs) {
            if (c == null) {
                c = coords;
            }
            if (!paths.containsKey(path)) {
                throw Ctx.compileError("No path \"" + path + "\" defined for [" + blockId + "].");
            }
            return paths.get(path).getCommand(f, c, macroArgs);
        }
    }

    public static final class PushBlock implements Block {
        final int line;
        final List<Expr> exprList;

        PushBlock(int line, List<Expr> exprList) {
            this.line = line;
            this.exprList = exprList;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            f.addCommand("data modify storage " + f.namespace() + " stack append value {}");
            int idx = 0;
            for (Expr e : exprList) {
                Var v = e.compile(f, null);
                new StoragePathVar(null, "stack[-1].v" + idx).copyFrom(f, v);
                idx++;
            }
        }
    }

    public static final class PopBlock implements Block {
        final int line;
        final List<Var> varList;

        PopBlock(int line, List<Var> varList) {
            this.line = line;
            this.varList = varList;
        }

        public int line() {
            return line;
        }

        public void compile(McFunction f) {
            int idx = 0;
            for (Var v : varList) {
                v.copyFrom(f, new StoragePathVar(null, "stack[-1].v" + idx));
                idx++;
            }
            f.addCommand("data remove storage " + f.namespace() + " stack[-1]");
        }
    }

    static PyObject floatOf(PyObject v) {
        return (PyFloat) Py.toFloat(v);
    }

    static boolean isStr(PyObject v) {
        return v instanceof PyStr;
    }

    static BigInteger bigOf(PyObject v) {
        return ((PyInt) Py.toInt(v)).value();
    }
}
