// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.Map;

/** mc_target.py: which Minecraft this compile is for, and the facts that follow from it. */
public final class McTarget {
    public static final String DEFAULT = "1.21.1";

    /** pack_format per release, newest first; a version takes the first row it reaches. */
    private static final int[][][] PACK_FORMATS = {
            {{1, 21, 7}, {81}},
            {{1, 21, 6}, {80}},
            {{1, 21, 5}, {71}},
            {{1, 21, 4}, {61}},
            {{1, 21, 2}, {57}},
            {{0}, {48}},
    };

    static final int[] FALL_DISTANCE_SNAKE_SINCE = {1, 21, 5};

    private final int[] version;

    public McTarget(String raw) {
        this.version = parse(raw == null ? DEFAULT : raw);
    }

    /** version(): the target as a tuple; an unparseable value is treated as the default. */
    static int[] parse(String raw) {
        String[] parts = raw.split("\\.", -1);
        int n = Math.min(3, parts.length);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            Integer v = pyInt(parts[i]);
            if (v == null) {
                return parse(DEFAULT);
            }
            out[i] = v;
        }
        return out;
    }

    /** Python int(str) for the small values a version holds; null where Python raises ValueError. */
    private static Integer pyInt(String s) {
        String t = com.moddingfromamod.cbscript.lex.PyStr.strip(s);
        if (t.startsWith("+") || t.startsWith("-")) {
            String rest = t.substring(1);
            Integer v = digits(rest);
            return v == null ? null : (t.charAt(0) == '-' ? -v : v);
        }
        return digits(t);
    }

    private static Integer digits(String t) {
        if (t.isEmpty() || t.startsWith("_") || t.endsWith("_") || t.contains("__")) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int c = t.codePointAt(i);
            i += Character.charCount(c);
            if (c == '_') {
                continue;
            }
            int d = Character.digit(c, 10);
            if (d < 0 || Character.getType(c) != Character.DECIMAL_DIGIT_NUMBER) {
                return null;
            }
            b.append((char) ('0' + d));
        }
        try {
            return Integer.parseInt(b.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Python tuple comparison {@code version >= since}. */
    public boolean atLeast(int... since) {
        int n = Math.min(version.length, since.length);
        for (int i = 0; i < n; i++) {
            if (version[i] != since[i]) {
                return version[i] > since[i];
            }
        }
        return version.length >= since.length;
    }

    public int packFormat() {
        for (int[][] row : PACK_FORMATS) {
            if (atLeast(row[0])) {
                return row[1][0];
            }
        }
        return PACK_FORMATS[PACK_FORMATS.length - 1][1][0];
    }

    /** pack.mcmeta's "pack" object (insertion ordered). */
    public Map<String, Object> packMeta(String desc) {
        Map<String, Object> pack = new java.util.LinkedHashMap<>();
        pack.put("pack_format", (long) packFormat());
        pack.put("description", desc);
        return pack;
    }

    public String maxChainRule() {
        return "maxCommandChainLength";
    }

    /** Entity NBT fields the target names differently: old -> {new, numeric suffix of its type}. */
    public Map<String, String[]> entityNbtRenames() {
        return atLeast(FALL_DISTANCE_SNAKE_SINCE) ? Map.of("FallDistance", new String[] {"fall_distance", "d"}) : Map.of();
    }
}
