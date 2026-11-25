// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** scratch_tracker.py. */
public final class ScratchTracker {
    private final Map<Integer, Boolean> scratch = new LinkedHashMap<>();
    private final Map<Integer, Boolean> temp = new LinkedHashMap<>();
    private final GlobalContext globalContext;
    private int scratchAllocation = 0;
    private int tempAllocation = 0;
    public String prefix = "";

    public ScratchTracker(GlobalContext globalContext) {
        this.globalContext = globalContext;
    }

    public String getTempVar() {
        for (Map.Entry<Integer, Boolean> e : temp.entrySet()) {
            if (!e.getValue()) {
                e.setValue(true);
                return "temp" + e.getKey();
            }
        }
        int n = temp.size();
        temp.put(n, true);
        int newLength = temp.size();
        if (newLength > tempAllocation) {
            tempAllocation = newLength;
            globalContext.allocateTemp(newLength);
        }
        return "temp" + n;
    }

    public String getScratch() {
        for (Map.Entry<Integer, Boolean> e : scratch.entrySet()) {
            if (!e.getValue()) {
                e.setValue(true);
                return prefix + "_scratch" + e.getKey();
            }
        }
        int n = scratch.size();
        scratch.put(n, true);
        int newLength = scratch.size();
        if (newLength > scratchAllocation) {
            scratchAllocation = newLength;
            globalContext.allocateScratch(prefix, newLength);
        }
        return prefix + "_scratch" + n;
    }

    public String getPrefix() {
        return prefix + "_scratch";
    }

    public boolean isScratch(String id) {
        return id.startsWith(getPrefix());
    }

    public void freeScratch(String id) {
        if (!isScratch(id)) {
            return;
        }
        String rest = id.substring(getPrefix().length());
        int num = ((com.moddingfromamod.cbscript.py.PyInt) com.moddingfromamod.cbscript.py.Py.toInt(
                com.moddingfromamod.cbscript.py.Py.of(rest))).value().intValueExact();
        scratch.put(num, false);
    }

    public List<String> getAllocatedVariables() {
        List<String> ret = new ArrayList<>();
        for (int i = 0; i < scratchAllocation; i++) {
            ret.add(prefix + "_scratch" + i);
        }
        for (int i = 0; i < tempAllocation; i++) {
            ret.add("temp" + i);
        }
        return ret;
    }

    public List<String> getActiveObjectives() {
        List<String> all = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> e : scratch.entrySet()) {
            if (e.getValue()) {
                all.add(prefix + "_scratch" + e.getKey());
            }
        }
        for (Map.Entry<Integer, Boolean> e : temp.entrySet()) {
            if (e.getValue()) {
                all.add("temp" + e.getKey());
            }
        }
        return all;
    }
}
