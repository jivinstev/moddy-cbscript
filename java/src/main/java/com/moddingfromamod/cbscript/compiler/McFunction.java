// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.compiler.Ast.Block;
import com.moddingfromamod.cbscript.compiler.Ast.Var;
import com.moddingfromamod.cbscript.compiler.Ast.ScoreboardVar;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyFloat;
import com.moddingfromamod.cbscript.py.PyFunction;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyObject;

/** mcfunction.py: one function being compiled, and the operations blocks call on it. */
public final class McFunction {
    public List<String> commands = new ArrayList<>();
    public Environment environment;
    public final List<String> params;
    public final boolean callable;
    private final List<Environment> environmentStack = new ArrayList<>();
    public boolean hasMacros = false;
    public String filename;

    public McFunction(Environment environment) {
        this(environment, false, List.of());
    }

    public McFunction(Environment environment, boolean callable, List<String> params) {
        this.environment = environment;
        this.callable = callable;
        this.params = params;
        for (String p : params) {
            registerLocal(p);
        }
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getCall() {
        if (filename == null) {
            throw Ctx.compileError("Tried to call function with no registered filename.");
        }
        if (hasMacros) {
            return "function " + namespace() + ":" + filename + " with storage " + namespace() + ":global args";
        }
        return "function " + namespace() + ":" + filename;
    }

    public boolean evaluateParams(List<Ast.Expr> ps) {
        for (int p = 0; p < ps.size(); p++) {
            ScoreboardVar paramVar = new ScoreboardVar("Global", "Param" + p);
            Var var;
            try {
                var = ps.get(p).compile(this, null);
            } catch (PyException e) {
                if (!e.isA("Exception")) {
                    throw e;
                }
                final int pp = p;
                Ctx.except(e, () -> {
                    Ctx.print(e.str());
                    Ctx.print("Unable to compile parameter " + pp + ".");
                });
                return false;
            }
            paramVar.copyFrom(this, var);
        }
        return true;
    }

    private static PyObject cmp(PyObject a, String op, PyObject b) {
        return Py.binop(op, a, b);
    }

    public String getIfChain(List<Ast.Condition> conditions, String iftype) {
        StringBuilder test = new StringBuilder();
        for (Ast.Condition c : conditions) {
            switch (c.type()) {
                case "selector" -> test.append(iftype).append(" entity ").append((String) c.value()).append(' ');
                case "predicate" -> {
                    String val = (String) c.value();
                    if (val.contains(":")) {
                        test.append(iftype).append(" predicate ").append(val).append(' ');
                    } else if (globalContext().predicates.containsKey(val)) {
                        test.append(iftype).append(" predicate ").append(namespace()).append(':').append(val).append(' ');
                    } else {
                        throw Ctx.compileError("Predicate \"" + val + "\" does not exist");
                    }
                }
                case "score" -> {
                    Ast.ScoreCondition sc = (Ast.ScoreCondition) c.value();
                    String op = sc.op();
                    Var lvar = sc.lhs().compile(this, null);
                    Var rvar = sc.rhs().compile(this, null);
                    PyObject lconst = lvar.getConstValue(this);
                    PyObject rconst = rvar.getConstValue(this);
                    if (lconst != null && rconst != null) {
                        boolean result = false;
                        if (op.equals("=") && Py.eq(lconst, rconst)) {
                            result = true;
                        } else if (op.equals(">") && cmp(lconst, ">", rconst).truthy()) {
                            result = true;
                        } else if (op.equals("<") && cmp(lconst, "<", rconst).truthy()) {
                            result = true;
                        } else if (op.equals(">=") && cmp(lconst, ">=", rconst).truthy()) {
                            result = true;
                        } else if (op.equals("<=") && cmp(lconst, "<=", rconst).truthy()) {
                            result = true;
                        }
                        if ((iftype.equals("if") && !result) || (iftype.equals("unless") && result)) {
                            return "if score Global unique matches -1 ";
                        }
                        continue;
                    } else if (lconst != null || rconst != null) {
                        ScoreboardVar sbvar;
                        PyObject cst;
                        if (lconst != null) {
                            sbvar = rvar.getScoreboardVar(this, null);
                            cst = lconst;
                        } else {
                            sbvar = lvar.getScoreboardVar(this, null);
                            cst = rconst;
                        }
                        String sv = sbvar.selector + " " + sbvar.objective;
                        switch (op) {
                            case ">" -> test.append(iftype).append(" score ").append(sv).append(" matches ")
                                    .append(Py.binop("+", Py.toInt(cst), Py.of(1)).str()).append(".. ");
                            case ">=" -> test.append(iftype).append(" score ").append(sv).append(" matches ").append(cst.str()).append(".. ");
                            case "<" -> test.append(iftype).append(" score ").append(sv).append(" matches ..")
                                    .append(Py.binop("-", Py.toInt(cst), Py.of(1)).str()).append(' ');
                            case "<=" -> test.append(iftype).append(" score ").append(sv).append(" matches ..").append(cst.str()).append(' ');
                            case "=" -> test.append(iftype).append(" score ").append(sv).append(" matches ").append(cst.str()).append(' ');
                            default -> {
                            }
                        }
                    } else {
                        ScoreboardVar l = lvar.getScoreboardVar(this, null);
                        ScoreboardVar r = rvar.getScoreboardVar(this, null);
                        test.append(iftype).append(" score ").append(l.selector).append(' ').append(l.objective).append(' ')
                                .append(op).append(' ').append(r.selector).append(' ').append(r.objective).append(' ');
                    }
                }
                case "vector_equality" -> {
                    if (iftype.equals("unless")) {
                        throw Ctx.compileError("Vector equality may not be used with \"unless\"");
                    }
                    Object[] pair = (Object[]) c.value();
                    Ast.VectorVar v1 = (Ast.VectorVar) pair[0];
                    Ast.VectorVar v2 = (Ast.VectorVar) pair[1];
                    if (v1.type().equals("VAR_CONST") && v2.type().equals("VAR_CONST")) {
                        PyObject val1 = ((Ast.Value) v1.content()).getValue(this);
                        PyObject val2 = ((Ast.Value) v2.content()).getValue(this);
                        if (!Py.eq(val1, val2)) {
                            return "if score Global unique matches -1 ";
                        }
                        continue;
                    }
                    if (v1.type().equals("VAR_CONST")) {
                        Ast.VectorVar t = v1;
                        v1 = v2;
                        v2 = t;
                    }
                    List<PyObject> constVals = new ArrayList<>();
                    if (v2.type().equals("VAR_CONST")) {
                        PyObject components = ((Ast.Value) v2.content()).getValue(this);
                        try {
                            List<PyObject> cv = new ArrayList<>();
                            for (int i = 0; i < 3; i++) {
                                cv.add(Py.toInt(components.getitem(Py.of(i))));
                            }
                            constVals = cv;
                        } catch (PyException e) {
                            if (!e.isA("Exception")) {
                                throw e;
                            }
                            throw Ctx.except(e, () -> {
                                Ctx.print(e.str());
                                return Ctx.compileError("Unable to get three components for constant vector.");
                            });
                        }
                    }
                    for (int i = 0; i < 3; i++) {
                        ScoreboardVar lv = vectorComponent(v1, i);
                        if (v2.type().equals("VAR_CONST")) {
                            // Python names sel1/sco1 here, which are never bound: a NameError (or a stale sel1).
                            throw new PyException("NameError", v1.type().equals("SEL_VAR_ID")
                                    ? "name 'sco1' is not defined" : "name 'sel1' is not defined");
                        }
                        ScoreboardVar rv = vectorComponent(v2, i);
                        test.append("if score ").append(lv.selvar()).append(" = ").append(rv.selvar()).append(' ');
                    }
                }
                case "block" -> {
                    Ast.BlockCondition bc = (Ast.BlockCondition) c.value();
                    String block = applyEnvironment(bc.block());
                    if (globalContext().blockTags.containsKey(block)) {
                        block = "#" + namespace() + ":" + block;
                    } else {
                        block = "minecraft:" + block;
                    }
                    test.append(iftype).append(" block ").append(bc.coords().getValue(this)).append(' ').append(block).append(' ');
                }
                case "nbt_path" -> test.append(iftype).append(" data ").append(((Ast.NbtPath) c.value()).getDestPath(this)).append(' ');
                default -> throw new PyException("ValueError", "Unknown \"if\" type: " + c.type());
            }
        }
        return test.toString();
    }

    private ScoreboardVar vectorComponent(Ast.VectorVar v, int i) {
        switch (v.type()) {
            case "VAR_ID" -> {
                return new ScoreboardVar("Global", "_" + v.content() + "_" + i);
            }
            case "SEL_VAR_ID" -> {
                String[] sv = (String[]) v.content();
                return new ScoreboardVar(sv[0], "_" + sv[1] + "_" + i);
            }
            default -> {
                @SuppressWarnings("unchecked")
                List<Var> comps = (List<Var>) v.content();
                return comps.get(i).getScoreboardVar(this, null);
            }
        }
    }

    /** get_execute_items: the execute subcommands, or null where Python returns None. */
    public String getExecuteItems(List<Ast.ExecItem> items, McFunction execFunc) {
        StringBuilder cmd = new StringBuilder();
        int asCount = 0;
        for (Ast.ExecItem it : items) {
            if (it.type().startsWith("As")) {
                asCount++;
                if (asCount >= 2) {
                    Ctx.print("Execute chain may only contain a single \"as\" clause.");
                    return null;
                }
            }
        }
        int atVectorCount = 0;
        for (Ast.ExecItem it : items) {
            String type = it.type();
            Object val = it.value();
            if (type.equals("If")) {
                @SuppressWarnings("unchecked")
                List<Ast.Condition> conds = (List<Ast.Condition>) val;
                cmd.append(getIfChain(conds, "if"));
            }
            switch (type) {
                case "Unless" -> {
                    @SuppressWarnings("unchecked")
                    List<Ast.Condition> conds = (List<Ast.Condition>) val;
                    cmd.append(getIfChain(conds, "unless"));
                }
                case "As" -> {
                    cmd.append("as ").append((String) val).append(' ');
                    execFunc.updateSelfSelector((String) val);
                }
                case "On" -> {
                    cmd.append("on ").append((String) val).append(' ');
                    execFunc.updateSelfSelector("@s");
                }
                case "AsId" -> {
                    Object[] pair = (Object[]) val;
                    Var var = (Var) pair[0];
                    String attype = (String) pair[1];
                    ScoreboardVar sbvar = var.getScoreboardVar(this, null);
                    String selector = sbvar.selector;
                    String id = sbvar.objective;
                    String psel = attype == null ? "@e" : "@" + attype;
                    if (PyText.first(selector).equals("@")) {
                        SelectorDefinition seldef = new SelectorDefinition(selector, environment);
                        if (seldef.baseName.equals("s") && environment.selfSelector != null
                                && environment.selfSelector.pointers.containsKey(id)) {
                            psel = environment.selfSelector.pointers.get(id);
                        } else if (seldef.pointers.containsKey(id)) {
                            psel = seldef.pointers.get(id);
                        }
                    } else if (selector.equals("Global")) {
                        if (environment.pointers.containsKey(id)) {
                            psel = environment.pointers.get(id);
                        }
                    }
                    registerObjective("_id");
                    registerObjective(id);
                    addCommand("scoreboard players operation Global _id = " + selector + " " + id);
                    cmd.append("as ").append(psel).append(" if score @s _id = Global _id ");
                    if (attype != null) {
                        execFunc.updateSelfSelector("@" + attype);
                    } else if (!psel.equals("@e")) {
                        execFunc.updateSelfSelector("@" + undecoratedSelectorName(psel));
                    } else {
                        execFunc.updateSelfSelector("@s");
                    }
                }
                case "AsCreate" -> {
                    if (items.size() > 1) {
                        Ctx.print("\"as create\" may not be paired with other execute commands.");
                        return null;
                    }
                    Ast.CreateBlock create = (Ast.CreateBlock) val;
                    registerObjective("_age");
                    addCommand("scoreboard players set @" + create.atid + " _age 1");
                    create.compile(this);
                    addCommand("scoreboard players add @" + create.atid + " _age 1");
                    cmd.append("as @").append(create.atid).append("[_age==1,limit=1] ");
                    execFunc.updateSelfSelector("@" + create.atid);
                }
                case "Rotated" -> cmd.append("rotated as ").append((String) val).append(' ');
                case "FacingCoords" -> cmd.append("facing ").append(((Ast.Coords) val).getValue(this)).append(' ');
                case "FacingEntity" -> cmd.append("facing entity ").append((String) val).append(" feet ");
                case "Align" -> cmd.append("align ").append((String) val).append(' ');
                case "At" -> {
                    Object[] at = (Object[]) val;
                    String selector = (String) at[0];
                    Ast.Coords coords = (Ast.Coords) at[1];
                    String anchor = (String) at[2];
                    if (selector != null) {
                        cmd.append("at ").append(selector).append(' ');
                    }
                    if (anchor != null) {
                        cmd.append("anchored ").append(anchor).append(' ');
                    }
                    if (coords != null) {
                        cmd.append("positioned ").append(coords.getValue(this)).append(' ');
                    }
                }
                case "AtVector" -> {
                    atVectorCount++;
                    if (atVectorCount >= 2) {
                        Ctx.print("Tried to execute at multiple vector locations.");
                        return null;
                    }
                    Object[] av = (Object[]) val;
                    PyObject scale = av[0] == null ? getScale() : ((Ast.Value) av[0]).getValue(this);
                    List<Var> vecVals = ((Ast.VectorExpr) av[1]).compile(this, null);
                    addCommand("scoreboard players add @e _age 1");
                    addCommand("summon area_effect_cloud");
                    addCommand("scoreboard players add @e _age 1");
                    for (int i = 0; i < 3; i++) {
                        ScoreboardVar v = vecVals.get(i).getScoreboardVar(this, null);
                        addCommand("execute store result entity @e[_age==1,limit=1] Pos[" + i + "] double "
                                + inverse(scale) + " run scoreboard players get " + v.selector + " " + v.objective);
                    }
                    cmd.append("at @e[_age == 1] ");
                    execFunc.addCommand("/kill @e[_age == 1]");
                }
                case "In" -> cmd.append("in ").append((String) val).append(' ');
                default -> {
                }
            }
        }
        return cmd.toString();
    }

    /** {@code 1/float(scale)} as text. */
    public static String inverse(PyObject scale) {
        return Py.binop("/", Py.of(1), Py.toFloat(scale)).str();
    }

    public static String undecoratedSelectorName(String selector) {
        if (selector.startsWith("@")) {
            selector = selector.substring(1);
        }
        return selector.split("\\[", -1)[0];
    }

    /** One switch case: (vmin, vmax, lines, line, dollarid). */
    public record Case(BigInteger vmin, BigInteger vmax, List<Block> sub, Object line, String dollarid) {
    }

    public boolean switchCases(ScoreboardVar var, List<Case> cases) {
        return switchCases(var, cases, "switch", "case");
    }

    public boolean switchCases(ScoreboardVar var, List<Case> cases, String switchFuncName, String caseFuncName) {
        for (int q = 0; q < 4; q++) {
            int imin = q * cases.size() / 4;
            int imax = (q + 1) * cases.size() / 4;
            if (imin == imax) {
                continue;
            }
            BigInteger vmin = cases.get(imin).vmin();
            BigInteger vmax = cases.get(imax - 1).vmax();
            Object line = cases.get(imin).line();
            List<Case> subCases = cases.subList(imin, imax);
            McFunction caseFunc = createChildFunction();
            if (subCases.size() == 1) {
                Case c = subCases.get(0);
                vmin = c.vmin();
                vmax = c.vmax();
                line = c.line();
                if (c.dollarid() != null) {
                    caseFunc.setDollarid(c.dollarid(), Py.of(vmin));
                }
                final Object fline = line;
                try {
                    caseFunc.compileBlocks(c.sub());
                } catch (PyException e) {
                    if (e.isA("CompileError")) {
                        throw Ctx.except(e, () -> {
                            Ctx.print(e.str());
                            return Ctx.compileError("Unable to compile case at line " + fline);
                        });
                    }
                    if (!e.isA("Exception")) {
                        throw e;
                    }
                    throw Ctx.except(e, () -> {
                        Ctx.printTraceback(e);
                        return Ctx.compileError("Unable to compile case at line " + fline);
                    });
                }
                String single = caseFunc.singleCommand();
                if (single != null) {
                    String vrange = vmin.equals(vmax) ? vmin.toString() : vmin + ".." + vmax;
                    if (!single.isEmpty() && single.charAt(0) == '$') {
                        addCommand("$execute if score " + var.selector + " " + var.objective + " matches " + vrange + " run " + single.substring(1));
                    } else {
                        addCommand("execute if score " + var.selector + " " + var.objective + " matches " + vrange + " run " + single);
                    }
                } else {
                    int unique = getUniqueId();
                    String caseName = vmin.equals(vmax)
                            ? "line" + line + "/" + caseFuncName + vmin + "_" + unique
                            : "line" + line + "/" + caseFuncName + vmin + "-" + vmax + "_" + unique;
                    registerFunction(caseName, caseFunc);
                    addCommand("execute if score " + var.selector + " " + var.objective + " matches " + vmin + ".." + vmax + " run " + caseFunc.getCall());
                }
            } else {
                int unique = getUniqueId();
                String caseName = "line" + line + "/" + switchFuncName + vmin + "-" + vmax + "_" + unique;
                registerFunction(caseName, caseFunc);
                addCommand("execute if score " + var.selector + " " + var.objective + " matches " + vmin + ".." + vmax + " run " + caseFunc.getCall());
                if (!caseFunc.switchCases(var, new ArrayList<>(subCases))) {
                    return false;
                }
            }
        }
        return true;
    }

    public void addCommand(String command) {
        insertCommand(command, commands.size());
    }

    public void insertCommand(String command, int index) {
        if (command.isEmpty()) {
            return;
        }
        if (command.charAt(0) != '#') {
            if (command.charAt(0) == '/') {
                command = command.substring(1);
            }
            command = environment.apply(command);
            command = Transforms.fixNbtCase(command);
            command = Transforms.fixAttributeIds(command);
            command = Transforms.fixComponentSyntax(command);
            if (command.contains("$(")) {
                if (command.isEmpty() || command.charAt(0) != '$') {
                    if (command.isEmpty()) {
                        throw new PyException("IndexError", "string index out of range");
                    }
                    command = "$" + command;
                }
                hasMacros = true;
            }
        }
        commands.add(Math.min(index, commands.size()), command);
    }

    public byte[] getUtf8Text() {
        List<String> out = new ArrayList<>();
        for (String cmd : commands) {
            out.add(PyText.first(cmd).equals("/") ? cmd.substring(1) : cmd);
        }
        return String.join("\n", out).getBytes(StandardCharsets.UTF_8);
    }

    public Map<String, Boolean> definedObjectives() {
        Map<String, Boolean> existing = new LinkedHashMap<>();
        String define = "scoreboard objectives add ";
        for (String cmd : commands) {
            if (PyText.first(cmd).equals("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.startsWith(define)) {
                existing.put(cmd.substring(define.length()).split(" ", -1)[0], true);
            }
        }
        return existing;
    }

    public void registerLocal(String id) {
        environment.registerLocal(id);
    }

    public void finalizeFunction() {
        List<String> comments = new ArrayList<>();
        while (!commands.isEmpty() && commands.get(0).length() >= 2 && commands.get(0).startsWith("##")) {
            comments.add(commands.remove(0));
        }
        if (callable) {
            for (String v : environment.scratch.getAllocatedVariables()) {
                registerLocal(v);
            }
            for (int p = 0; p < params.size(); p++) {
                insertCommand("scoreboard players operation Global " + params.get(p) + " = Global Param" + p, 0);
                registerObjective("Param" + p);
            }
        }
        List<String> all = new ArrayList<>(comments);
        all.addAll(commands);
        commands = all;
    }

    static boolean realCommand(String cmd) {
        return !cmd.startsWith("#") && !cmd.isEmpty();
    }

    public String singleCommand() {
        String ret = null;
        int count = 0;
        for (String cmd : commands) {
            if (realCommand(cmd)) {
                ret = cmd;
                count++;
            }
            if (count >= 2) {
                return null;
            }
        }
        return ret;
    }

    public boolean isEmpty() {
        for (String cmd : commands) {
            if (realCommand(cmd)) {
                return false;
            }
        }
        return true;
    }

    public boolean checkSingleEntity(String selector) {
        if (!PyText.first(selector).equals("@")) {
            return true;
        }
        return environment.getSelectorDefinition(selector).singleEntity();
    }

    private SelectorDefinition pathDefinition(String selector) {
        String id = selector.substring(1);
        if (id.contains("[")) {
            id = id.split("\\[", 2)[0];
        }
        if (environment.selectors.containsKey(id)) {
            return environment.selectors.get(id);
        } else if (id.equals("s") && environment.selfSelector != null) {
            return environment.selfSelector;
        }
        return null;
    }

    public void getPath(String selector, String var) {
        if (!PyText.first(selector).equals("@")) {
            return;
        }
        SelectorDefinition def = pathDefinition(selector);
        if (def == null) {
            return;
        }
        if (def.paths.containsKey(var)) {
            SelectorDefinition.Path p = def.paths.get(var);
            PyObject scale = p.scale() == null ? getScale() : p.scale();
            if (!checkSingleEntity(selector)) {
                throw Ctx.compileError("Tried to get data \"" + var + "\" from selector \"" + selector + "\" which is not limited to a single entity.");
            }
            addCommand("execute store result score " + selector + " " + var + " run data get entity " + selector + " " + p.path() + " " + scale.str());
        }
    }

    public void setPath(String selector, String var) {
        if (!PyText.first(selector).equals("@")) {
            return;
        }
        SelectorDefinition def = pathDefinition(selector);
        if (def == null) {
            return;
        }
        if (def.paths.containsKey(var)) {
            SelectorDefinition.Path p = def.paths.get(var);
            PyObject scale = p.scale() == null ? getScale() : p.scale();
            if (!checkSingleEntity(selector)) {
                throw Ctx.compileError("Tried to set data \"" + var + "\" for selector \"" + selector + "\" which is not limited to a single entity.");
            }
            addCommand("execute store result entity " + selector + " " + p.path() + " " + p.dataType() + " " + inverse(scale)
                    + " run scoreboard players get " + selector + " " + var);
        }
    }

    public boolean getVectorPath(String selector, String var) {
        if (!PyText.first(selector).equals("@")) {
            return false;
        }
        SelectorDefinition def = pathDefinition(selector);
        if (def == null) {
            return false;
        }
        if (def.vectorPaths.containsKey(var)) {
            SelectorDefinition.Path p = def.vectorPaths.get(var);
            PyObject scale = p.scale() == null ? getScale() : p.scale();
            if (!checkSingleEntity(selector)) {
                throw Ctx.compileError("Tried to get vector data \"" + var + "\" from selector \"" + selector + "\" which is not limited to a single entity.");
            }
            for (int i = 0; i < 3; i++) {
                addCommand("execute store result score " + selector + " _" + var + "_" + i + " run data get entity " + selector + " " + p.path() + "[" + i + "] " + scale.str());
            }
            return true;
        }
        return false;
    }

    public boolean setVectorPath(String selector, String var, List<Var> values) {
        if (!PyText.first(selector).equals("@")) {
            return false;
        }
        SelectorDefinition def = pathDefinition(selector);
        if (def == null) {
            return false;
        }
        if (def.vectorPaths.containsKey(var)) {
            SelectorDefinition.Path p = def.vectorPaths.get(var);
            PyObject scale = p.scale() == null ? getScale() : p.scale();
            if (!checkSingleEntity(selector)) {
                throw Ctx.compileError("Tried to set vector data \"" + var + "\" for selector \"" + selector + "\" which is not limited to a single entity.");
            }
            for (int i = 0; i < 3; i++) {
                ScoreboardVar v = values.get(i).getScoreboardVar(this, null);
                addCommand("execute store result entity " + selector + " " + p.path() + "[" + i + "] " + p.dataType() + " " + inverse(scale)
                        + " run scoreboard players get " + v.selector + " " + v.objective);
            }
            return true;
        }
        return false;
    }

    // ── delegation to the environment ───────────────────────────────────────

    public void registerObjective(String objective) {
        environment.registerObjective(objective);
    }

    public void registerArray(String name, BigInteger from, BigInteger to, boolean selectorBased) {
        globalContext().registerArray(name, from, to, selectorBased);
    }

    public String applyReplacements(String text, Map<String, PyObject> overrides) {
        return environment.applyReplacements(text, overrides);
    }

    public String applyReplacements(String text) {
        return environment.applyReplacements(text, Map.of());
    }

    public PyObject getScale() {
        return environment.getScale();
    }

    public String namespace() {
        return globalContext().namespace;
    }

    public Map<String, SelectorDefinition> selectors() {
        return environment.selectors;
    }

    public String getScratch() {
        return environment.scratch.getScratch();
    }

    public boolean isScratch(String var) {
        return environment.scratch.isScratch(var);
    }

    public void freeScratch(String id) {
        environment.scratch.freeScratch(id);
    }

    public String applyEnvironment(String text) {
        return environment.apply(text);
    }

    public String addConstant(PyObject val) {
        return globalContext().addConstant(val);
    }

    public void registerFunction(String name, McFunction func) {
        globalContext().registerFunction(name, func);
    }

    public int getUniqueId() {
        return globalContext().getUniqueId();
    }

    public void updateSelfSelector(String selector) {
        environment.updateSelfSelector(selector);
    }

    public SelectorDefinition getSelfSelectorDefinition() {
        return environment.selfSelector;
    }

    public Environment cloneEnvironment(String newFunctionName) {
        return environment.cloneEnv(newFunctionName);
    }

    public void setDollarid(String id, PyObject val) {
        environment.setDollarid(id, val);
    }

    public PyObject getDollarid(String id) {
        return environment.getDollarid(id);
    }

    public SelectorDefinition setAtid(String id, String fullselector) {
        return environment.setAtid(id, fullselector);
    }

    public void pushEnvironment(Environment env) {
        environmentStack.add(environment);
        environment = env;
    }

    public void popEnvironment() {
        environment = environmentStack.remove(environmentStack.size() - 1);
    }

    public boolean runCreate(String atid, Ast.Coords relcoords, Ast.Value idx) {
        if (!selectors().containsKey(atid)) {
            Ctx.print("Unable to create unknown entity: @" + atid);
            return false;
        }
        SelectorDefinition selector = selectors().get(atid);
        String entityType = selector.getType();
        if (entityType == null) {
            Ctx.print("Unable to create @" + atid + ", no entity type is defined.");
            return false;
        }
        PyObject h = Py.binop("%", Py.of(BigInteger.valueOf(PyHash.hash(atid))), Py.of(BigInteger.ONE.shiftLeft(32)));
        if (selector.tag == null) {
            if (idx != null) {
                addCommand("summon " + entityType + " " + relcoords.getValue(this) + " " + Py.binop("+", idx.getValue(this), h).str() + "}");
            } else {
                addCommand("summon " + entityType + " " + relcoords.getValue(this));
            }
        } else {
            String tag;
            if (idx != null) {
                Object parsed = Json.loads(selector.tag);
                if (!(parsed instanceof Map)) {
                    throw new PyException("TypeError", parsed instanceof List
                            ? "list indices must be integers or slices, not str"
                            : "'" + Json.pyTypeName(parsed) + "' object does not support item assignment");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) parsed;
                m.put("UUIDMost", BigInteger.ZERO);
                m.put("UUIDLeast", Py.binop("+", idx.getValue(this), h));
                tag = Json.dumps(m, null);
            } else {
                tag = selector.tag;
            }
            addCommand("summon " + entityType + " " + relcoords.getValue(this) + " " + tag);
        }
        return true;
    }

    public void registerNameDefinition(String id, String str) {
        environment.registerNameDefinition(id, str);
    }

    public String getNameDefinition(String id) {
        return environment.getNameDefinition(id);
    }

    public McFunction createChildFunction() {
        return createChildFunction(null, false, List.of());
    }

    public McFunction createChildFunction(String newFunctionName, boolean callable, List<String> params) {
        return new McFunction(cloneEnvironment(newFunctionName), callable, params);
    }

    public void compileBlocks(List<Block> lines) {
        for (Block block : lines) {
            com.moddingfromamod.cbscript.py.Cancelled.check();
            try {
                block.compile(this);
            } catch (PyException e) {
                if (e.isA("CompileError")) {
                    throw Ctx.except(e, () -> {
                        Ctx.print(e.str());
                        return Ctx.compileError("Error compiling block at line " + block.line());
                    });
                }
                throw Ctx.except(e, () -> {
                    Ctx.printTraceback(e);
                    return Ctx.compileError("Error compiling block at line " + block.line());
                });
            } catch (StackOverflowError e) {
                PyException r = new PyException("RecursionError", "maximum recursion depth exceeded");
                throw Ctx.except(r, () -> {
                    Ctx.printTraceback(r);
                    return Ctx.compileError("Error compiling block at line " + block.line());
                });
            } catch (RuntimeException e) {
                PyException w = Ctx.wrap(e);
                throw Ctx.except(w, () -> {
                    Ctx.printTraceback(w);
                    return Ctx.compileError("Error compiling block at line " + block.line());
                });
            }
        }
    }

    public void importFile(String filename) {
        globalContext().registerDependency(filename);
        String text = Ctx.get().files.read(filename);
        if (text == null) {
            PyException e = new PyException("FileNotFoundError",
                    "[Errno 2] No such file or directory: " + PyException.reprString(Ctx.get().files.absolute(filename)));
            e.reprArgs = "2, 'No such file or directory'";
            throw e;
        }
        Object result = globalContext().parser.parse("import " + text + "\n");
        if (result == null) {
            throw Ctx.compileError("Unable to parse file \"" + filename + "\"");
        }
        Object[] r = (Object[]) result;
        if (!r[0].equals("lib")) {
            throw Ctx.compileError("Unable to import non-lib-file \"" + filename + "\"");
        }
        @SuppressWarnings("unchecked")
        List<Block> lines = (List<Block>) ((Map<String, Object>) r[1]).get("lines");
        for (Block line : lines) {
            line.register(globalContext());
        }
        compileBlocks(lines);
    }

    /** import name.py: running a Python file is not something this compiler does (JAVA_PORT.md, Boundary). */
    public void importPythonFile(String filename) {
        globalContext().registerDependency(filename);
        String text = Ctx.get().files.read(filename);
        PyException e = text == null
                ? new PyException("FileNotFoundError", "[Errno 2] No such file or directory: " + PyException.reprString(filename))
                : new PyException("NotImplementedError", "importing a Python file is not supported by the Java compiler");
        if (text == null) {
            e.reprArgs = "2, 'No such file or directory'";
        }
        throw Ctx.except(e, () -> {
            Ctx.print(e.str());
            return Ctx.compileError(text == null ? "Unable to open \"" + filename + "\"" : "Unable to execute \"" + filename + "\"");
        });
    }

    public PyObject eval(String expr, int line) {
        try {
            return com.moddingfromamod.cbscript.py.PyEval.eval(expr, environment.dollarid, Ctx::print);
        } catch (PyException e) {
            if (!e.isA("Exception")) {
                throw e;
            }
            throw Ctx.except(e, () -> {
                Ctx.print(e.str());
                return Ctx.compileError("Could not evaluate python expression \"" + expr + "\" at line " + line);
            });
        } catch (StackOverflowError e) {
            PyException r = new PyException("RecursionError", "maximum recursion depth exceeded");
            throw Ctx.except(r, () -> {
                Ctx.print(r.str());
                return Ctx.compileError("Could not evaluate python expression \"" + expr + "\" at line " + line);
            });
        }
    }

    public void callFunction(McFunction sub, String subName, String prefix) {
        if (sub.isEmpty()) {
            return;
        }
        String single = sub.singleCommand();
        if (single != null && !single.isEmpty()) {
            if (single.startsWith("$")) {
                addCommand("$" + prefix + single.substring(1));
            } else {
                addCommand(prefix + single);
            }
        } else {
            int unique = getUniqueId();
            String name = subName + "_" + unique;
            registerFunction(name, sub);
            addCommand(prefix + sub.getCall());
        }
    }

    public McFunction getResetFunction() {
        return globalContext().getResetFunction();
    }

    public void registerClock(String id) {
        globalContext().registerClock(id);
    }

    public GlobalContext globalContext() {
        return environment.globalContext;
    }

    public void copyEnvironmentFrom(McFunction func) {
        environment = func.environment.cloneEnv(null);
    }

    public String name() {
        return environment.functionName;
    }

    public List<Var> getLocalVariables() {
        List<Var> out = new ArrayList<>();
        for (String l : environment.getAllLocals()) {
            out.add(new ScoreboardVar("Global", l));
        }
        return out;
    }

    public void pushLocals(List<? extends Object> locals) {
        new Ast.PushBlock(0, toExprs(locals)).compile(this);
    }

    public void popLocals(List<? extends Object> locals) {
        new Ast.PopBlock(0, toVars(locals)).compile(this);
    }

    private static List<Ast.Expr> toExprs(List<? extends Object> locals) {
        List<Ast.Expr> out = new ArrayList<>();
        for (Object o : locals) {
            if (o instanceof Ast.Expr e) {
                out.add(e);
            } else {
                out.add(new Ast.StrExpr((String) o));
            }
        }
        return out;
    }

    private static List<Var> toVars(List<? extends Object> locals) {
        List<Var> out = new ArrayList<>();
        for (Object o : locals) {
            if (o instanceof Var v) {
                out.add(v);
            } else {
                out.add(new Ast.StrVar((String) o));
            }
        }
        return out;
    }

    /** The get_num_blocks / get_num_block_states callables the program sees as $-ids. */
    public static PyFunction boundMethod(String name, java.util.function.IntSupplier body) {
        return new PyFunction(name, (args, kwargs) -> {
            if (!args.isEmpty() || !kwargs.isEmpty()) {
                throw new PyException("TypeError", "global_context." + name + "() takes 1 positional argument but "
                        + (args.size() + 1) + " were given");
            }
            return Py.of(body.getAsInt());
        });
    }

    static PyObject floatOf(PyObject v) {
        return (PyFloat) Py.toFloat(v);
    }

    static BigInteger intOf(PyObject v) {
        return ((PyInt) Py.toInt(v)).value();
    }
}
