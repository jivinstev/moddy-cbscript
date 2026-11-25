// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyException;
import com.moddingfromamod.cbscript.py.PyFloat;
import com.moddingfromamod.cbscript.py.PyInt;
import com.moddingfromamod.cbscript.py.PyObject;
import com.moddingfromamod.cbscript.py.PyStr;

/** environment.py. */
public final class Environment {
    public final Map<String, PyObject> dollarid = new LinkedHashMap<>();
    public final GlobalContext globalContext;
    public ScratchTracker scratch;
    public List<String> locals = new ArrayList<>();
    public final Map<String, SelectorDefinition> selectors = new LinkedHashMap<>();
    public SelectorDefinition selfSelector;
    public final Map<String, String> nameDefinitions = new LinkedHashMap<>();
    public final Map<String, String> pointers = new LinkedHashMap<>();
    public final Map<String, Ast.BlockDefinition> blockDefinitions = new LinkedHashMap<>();
    public String functionName;

    public Environment(GlobalContext globalContext) {
        this.globalContext = globalContext;
        this.scratch = new ScratchTracker(globalContext);
    }

    public Environment cloneEnv(String newFunctionName) {
        Environment e = new Environment(globalContext);
        e.selectors.putAll(selectors);
        e.blockDefinitions.putAll(blockDefinitions);
        e.pointers.putAll(pointers);
        e.dollarid.putAll(dollarid);
        e.nameDefinitions.putAll(nameDefinitions);
        if (newFunctionName == null) {
            e.scratch = scratch;
            e.locals = locals;
            e.functionName = functionName;
        } else {
            e.scratch.prefix = globalContext.getScratchPrefix(newFunctionName);
            e.functionName = newFunctionName;
        }
        e.selfSelector = selfSelector;
        return e;
    }

    public void registerLocal(String local) {
        if (!locals.contains(local)) {
            locals.add(local);
        }
    }

    public String apply(String text) {
        text = applyReplacements(text, Map.of());
        return compileSelectors(text);
    }

    // ── isNumber / isInt (environment.py) ────────────────────────────────────

    public static boolean isNumber(PyObject s) {
        try {
            double v = ((PyFloat) Py.toFloat(s)).value();
            return !Double.isInfinite(v) && !Double.isNaN(v);
        } catch (PyException e) {
            if (!e.isA("Exception")) {
                throw e;
            }
            return false;
        }
    }

    public static boolean isInt(PyObject s) {
        try {
            if (s instanceof PyStr str) {
                return str.value().equals(Py.toInt(s).str());
            }
            Py.toInt(s);
            return true;
        } catch (PyException e) {
            if (!e.isA("Exception")) {
                throw e;
            }
            return false;
        }
    }

    public String applyReplacements(String text, Map<String, PyObject> overrides) {
        Map<String, PyObject> replacements = new LinkedHashMap<>(dollarid);
        replacements.putAll(overrides);
        List<String> keys = new ArrayList<>(replacements.keySet());
        keys.sort(Environment::compareCodePoints);
        Collections.reverse(keys);
        for (String id : keys) {
            PyObject v = replacements.get(id);
            if (isInt(v)) {
                text = text.replace("-$" + id, Py.neg(Py.toInt(v)).str());
            } else if (isNumber(v)) {
                text = text.replace("-$" + id, Py.neg(Py.toFloat(v)).str());
            }
            text = text.replace("$" + id, v.str());
        }
        return text;
    }

    /** Python's str ordering: by code point. */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    public void setDollarid(String id, PyObject val) {
        if (id.isEmpty()) {
            throw new PyException("Exception", "Dollar ID is empty string.");
        }
        if (id.charAt(0) == '$') {
            id = id.substring(1);
        }
        dollarid.put(id, val);
    }

    public PyObject getDollarid(String id) {
        if (id.isEmpty()) {
            throw new PyException("Exception", "Dollar ID is empty string.");
        }
        if (id.charAt(0) == '$') {
            id = id.substring(1);
        }
        PyObject v = dollarid.get(id);
        if (v == null) {
            PyException e = new PyException("KeyError", PyException.reprString(id));
            e.reprArgs = PyException.reprString(id);
            throw e;
        }
        return v;
    }

    public SelectorDefinition setAtid(String id, String fullselector) {
        SelectorDefinition s = new SelectorDefinition(fullselector, this);
        selectors.put(id, s);
        return s;
    }

    public void registerNameDefinition(String id, String str) {
        nameDefinitions.put(id, str);
    }

    public String getNameDefinition(String id) {
        return nameDefinitions.get(id);
    }

    public String compileSelectors(String command) {
        StringBuilder ret = new StringBuilder();
        for (String fragment : splitSelectors(command)) {
            if (fragment.charAt(0) == '@') {
                ret.append(compileSelector(fragment));
            } else {
                ret.append(fragment);
            }
        }
        return ret.toString();
    }

    public String compileSelector(String selector) {
        SelectorDefinition sel = new SelectorDefinition(selector, this);
        String interpreted = sel.compile();
        if (PyText.len(interpreted) == 4) {
            interpreted = PyText.slice(interpreted, 0, 2);
        }
        return interpreted;
    }

    public void registerObjective(String objective) {
        globalContext.registerObjective(objective);
    }

    public static List<String> splitSelectors(String line) {
        List<String> fragments = new ArrayList<>();
        String remaining = line;
        while (remaining.indexOf('@') >= 0) {
            int at = remaining.indexOf('@');
            String before = remaining.substring(0, at);
            String after = remaining.substring(at + 1);
            if (!before.isEmpty()) {
                fragments.add(before);
            }
            int end = 0;
            for (int i = 0; i < after.length(); i++) {
                char c = after.charAt(i);
                if (isAlnumChar(after, i) || c == '_') {
                    end++;
                } else if (c == '[') {
                    int count = 1;
                    for (int j = i + 1; j < after.length(); j++) {
                        if (after.charAt(j) == '[') {
                            count++;
                        }
                        if (after.charAt(j) == ']') {
                            count--;
                        }
                        if (count == 0) {
                            end = j + 1;
                            break;
                        }
                    }
                    break;
                } else {
                    break;
                }
            }
            fragments.add("@" + after.substring(0, end));
            remaining = after.substring(end);
        }
        if (!remaining.isEmpty()) {
            fragments.add(remaining);
        }
        return fragments;
    }

    /** Python {@code ch.isalnum()} for the char at i (a surrogate half is never alphanumeric on its own). */
    private static boolean isAlnumChar(String s, int i) {
        char c = s.charAt(i);
        if (Character.isSurrogate(c)) {
            return false;
        }
        return com.moddingfromamod.cbscript.lex.PyStr.isAlnum(c);
    }

    public void updateSelfSelector(String selector) {
        if (selector.charAt(0) != '@') {
            return;
        }
        String id = selector.substring(1);
        if (id.contains("[")) {
            id = id.split("\\[", 2)[0];
        }
        if (selectors.containsKey(id)) {
            selfSelector = selectors.get(id);
        }
    }

    public SelectorDefinition getSelectorDefinition(String selectorText) {
        if (selectorText.startsWith("@")) {
            return new SelectorDefinition(selectorText, this);
        }
        return null;
    }

    public Ast.BlockDefinition getBlockDefinition(String blockId) {
        if (!blockDefinitions.containsKey(blockId)) {
            throw Ctx.compileError("[" + blockId + "] is not defined.");
        }
        return blockDefinitions.get(blockId);
    }

    public List<String> getAllLocals() {
        List<String> all = new ArrayList<>(locals);
        all.addAll(scratch.getActiveObjectives());
        return all;
    }

    public PyObject getScale() {
        return globalContext.scale;
    }
}
