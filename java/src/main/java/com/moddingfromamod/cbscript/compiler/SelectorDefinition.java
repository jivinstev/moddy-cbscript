// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.cbscript.lex.PyStr;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyObject;

/** selector_definition.py. */
public final class SelectorDefinition {
    /** A data path: (path, data type, scale). */
    public record Path(String path, String dataType, PyObject scale) {
    }

    /** One selector argument; a 2-list in Python when added for a predicate, a tuple otherwise. */
    public record Part(String name, String value, boolean list) {
    }

    static final Set<String> REPEATABLE_ARGS = Set.of("type", "tag", "nbt", "predicate", "team", "advancements", "gamemode");

    public List<Part> parts = new ArrayList<>();
    public final Map<String, BigInteger> scoresMin = new LinkedHashMap<>();
    public final Map<String, BigInteger> scoresMax = new LinkedHashMap<>();
    public String tag;
    public final Map<String, Path> paths = new LinkedHashMap<>();
    public final Map<String, Path> vectorPaths = new LinkedHashMap<>();
    public final Map<String, String> pointers = new LinkedHashMap<>();
    public String uuid;
    public final Map<String, Boolean> predicates = new LinkedHashMap<>();
    public String baseName;

    static String[] splitQualifier(String qualifier) {
        for (String op : new String[] {"==", "<=", ">=", "<", ">"}) {
            int i = qualifier.indexOf(op);
            if (i >= 0) {
                return new String[] {PyStr.strip(qualifier.substring(0, i)), op,
                        PyStr.strip(qualifier.substring(i + op.length()))};
            }
        }
        return null;
    }

    static boolean isNumber(String s) {
        try {
            Py.toFloat(Py.of(s));
            return true;
        } catch (PyException e) {
            if (e.isA("ValueError")) {
                return false;
            }
            throw e;
        }
    }

    public SelectorDefinition(String selector, Environment env) {
        selector = env.applyReplacements(selector, Map.of());
        String base = selector;
        if (PyText.first(base).equals("@")) {
            base = base.substring(1);
        }
        if (base.contains("[")) {
            base = base.split("\\[", -1)[0];
        }
        SelectorDefinition b = env.selectors.get(base);
        if (b != null) {
            baseName = b.baseName;
            parts.addAll(b.parts);
            scoresMin.putAll(b.scoresMin);
            scoresMax.putAll(b.scoresMax);
            paths.putAll(b.paths);
            vectorPaths.putAll(b.vectorPaths);
            pointers.putAll(b.pointers);
            predicates.putAll(b.predicates);
            tag = b.tag;
            uuid = b.uuid;
        } else {
            if (PyText.len(base) != 1) {
                throw Ctx.compileError("Tried to create selector with base name @" + base);
            }
            baseName = base;
        }

        if (selector.contains("[") && selector.contains("]")) {
            String inner = selector.substring(selector.indexOf('[') + 1, Math.max(selector.indexOf('[') + 1, selector.lastIndexOf(']')));
            if (selector.lastIndexOf(']') < selector.indexOf('[') + 1) {
                inner = "";
            }
            List<String> partList = new ArrayList<>();
            for (String p : inner.split(",", -1)) {
                partList.add(PyStr.strip(p));
            }
            if (partList.size() == 1) {
                try {
                    BigInteger index = ((PyInt) Py.toInt(Py.of(partList.get(0)))).value();
                    BigInteger h = BigInteger.valueOf(PyHash.hash(base)).mod(BigInteger.ONE.shiftLeft(32));
                    uuid = "0-0-0-0-" + index.add(h).toString(16).toUpperCase();
                    return;
                } catch (PyException e) {
                    // bare except: None
                }
            }
            String nbtPart = null;
            int lbrack = 0;
            int rbrack = 0;
            for (String part : partList) {
                if (part.isEmpty()) {
                    continue;
                }
                if (part.startsWith("nbt") || part.startsWith("scores")) {
                    lbrack = count(part, '{');
                    rbrack = count(part, '}');
                    if (rbrack < lbrack) {
                        nbtPart = part;
                        continue;
                    }
                }
                if (nbtPart != null) {
                    lbrack += count(part, '{');
                    rbrack += count(part, '}');
                    if (rbrack < lbrack) {
                        nbtPart = nbtPart + "," + part;
                        continue;
                    } else {
                        part = nbtPart + "," + part;
                        nbtPart = null;
                    }
                }
                String[] op = splitQualifier(part);
                if (op == null) {
                    if (part.contains("=")) {
                        String[] raw = part.split("=", -1);
                        List<String> sub = new ArrayList<>();
                        for (String s : raw) {
                            sub.add(PyStr.strip(s));
                        }
                        if (sub.get(0).equals("scores")) {
                            String joined = String.join("=", sub.subList(1, sub.size()));
                            sub = new ArrayList<>(List.of(sub.get(0), joined));
                        }
                        String type = sub.get(1);
                        String bare = type.startsWith("!") ? type.substring(1) : type;
                        boolean namespaced = bare.contains(":");
                        if (sub.get(0).equals("type") && !namespaced) {
                            if (sub.get(1).startsWith("!")) {
                                if (env.globalContext.entityTags.containsKey(type)) {
                                    sub.set(1, "!#" + env.globalContext.namespace + ":" + sub.get(1).substring(1));
                                } else {
                                    sub.set(1, "!minecraft:" + sub.get(1).substring(1));
                                }
                            } else {
                                if (env.globalContext.entityTags.containsKey(type)) {
                                    sub.set(1, "#" + env.globalContext.namespace + ":" + sub.get(1));
                                } else {
                                    sub.set(1, "minecraft:" + sub.get(1));
                                }
                            }
                        }
                        setPart(sub.get(0), sub.get(1));
                    } else {
                        if (PyText.len(part) >= 5 && PyText.slice(part, 0, 4).toUpperCase(java.util.Locale.ROOT).equals("NOT ")) {
                            String name = PyText.from(part, 4);
                            scoresMax.put(name, BigInteger.ZERO);
                            env.registerObjective(name);
                        } else if (predicates.containsKey(part)) {
                            if (part.contains(":")) {
                                parts.add(new Part("predicate", part, true));
                            } else {
                                parts.add(new Part("predicate", env.globalContext.namespace + ":" + part, true));
                            }
                        } else {
                            scoresMin.put(part, BigInteger.ONE);
                            env.registerObjective(part);
                        }
                    }
                } else {
                    String before = op[0];
                    String o = op[1];
                    String after = op[2];
                    if (!isNumber(after)) {
                        throw Ctx.syntaxError("\"" + after + "\" is not a number in \"" + selector + "\"");
                    }
                    BigInteger a = ((PyInt) Py.toInt(Py.of(after))).value();
                    switch (o) {
                        case "==" -> {
                            scoresMin.put(before, a);
                            scoresMax.put(before, a);
                        }
                        case "<=" -> scoresMax.put(before, a);
                        case ">=" -> scoresMin.put(before, a);
                        case "<" -> scoresMax.put(before, a.subtract(BigInteger.ONE));
                        case ">" -> scoresMin.put(before, a.add(BigInteger.ONE));
                        default -> {
                        }
                    }
                    env.registerObjective(before);
                }
            }
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    public String compile() {
        if (uuid != null && !uuid.isEmpty()) {
            return uuid;
        }
        List<String> major = new ArrayList<>();
        String base = baseName;
        List<Part> ps = parts;
        if (base.equals("e")) {
            int idx = indexOfTuple(ps, "type", "minecraft:player");
            if (idx >= 0) {
                ps.remove(idx);
                base = "a";
            }
        }
        if (!ps.isEmpty()) {
            List<String> joined = new ArrayList<>();
            for (Part p : ps) {
                joined.add(p.name() + "=" + p.value());
            }
            major.add(String.join(",", joined));
        }
        if (!scoresMin.isEmpty() || !scoresMax.isEmpty()) {
            List<String> score = new ArrayList<>();
            for (Map.Entry<String, BigInteger> e : scoresMin.entrySet()) {
                String var = e.getKey();
                if (scoresMax.containsKey(var)) {
                    if (e.getValue().equals(scoresMax.get(var))) {
                        score.add(var + "=" + e.getValue());
                    } else {
                        score.add(var + "=" + e.getValue() + ".." + scoresMax.get(var));
                    }
                } else {
                    score.add(var + "=" + e.getValue() + "..");
                }
            }
            for (Map.Entry<String, BigInteger> e : scoresMax.entrySet()) {
                if (!scoresMin.containsKey(e.getKey())) {
                    score.add(e.getKey() + "=.." + e.getValue());
                }
            }
            major.add("scores={" + String.join(",", score) + "}");
        }
        return "@" + base + "[" + String.join(",", major) + "]";
    }

    /** {@code ('type', 'minecraft:player') in parts}: a tuple equals a tuple only, never a list. */
    private static int indexOfTuple(List<Part> ps, String name, String value) {
        for (int i = 0; i < ps.size(); i++) {
            Part p = ps.get(i);
            if (!p.list() && p.name().equals(name) && p.value().equals(value)) {
                return i;
            }
        }
        return -1;
    }

    public String getType() {
        for (Part p : parts) {
            if (p.name().equals("type")) {
                return p.value();
            }
        }
        return null;
    }

    public void setPart(String name, String value) {
        if (REPEATABLE_ARGS.contains(name)) {
            if (indexOfTuple(parts, name, value) < 0) {
                parts.add(new Part(name, value, false));
            }
            return;
        }
        List<Part> kept = new ArrayList<>();
        for (Part p : parts) {
            if (!p.name().equals(name)) {
                kept.add(p);
            }
        }
        parts = kept;
        parts.add(new Part(name, value, false));
    }

    public boolean singleEntity() {
        if (uuid != null && !uuid.isEmpty()) {
            return true;
        }
        if (baseName.equals("s") || baseName.equals("p")) {
            return true;
        }
        for (Part p : parts) {
            if (p.name().equals("limit") && p.value().equals("1")) {
                return true;
            }
        }
        return false;
    }
}
