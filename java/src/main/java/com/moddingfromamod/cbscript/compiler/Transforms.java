// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.moddingfromamod.cbscript.lex.PyRe;
import com.moddingfromamod.cbscript.lex.PyStr;

/**
 * The corrections every command passes through: nbt_case.py, attribute_ids.py and component_syntax.py, keyed on the
 * target version as those are keyed on CBSCRIPT_MC. Each warns once per compile (Python: once per process).
 */
final class Transforms {
    private Transforms() {
    }

    // ── nbt_case.py ──────────────────────────────────────────────────────────

    static final List<String> FIELDS = List.of(
            "OnGround", "FallDistance", "Motion", "Rotation", "Invulnerable", "NoGravity",
            "Silent", "CustomName", "CustomNameVisible", "Health", "Fire", "Air", "Glowing",
            "PortalCooldown", "Passengers", "Tags");
    static final Pattern NBT_BLOCK = PyRe.compile("nbt=\\{[^}]*\\}");
    private static final Map<String, Pattern> FIELD_PATTERNS = new LinkedHashMap<>();

    static {
        for (String f : FIELDS) {
            FIELD_PATTERNS.put(f, PyRe.compile("([{,]\\s*)(" + f + ")(\\s*:)", PyRe.IGNORECASE));
        }
    }

    static String fixNbtCase(String command) {
        if (command == null || command.isEmpty() || !command.contains("nbt=")) {
            return command;
        }
        Ctx ctx = Ctx.get();
        Map<String, String[]> renames = ctx.target.entityNbtRenames();
        return PyRe.sub(NBT_BLOCK, command, match -> {
            String block = match.group();
            for (Map.Entry<String, String[]> r : renames.entrySet()) {
                String old = r.getKey();
                String nw = r.getValue()[0];
                String kind = r.getValue()[1];
                Pattern pattern = PyRe.compile("([{,]\\s*)(" + old + "|" + nw + ")(\\s*:\\s*)(-?(?:\\d+\\.?\\d*|\\.\\d+)[bBsSlLfFdD]?)?",
                        PyRe.IGNORECASE);
                block = PyRe.sub(pattern, block, m -> {
                    String value = m.group(4) == null ? "" : m.group(4);
                    if (!value.isEmpty() && kind.equals("d")) {
                        char last = value.charAt(value.length() - 1);
                        if (last != 'd' && last != 'D' && (Character.isLetter(last) || !value.contains("."))) {
                            value = (Character.isLetter(last) ? value.substring(0, value.length() - 1) : value) + "d";
                        }
                    }
                    String orig = m.group(4) == null ? "" : m.group(4);
                    if (!m.group(2).equals(nw) || !value.equals(orig)) {
                        List<String> key = List.of(m.group(2), nw);
                        if (ctx.nbtWarned.add(key)) {
                            Ctx.print("Warning: \"" + m.group(2) + "\" should be \"" + nw + "\" (a double, e.g. " + nw + ":0d) on this "
                                    + "Minecraft — nbt={...} matching is case- and type-sensitive and the old form "
                                    + "matches nothing. Corrected.");
                        }
                    }
                    return m.group(1) + nw + m.group(3) + value;
                });
            }
            for (String field : FIELDS) {
                if (renames.containsKey(field)) {
                    continue;
                }
                block = PyRe.sub(FIELD_PATTERNS.get(field), block, m -> {
                    if (!m.group(2).equals(field)) {
                        List<String> key = List.of(m.group(2), field);
                        if (ctx.nbtWarned.add(key)) {
                            Ctx.print("Warning: \"" + m.group(2) + "\" should be \"" + field + "\" — vanilla NBT field "
                                    + "names are CamelCase and nbt={...} matching is case-sensitive, so "
                                    + "the lower-case form matches nothing and the rule never fires. "
                                    + "Corrected.");
                        }
                    }
                    return m.group(1) + field + m.group(3);
                });
            }
            return block;
        });
    }

    // ── attribute_ids.py ─────────────────────────────────────────────────────

    static final List<String> QUALIFIED = List.of(
            "generic.armor", "generic.armor_toughness", "generic.attack_damage",
            "generic.attack_knockback", "generic.attack_speed", "generic.burning_time",
            "generic.explosion_knockback_resistance", "generic.fall_damage_multiplier",
            "generic.flying_speed", "generic.follow_range", "generic.gravity",
            "generic.jump_strength", "generic.knockback_resistance", "generic.luck",
            "generic.max_absorption", "generic.max_health", "generic.movement_efficiency",
            "generic.movement_speed", "generic.oxygen_bonus", "generic.safe_fall_distance",
            "generic.scale", "generic.step_height", "generic.water_movement_efficiency",
            "player.block_break_speed", "player.block_interaction_range",
            "player.entity_interaction_range", "player.mining_efficiency", "player.sneaking_speed",
            "player.submerged_mining_speed", "player.sweeping_damage_ratio",
            "zombie.spawn_reinforcements");
    static final Map<String, String> BY_SHORT = new LinkedHashMap<>();
    static final Map<String, String> BY_QUALIFIED = new LinkedHashMap<>();

    static {
        for (String q : QUALIFIED) {
            BY_SHORT.putIfAbsent(q.split("\\.", 2)[1], q);
            BY_QUALIFIED.put(q, q.split("\\.", 2)[1]);
        }
    }

    static final Pattern ATTRIBUTE_COMMAND = PyRe.compile("(\\battribute\\s+\\S+\\s+)([a-z0-9_.:]+)", PyRe.IGNORECASE);
    static final Pattern ATTRIBUTES_NBT = PyRe.compile("attributes\\s*:\\s*\\[[^\\]]*\\]", PyRe.IGNORECASE);
    static final Pattern NBT_ID = PyRe.compile("(\\bid\\s*:\\s*\")([a-z0-9_.:]+)(\")", PyRe.IGNORECASE);

    private static String corrected(String raw) {
        boolean namespaced = raw.contains(":");
        String name = namespaced ? raw.split(":", 2)[1] : raw;
        String real;
        if (Ctx.get().target.atLeast(1, 21, 2)) {
            real = BY_QUALIFIED.get(name.toLowerCase(java.util.Locale.ROOT));
        } else {
            if (QUALIFIED.contains(name)) {
                return null;
            }
            real = BY_SHORT.get(name.toLowerCase(java.util.Locale.ROOT));
        }
        if (real == null) {
            return null;
        }
        return namespaced ? "minecraft:" + real : real;
    }

    private static void warnAttribute(String was, String now) {
        if (Ctx.get().attributeWarned.add(List.of(was, now))) {
            Ctx.print("Warning: \"" + was + "\" should be \"" + now + "\" on this Minecraft — the other spelling makes the "
                    + "whole FUNCTION fail to load while the pack still reports as enabled. Corrected.");
        }
    }

    static String fixAttributeIds(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        if (!command.toLowerCase(java.util.Locale.ROOT).contains("attribute")) {
            return command;
        }
        command = PyRe.sub(ATTRIBUTE_COMMAND, command, m -> {
            String fixed = corrected(m.group(2));
            if (fixed == null) {
                return m.group();
            }
            warnAttribute(m.group(2), fixed);
            return m.group(1) + fixed;
        });
        return PyRe.sub(ATTRIBUTES_NBT, command, block -> PyRe.sub(NBT_ID, block.group(), m -> {
            String fixed = corrected(m.group(2));
            if (fixed == null) {
                return m.group();
            }
            warnAttribute(m.group(2), fixed);
            return m.group(1) + fixed + m.group(3);
        }));
    }

    // ── component_syntax.py ──────────────────────────────────────────────────

    private static boolean newSyntax() {
        return Ctx.get().target.atLeast(1, 21, 5);
    }

    private static void warnComponent(String was, String now) {
        List<String> key = List.of(PyText.slice(was, 0, 40), PyText.slice(now, 0, 40));
        if (Ctx.get().componentWarned.add(key)) {
            Ctx.print("component syntax: \"" + was + "\" is the pre-1.21.5 spelling; wrote \"" + now + "\"");
        }
    }

    static final Pattern DYED = PyRe.compile(
            "((?:minecraft:)?dyed_color)=\\{\\s*rgb\\s*:\\s*(-?\\d+)\\s*(?:,\\s*show_in_tooltip\\s*:\\s*\\w+\\s*)?\\}");
    static final Pattern TEXT_ONE = PyRe.compile(
            "((?:minecraft:)?(?:custom_name|item_name)=|\\b(?:CustomName|custom_name)\\s*:\\s*)'((?:[^'\\\\]|\\\\.)*)'");
    static final Pattern LORE = PyRe.compile("(?:(?:minecraft:)?lore=|\\b(?:messages|pages|Lore)\\s*:\\s*)\\[");
    static final Pattern QUOTED = PyRe.compile("'((?:[^'\\\\]|\\\\.)*)'");
    static final Pattern MODIFIERS = PyRe.compile("((?:minecraft:)?(attribute_modifiers|stored_enchantments|enchantments))=\\{");

    private static String unquote(String body) {
        return body.replace("\\'", "'").replace("\\\\", "\\");
    }

    private static boolean isJsonText(String s) {
        s = PyStr.strip(s);
        return s.startsWith("{") || s.startsWith("\"") || s.startsWith("[");
    }

    /** Index just past the bracket matching s[i] (which is open), respecting quoted strings; -1 if unmatched. */
    private static int matchBracket(String s, int i, char open, char close) {
        int depth = 0;
        char quote = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }

    private static String fixLore(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            MatchResult m = PyRe.search(LORE, command, i);
            if (m == null) {
                out.append(command.substring(i));
                return out.toString();
            }
            int start = m.end() - 1;
            int end = matchBracket(command, start, '[', ']');
            if (end < 0) {
                out.append(command.substring(i));
                return out.toString();
            }
            String body = command.substring(start + 1, end - 1);
            List<String> items = PyRe.findall1(QUOTED, body);
            String rest = PyStr.strip(PyRe.sub(QUOTED, body, x -> "").replace(",", ""));
            boolean all = true;
            for (String x : items) {
                all &= isJsonText(unquote(x));
            }
            if (!items.isEmpty() && rest.isEmpty() && all) {
                List<String> un = new ArrayList<>();
                for (String x : items) {
                    un.add(unquote(x));
                }
                String nw = "[" + String.join(",", un) + "]";
                warnComponent(command.substring(m.start(), end), command.substring(m.start(), start) + nw);
                out.append(command, i, start).append(nw);
            } else {
                out.append(command, i, end);
            }
            i = end;
        }
    }

    private static final Map<String, String[]> WRAPPED = Map.of(
            "attribute_modifiers", new String[] {"modifiers", "[", "]"},
            "enchantments", new String[] {"levels", "{", "}"},
            "stored_enchantments", new String[] {"levels", "{", "}"});

    private static String fixModifiers(String command) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            MatchResult m = PyRe.search(MODIFIERS, command, i);
            if (m == null) {
                out.append(command.substring(i));
                return out.toString();
            }
            String[] w = WRAPPED.get(m.group(2));
            String key = w[0];
            String lopen = w[1];
            String lclose = w[2];
            int start = m.end() - 1;
            int end = matchBracket(command, start, '{', '}');
            String inner = end > 0 ? command.substring(start + 1, end - 1) : "";
            MatchResult lm = PyRe.match(PyRe.compile("\\s*" + key + "\\s*:\\s*\\" + lopen), inner);
            if (end < 0 || lm == null) {
                out.append(command, i, m.end());
                i = m.end();
                continue;
            }
            int lstart = lm.end() - 1;
            int lend = matchBracket(inner, lstart, lopen.charAt(0), lclose.charAt(0));
            if (lend < 0) {
                out.append(command, i, end);
                i = end;
                continue;
            }
            String tail = PyStr.strip(inner.substring(lend));
            boolean hide = PyRe.fullmatch(HIDE, tail);
            if (!(tail.isEmpty() || hide || PyRe.fullmatch(SHOW, tail))) {
                out.append(command, i, end);
                i = end;
                continue;
            }
            String nw = m.group(1) + "=" + inner.substring(lstart, lend);
            if (hide) {
                nw += ",minecraft:tooltip_display={hidden_components:[\"minecraft:" + m.group(2) + "\"]}";
            }
            warnComponent(command.substring(m.start(), end), nw);
            out.append(command, i, m.start()).append(nw);
            i = end;
        }
    }

    static final Pattern HIDE = PyRe.compile(",?\\s*show_in_tooltip\\s*:\\s*(false|0b|0)\\s*");
    static final Pattern SHOW = PyRe.compile(",?\\s*show_in_tooltip\\s*:\\s*\\w+\\s*");

    static String fixComponentSyntax(String command) {
        if (!newSyntax() || (!command.contains("[") && !command.contains("{"))) {
            return command;
        }
        command = PyRe.sub(DYED, command, m -> {
            String now = m.group(1) + "=" + m.group(2);
            warnComponent(m.group(), now);
            return now;
        });
        command = PyRe.sub(TEXT_ONE, command, m -> {
            String body = unquote(m.group(2));
            if (!isJsonText(body)) {
                return m.group();
            }
            String now = m.group(1) + body;
            warnComponent(m.group(), now);
            return now;
        });
        command = fixLore(command);
        command = fixModifiers(command);
        lintComponentSyntax(command);
        return command;
    }

    private static final Object[][] LINT = {
            {PyRe.compile("\\b(HandItems|ArmorItems)\\b"),
                    "HandItems/ArmorItems are gone since 1.21.5 and are silently ignored: write equipment:{mainhand:{…},offhand:{…},head:{…},chest:{…},legs:{…},feet:{…}} (a path: equipment.mainhand), or item replace entity @s weapon.mainhand with …"},
            {PyRe.compile("\\b(HandDropChances|ArmorDropChances)\\b"),
                    "HandDropChances/ArmorDropChances are gone since 1.21.5 and are silently ignored: write drop_chances:{mainhand:0.5f,head:0f,…} (a path: drop_chances.mainhand)"},
            {PyRe.compile("can_(break|place_on)=\\{\\s*predicates"),
                    "can_break/can_place_on lost their {predicates:[…]} wrapper in 1.21.5: write can_break=[{blocks:\"minecraft:stone\"}] (or one predicate), and hide its line with tooltip_display"},
    };

    static void lintComponentSyntax(String command) {
        if (!newSyntax()) {
            return;
        }
        Set<Object> warned = Ctx.get().componentWarned;
        for (Object[] row : LINT) {
            Matcher m = ((Pattern) row[0]).matcher(command);
            String advice = (String) row[1];
            if (m.find() && !warned.contains(advice)) {
                warned.add(advice);
                Ctx.print("component syntax: " + advice);
            }
        }
    }
}
