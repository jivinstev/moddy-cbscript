// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.compiler.Ast.Block;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyObject;

/** global_context.py. */
public final class GlobalContext {
    public record ArrayDef(BigInteger from, BigInteger to, boolean selectorBased) {
    }

    public record Macro(List<String> params, List<Block> lines) {
    }

    public record Template(List<String> macroParams, List<String> params, List<Block> lines) {
    }

    public record LootTable(String type, String contents) {
    }

    public final List<String> clocks = new ArrayList<>();
    /** A null value is a template function being compiled (the Python placeholder None). */
    public final Map<String, McFunction> functions = new LinkedHashMap<>();
    public final Map<String, List<String>> functionParams = new LinkedHashMap<>();
    public final Map<String, Macro> macros = new LinkedHashMap<>();
    public final Map<String, Template> templateFunctions = new LinkedHashMap<>();
    public final Map<String, Boolean> objectives = new LinkedHashMap<>();
    public final List<BigInteger> constants = new ArrayList<>();
    public final Map<String, ArrayDef> arrays = new LinkedHashMap<>();
    public final Map<String, Integer> scratch = new LinkedHashMap<>();
    public int temp = 0;
    public int unique = 0;
    public final String friendlyName;
    public final Map<String, List<String>> blockTags = new LinkedHashMap<>();
    public final Map<String, List<String>> entityTags = new LinkedHashMap<>();
    public final Map<String, List<String>> itemTags = new LinkedHashMap<>();
    public final Map<String, Boolean> scratchPrefixes = new LinkedHashMap<>();
    public final String namespace;
    public Driver.ParseFunc parser;
    public final List<String> dependencies = new ArrayList<>();
    public final List<Ast.ShapedRecipe> recipes = new ArrayList<>();
    public final Map<String, String> advancements = new LinkedHashMap<>();
    public final Map<String, LootTable> lootTables = new LinkedHashMap<>();
    public final Map<String, String> predicates = new LinkedHashMap<>();
    public final Map<String, String> itemModifiers = new LinkedHashMap<>();
    private Map<String, Object> blockStateList;
    private Map<String, Object> blockList;
    public PyObject scale;

    public GlobalContext(String namespace) {
        this.namespace = namespace;
        this.friendlyName = friendlyName(namespace);
    }

    static String friendlyName(String namespace) {
        String name = "CB" + PyText.slice(namespace, 0, 14);
        for (String c : new String[] {" ", ".", ",", ":", "{", "}", "="}) {
            name = name.replace(c, "_");
        }
        return name;
    }

    static String constantName(BigInteger c) {
        if (c.equals(BigInteger.ONE.negate())) {
            return "minus";
        } else if (c.signum() >= 0) {
            return "c" + c;
        } else {
            return "cm" + c.negate();
        }
    }

    public void registerBlockTag(String name, List<String> blocks) {
        blockTags.put(name, blocks);
    }

    public void registerEntityTag(String name, List<String> entities) {
        entityTags.put(name, entities);
    }

    public void registerItemTag(String name, List<String> items) {
        itemTags.put(name, items);
    }

    public int getUniqueId() {
        return ++unique;
    }

    public void registerClock(String name) {
        clocks.add(name);
    }

    public void registerFunction(String name, McFunction func) {
        functions.put(name, func);
        func.setFilename(name);
    }

    public void registerFunctionParams(String name, List<String> params) {
        functionParams.put(name, params);
    }

    public void registerArray(String name, BigInteger from, BigInteger to, boolean selectorBased) {
        if (arrays.containsKey(name)) {
            throw Ctx.compileError("Array \"" + name + "\" is defined multiple times.");
        }
        arrays.put(name, new ArrayDef(from, to, selectorBased));
    }

    public void registerObjective(String objective) {
        int len = objective.codePointCount(0, objective.length());
        if (len > 16) {
            throw Ctx.compileError("Cannot create objective \"" + objective + "\", name is " + len + " characters (max is 16)");
        }
        objectives.put(objective, true);
    }

    public McFunction getResetFunction() {
        return functions.get("reset");
    }

    /** add_constant(c): c = int(c), with Python's broken except clause (it names an unbound {@code e}). */
    public String addConstant(PyObject c) {
        BigInteger v;
        try {
            v = ((PyInt) Py.toInt(c)).value();
        } catch (PyException e) {
            throw Ctx.except(e, () -> new PyException("NameError", "name 'e' is not defined"));
        }
        if (!constants.contains(v)) {
            constants.add(v);
        }
        return constantName(v);
    }

    public void addConstantDefinitions() {
        McFunction f = getResetFunction();
        if (!constants.isEmpty()) {
            f.insertCommand("/scoreboard objectives add Constant dummy", 0);
            for (BigInteger c : constants) {
                f.insertCommand("/scoreboard players set " + constantName(c) + " Constant " + c, 1);
            }
        }
    }

    public void allocateScratch(String prefix, int n) {
        scratch.putIfAbsent(prefix, 0);
        if (n > scratch.get(prefix)) {
            scratch.put(prefix, n);
        }
    }

    public void allocateTemp(int t) {
        if (t > temp) {
            temp = t;
        }
    }

    public void finalizeFunctions() {
        for (McFunction f : new ArrayList<>(functions.values())) {
            if (f == null) {
                throw new PyException("AttributeError", "'NoneType' object has no attribute 'finalize'");
            }
            f.finalizeFunction();
        }
    }

    public String getScratchPrefix(String name) {
        name = PyText.slice(name, 0, 3);
        if (scratchPrefixes.containsKey(name)) {
            int i = 2;
            while (scratchPrefixes.containsKey(name + i)) {
                i++;
            }
            name = name + i;
            scratchPrefixes.put(name, true);
            return name;
        }
        scratchPrefixes.put(name, true);
        return name;
    }

    public String getRandomObjective() {
        return "RV" + friendlyName.substring(2);
    }

    public void registerDependency(String filename) {
        dependencies.add(filename);
    }

    /** get_block_state_list: blocks.json, as loaded (with states) or reduced to one default state per block. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBlockStateList(boolean includeBlockStates) {
        if (includeBlockStates) {
            if (blockStateList == null) {
                blockStateList = (Map<String, Object>) loadBlocks();
            }
            return blockStateList;
        }
        if (blockList == null) {
            Map<String, Object> blocks = (Map<String, Object>) loadBlocks();
            long id = 0;
            for (Map.Entry<String, Object> e : blocks.entrySet()) {
                Map<String, Object> block = (Map<String, Object>) e.getValue();
                List<Object> states = new ArrayList<>();
                for (Object s : (List<Object>) block.get("states")) {
                    if (((Map<String, Object>) s).containsKey("default")) {
                        states.add(s);
                    }
                }
                block.put("states", states);
                if (states.isEmpty()) {
                    throw new PyException("IndexError", "list index out of range");
                }
                Map<String, Object> first = (Map<String, Object>) states.get(0);
                first.remove("properties");
                block.remove("properties");
                first.put("id", BigInteger.valueOf(id));
                id++;
            }
            blockList = blocks;
        }
        return blockList;
    }

    private Object loadBlocks() {
        String text = Ctx.get().files.read("blocks.json");
        if (text == null) {
            PyException e = new PyException("FileNotFoundError", "[Errno 2] No such file or directory: 'blocks.json'");
            e.reprArgs = "2, 'No such file or directory'";
            throw e;
        }
        return Json.loads(text);
    }

    public int getNumBlocks() {
        return getBlockStateList(false).size();
    }

    public int getNumBlockStates() {
        return getBlockStateList(true).size();
    }
}
