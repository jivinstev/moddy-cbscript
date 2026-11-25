// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** tellraw.py: CBScript's formatted text into a JSON text component. */
final class Tellraw {
    private Tellraw() {
    }

    static String clickEvent(String action, String value) {
        if (Ctx.get().target.atLeast(1, 21, 5)) {
            String field = action.equals("open_url") ? "url" : "command";
            return "\"click_event\":{\"action\":\"" + action + "\",\"" + field + "\":\"" + value + "\"}";
        }
        return "\"clickEvent\":{\"action\":\"" + action + "\",\"value\":\"" + value + "\"}";
    }

    /** color, bold, underlined, italic, strikethrough. */
    static final class Props {
        String color;
        boolean bold;
        boolean underlined;
        boolean italic;
        boolean strikethrough;

        Props copy() {
            Props p = new Props();
            p.color = color;
            p.bold = bold;
            p.underlined = underlined;
            p.italic = italic;
            p.strikethrough = strikethrough;
            return p;
        }

        String text() {
            StringBuilder t = new StringBuilder();
            if (color != null) {
                t.append(",\"color\":\"").append(color).append('"');
            }
            if (bold) {
                t.append(",\"bold\":true");
            }
            if (underlined) {
                t.append(",\"underlined\":true");
            }
            if (italic) {
                t.append(",\"italic\":true");
            }
            if (strikethrough) {
                t.append(",\"strikethrough\":true");
            }
            return t.toString();
        }
    }

    /** A segment: plain text, or (formatted, command) where either may be null. */
    record Segment(String text, String formatted, String command, boolean pair, Props props) {
    }

    static String formatJsonText(McFunction func, String text) {
        StringBuilder f = new StringBuilder("[\"\"");
        for (Segment seg : parseTextFormatting(text)) {
            String props = seg.props().text();
            if (!seg.pair()) {
                f.append(",{\"text\":\"").append(seg.text().replace("\"", "\\\"")).append('"').append(props).append('}');
                continue;
            }
            String unformatted = seg.formatted();
            String command = seg.command();
            if (unformatted == null) {
                String[] parts = command.split("\\.", -1);
                if (parts.length > 2) {
                    throw Ctx.compileError("Json text has invalid () text: \"" + command + "\"");
                }
                if (parts.length == 1) {
                    if (parts[0].isEmpty()) {
                        throw Ctx.compileError("Empty () in json text.");
                    } else if (parts[0].charAt(0) == '@') {
                        f.append(",{\"selector\":\"").append(parts[0]).append('"').append(props).append('}');
                    } else if (parts[0].contains(":")) {
                        String[] sp = parts[0].split(":", -1);
                        if (sp.length != 2) {
                            throw Ctx.compileError("Unable to parse storage variable \"" + parts[0] + "\"");
                        }
                        String target = sp[0].isEmpty() ? func.namespace() : sp[0];
                        f.append(",{\"nbt\":\"").append(sp[1]).append("\",\"storage\":\"").append(target).append("\"}");
                    } else {
                        f.append(",{\"score\":{\"name\":\"Global\",\"objective\":\"").append(parts[0]).append('"').append(props).append("}}");
                    }
                }
                if (parts.length == 2) {
                    String name = parts[0];
                    String nameDef = func.getNameDefinition(parts[0]);
                    if (nameDef != null && !nameDef.isEmpty()) {
                        name = nameDef;
                    }
                    f.append(",{\"score\":{\"name\":\"").append(name).append("\",\"objective\":\"").append(parts[1]).append('"').append(props).append("}}");
                }
            } else if (command == null) {
                f.append(",{\"text\":\"").append(unformatted.replace("\"", "\\\"")).append('"').append(props).append('}');
            } else {
                command = command.replace("\"", "\\\"");
                String action;
                if (command.startsWith("http")) {
                    action = "open_url";
                } else if (command.startsWith("//")) {
                    action = "suggest_command";
                    command = command.substring(1);
                } else if (command.startsWith("call ")) {
                    action = "run_command";
                    command = "/function " + func.namespace() + ":" + command.substring("call ".length());
                } else {
                    action = "run_command";
                }
                f.append(",{\"text\":\"").append(unformatted.replace("\"", "\\\"")).append("\",").append(clickEvent(action, command))
                        .append(props).append('}');
            }
        }
        return f.append(']').toString();
    }

    static final Map<Character, String> COLORS = Map.ofEntries(
            Map.entry('k', "black"), Map.entry('K', "dark_gray"), Map.entry('w', "gray"), Map.entry('W', "white"),
            Map.entry('r', "dark_red"), Map.entry('R', "red"), Map.entry('g', "dark_green"), Map.entry('G', "green"),
            Map.entry('b', "dark_blue"), Map.entry('B', "blue"), Map.entry('y', "gold"), Map.entry('Y', "yellow"),
            Map.entry('c', "dark_aqua"), Map.entry('C', "aqua"), Map.entry('m', "dark_purple"), Map.entry('M', "light_purple"));

    static List<Segment> parseTextFormatting(String text) {
        final int none = 1;
        final int formattedMode = 2;
        final int endFormatted = 3;
        final int commandMode = 4;
        final int scoreboard = 5;
        final int property = 6;
        boolean escaped = false;
        int mode = none;
        StringBuilder seg = new StringBuilder();
        String formatted = "";
        List<Segment> segments = new ArrayList<>();
        Props props = new Props();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            if (cp == '\\' && !escaped) {
                escaped = true;
            } else if (escaped) {
                seg.append(ch);
                escaped = false;
            } else if (mode == none) {
                if (cp == '[' || cp == '(' || cp == '{') {
                    if (seg.length() > 0) {
                        segments.add(new Segment(seg.toString(), null, null, false, props.copy()));
                        seg.setLength(0);
                    }
                    mode = cp == '[' ? formattedMode : cp == '(' ? scoreboard : property;
                } else {
                    seg.append(ch);
                }
            } else if (mode == formattedMode) {
                if (cp == ']') {
                    formatted = seg.toString();
                    seg.setLength(0);
                    mode = endFormatted;
                } else {
                    seg.append(ch);
                }
            } else if (mode == endFormatted) {
                if (cp == '(') {
                    mode = commandMode;
                } else {
                    segments.add(new Segment(null, formatted, null, true, props.copy()));
                    formatted = "";
                    mode = none;
                    seg.append(ch);
                }
            } else if (mode == commandMode) {
                if (cp == ')') {
                    segments.add(new Segment(null, formatted, seg.toString(), true, props.copy()));
                    seg.setLength(0);
                    formatted = "";
                    mode = none;
                } else {
                    seg.append(ch);
                }
            } else if (mode == scoreboard) {
                if (cp == ')') {
                    segments.add(new Segment(null, null, seg.toString(), true, props.copy()));
                    seg.setLength(0);
                    mode = none;
                } else {
                    seg.append(ch);
                }
            } else if (mode == property) {
                if (cp < 0x10000 && COLORS.containsKey((char) cp)) {
                    props.color = COLORS.get((char) cp);
                } else if (cp == '-') {
                    props.color = null;
                    props.bold = false;
                    props.underlined = false;
                    props.italic = false;
                    props.strikethrough = false;
                } else if (cp == 'D') {
                    props.bold = true;
                } else if (cp == 'd') {
                    props.bold = false;
                } else if (cp == 'U') {
                    props.underlined = true;
                } else if (cp == 'u') {
                    props.underlined = false;
                } else if (cp == 'I') {
                    props.italic = true;
                } else if (cp == 'i') {
                    props.italic = false;
                } else if (cp == 'S') {
                    props.strikethrough = true;
                } else if (cp == 's') {
                    props.strikethrough = false;
                } else if (cp == '{') {
                    seg.append(ch);
                } else {
                    throw Ctx.compileError("Unexpected formatting character {" + ch + " in tell command");
                }
                mode = none;
            }
        }
        if (seg.length() > 0) {
            segments.add(new Segment(seg.toString(), null, null, false, props.copy()));
        }
        return segments;
    }
}
