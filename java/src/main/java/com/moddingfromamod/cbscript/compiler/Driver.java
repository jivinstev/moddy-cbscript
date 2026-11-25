// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.compiler.Ast.Block;
import com.moddingfromamod.cbscript.parse.Parser;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyObject;

/** cbscript.py's compile_all and post-processing, mcworld.py's writer, and compile_once.py's verdict. */
public final class Driver {
    /** global_context.parser. */
    public interface ParseFunc {
        Object parse(String text);
    }

    /** compile_once.py's JSON verdict, plus the files the zip would hold, in order. */
    public record Result(boolean ok, String namespace, String error, List<String> log, List<Map.Entry<String, byte[]>> files) {
    }

    private Driver() {
    }

    /** {@code basename.split('.')[0].lower()}. */
    public static String namespaceOf(String name) {
        return name.split("\\.", -1)[0].toLowerCase(java.util.Locale.ROOT);
    }

    /** Python's text-mode read: \r\n and lone \r become \n. */
    public static String universalNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static Result compile(String scriptName, String source, String minecraftVersion, SourceFiles files) {
        Ctx ctx = new Ctx(new McTarget(minecraftVersion), files);
        Ctx previous = Ctx.get();
        Ctx.set(ctx);
        PyException.reset();
        String namespace = namespaceOf(scriptName);
        List<String> logs = new ArrayList<>();
        String error = null;
        boolean ok;
        List<Map.Entry<String, byte[]>> out = new ArrayList<>();
        try {
            try {
                ok = compileAll(namespace, universalNewlines(source), logs, out);
            } catch (PyException e) {
                ok = false;
                if (e.isA("SyntaxError") || e.isA("CompileError")) {
                    error = e.str();
                } else {
                    error = "Unexpected compiler error: " + e.repr() + "\n" + Log.traceback(e);
                }
            } catch (StackOverflowError e) {
                ok = false;
                error = "Unexpected compiler error: RecursionError('maximum recursion depth exceeded')\n";
            } catch (RuntimeException e) {
                ok = false;
                error = "Unexpected compiler error: " + Ctx.wrap(e).repr() + "\n";
            }
            if (!ok && error == null) {
                error = "Compile failed — see log.";
            }
            List<String> log = new ArrayList<>(logs);
            log.addAll(ctx.log.lines());
            return new Result(ok, namespace, error, log, ok ? out : List.of());
        } finally {
            Ctx.set(previous);
            PyException.reset();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean compileAll(String namespace, String text, List<String> logs, List<Map.Entry<String, byte[]>> out) {
        Parser parser = new Parser(Ctx::print, new Builder());
        Object result = parser.parse(text + "\n");
        if (result == null) {
            logs.add("Unable to parse script.");
            return false;
        }
        Object[] r = (Object[]) result;
        if (!r[0].equals("program")) {
            logs.add("Script does not contain a full program.");
            return false;
        }
        Map<String, Object> parsed = (Map<String, Object>) r[1];
        GlobalContext gc = new GlobalContext(namespace);
        Environment env = new Environment(gc);
        env.setDollarid("namespace", Py.of(namespace));
        env.setDollarid("get_num_blocks", McFunction.boundMethod("get_num_blocks", gc::getNumBlocks));
        env.setDollarid("get_num_block_states", McFunction.boundMethod("get_num_block_states", gc::getNumBlockStates));
        if (!parsed.containsKey("scale")) {
            parsed.put("scale", Py.of(10000));
        }
        PyObject scale = (PyObject) parsed.get("scale");
        env.setDollarid("global_scale", scale);
        McFunction global = new McFunction(env);
        gc.scale = scale;
        gc.parser = parser::parse;
        List<Block> lines = (List<Block>) parsed.get("lines");
        for (Block line : lines) {
            line.register(gc);
        }
        try {
            global.compileBlocks(lines);
        } catch (PyException e) {
            if (!e.isA("Exception")) {
                throw e;
            }
            Ctx.except(e, () -> {
                Ctx.print(e.str());
                checkDependencies(gc);
            });
            return false;
        }
        if (gc.getResetFunction() == null) {
            gc.registerFunction("reset", new McFunction(env));
        }
        postProcessing(gc);
        write(gc, namespace, (String) parsed.get("desc"), out);
        checkDependencies(gc);
        return true;
    }

    /** self.dependencies = [source_file(d) for d in ...]: a missing file is a FileNotFoundError. */
    private static void checkDependencies(GlobalContext gc) {
        for (String d : gc.dependencies) {
            if (Ctx.get().files.read(d) == null) {
                PyException e = new PyException("FileNotFoundError",
                        "[Errno 2] No such file or directory: " + PyException.reprString(Ctx.get().files.absolute(d)));
                e.reprArgs = "2, 'No such file or directory'";
                throw e;
            }
        }
    }

    private static void postProcessing(GlobalContext gc) {
        gc.finalizeFunctions();
        McFunction reset = gc.getResetFunction();
        for (Map.Entry<String, Integer> e : gc.scratch.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                reset.insertCommand("/scoreboard objectives add " + e.getKey() + "_scratch" + i + " dummy", 0);
            }
        }
        for (int t = 0; t < gc.temp; t++) {
            reset.insertCommand("scoreboard objectives add temp" + t + " dummy", 0);
        }
        gc.addConstantDefinitions();
        Map<String, Boolean> defined = reset.definedObjectives();
        for (String objective : gc.objectives.keySet()) {
            if (!defined.containsKey(objective)) {
                reset.insertCommand("/scoreboard objectives add " + objective + " dummy", 0);
            }
        }
        reset.insertCommand("/gamerule " + Ctx.get().target.maxChainRule() + " 1000000000", 0);
        reset.insertCommand("/data modify storage " + gc.namespace + " stack set value []", 0);
        reset.insertCommand("/data modify storage " + gc.namespace + ":global args set value {}", 0);
    }

    private static void put(List<Map.Entry<String, byte[]>> out, String name, String text) {
        out.add(Map.entry(name, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** mcworld.py, in its write order. */
    private static void write(GlobalContext gc, String ns, String desc, List<Map.Entry<String, byte[]>> out) {
        for (Map.Entry<String, McFunction> e : gc.functions.entrySet()) {
            out.add(Map.entry("data/" + ns + "/function/" + e.getKey() + ".mcfunction", e.getValue().getUtf8Text()));
        }
        List<String> ticks = new ArrayList<>();
        for (String c : gc.clocks) {
            ticks.add(ns + ":" + c);
        }
        put(out, "data/minecraft/tags/function/tick.json", Json.dumps(Map.of("values", ticks), 4));
        put(out, "data/minecraft/tags/function/load.json", Json.dumps(Map.of("values", List.of(ns + ":reset")), 4));
        Object[][] tagKinds = {{"block", gc.blockTags}, {"item", gc.itemTags}, {"entity_type", gc.entityTags}};
        for (Object[] kind : tagKinds) {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> tags = (Map<String, List<String>>) kind[1];
            for (Map.Entry<String, List<String>> t : tags.entrySet()) {
                List<String> values = new ArrayList<>();
                for (String item : t.getValue()) {
                    values.add("minecraft:" + item);
                }
                put(out, "data/" + ns + "/tags/" + kind[0] + "/" + t.getKey() + ".json", Json.dumps(Map.of("values", values), 4));
            }
        }
        Map<String, Object> mcmeta = new LinkedHashMap<>();
        mcmeta.put("pack", Ctx.get().target.packMeta(desc));
        put(out, "pack.mcmeta", Json.dumps(mcmeta, 4));
        int id = 0;
        for (Ast.ShapedRecipe recipe : gc.recipes) {
            id++;
            put(out, "data/" + ns + "/recipe/" + recipe.getType() + id + ".json", Json.dumps(recipe.getJsonStruct(), 4));
        }
        for (Map.Entry<String, String> e : gc.advancements.entrySet()) {
            put(out, "data/" + ns + "/advancement/" + e.getKey() + ".json", e.getValue());
        }
        for (Map.Entry<String, GlobalContext.LootTable> e : gc.lootTables.entrySet()) {
            String name = e.getKey();
            String dir;
            String file;
            if (name.contains(":")) {
                String[] parts = name.split(":", -1);
                if (parts.length != 2) {
                    throw Ctx.compileError("Invalid loot tables name \"" + name + "\"");
                }
                dir = "data/" + parts[0] + "/loot_table/" + e.getValue().type() + "/";
                file = parts[1];
            } else {
                dir = "data/" + ns + "/loot_table/" + e.getValue().type() + "/";
                file = name;
            }
            put(out, dir + file + ".json", e.getValue().contents());
        }
        for (Map.Entry<String, String> e : gc.predicates.entrySet()) {
            put(out, "data/" + ns + "/predicate/" + e.getKey() + ".json", e.getValue());
        }
        for (Map.Entry<String, String> e : gc.itemModifiers.entrySet()) {
            put(out, "data/" + ns + "/item_modifier/" + e.getKey() + ".json", ItemModifiers.fix(e.getValue()));
        }
    }
}
