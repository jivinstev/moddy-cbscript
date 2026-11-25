// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.cbscript.py.PyException;

/**
 * loot_syntax.py: an item modifier in the target's loot-function shape. Up to 26.2 it is written exactly as the script
 * wrote it; from 26.3 a loot function's "function" key is renamed to "type" (the old key stops the server loading any
 * data pack), and the shapes that cannot be rewritten mechanically are warned about.
 */
final class ItemModifiers {
    private ItemModifiers() {
    }

    private static void warn(String key, String text) {
        if (Ctx.get().lootWarned.add(key)) {
            Ctx.print("Warning: " + text);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object rename(Object obj, String key) {
        if (!(obj instanceof Map) || !((Map<String, Object>) obj).containsKey("function") || ((Map<String, Object>) obj).containsKey(key)) {
            return obj;
        }
        Map<String, Object> m = (Map<String, Object>) obj;
        if (m.containsKey("conditions")) {
            warn("conditions", "an item modifier's \"conditions\" list is not read on this Minecraft: write one "
                    + "\"condition\": {\"type\": \"minecraft:...\", ...} (several: {\"type\": \"minecraft:all_of\", \"terms\": [...]}).");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey().equals("function") ? key : e.getKey(), e.getValue());
        }
        return out;
    }

    static String fix(String text) {
        String key = Ctx.get().target.lootFunctionKey();
        if (key.equals("function")) {
            return text;
        }
        Object data;
        try {
            data = Json.loads(text);
        } catch (PyException e) {
            return text;
        }
        Object fixed;
        if (data instanceof List<?> l) {
            warn("list", "an item modifier that is a list of functions is not read on this Minecraft: write one "
                    + "{\"type\": \"minecraft:sequence\", \"functions\": [...]}.");
            List<Object> out = new ArrayList<>();
            for (Object f : l) {
                out.add(rename(f, key));
            }
            fixed = out;
        } else {
            fixed = rename(data, key);
        }
        if (fixed.equals(data)) {
            return text;
        }
        return Json.dumpsCompact(fixed);
    }
}
