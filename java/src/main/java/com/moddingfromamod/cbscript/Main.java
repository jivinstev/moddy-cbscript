// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command line, compile_once.py's: {@code java -jar moddy-cbscript.jar <script.cbscript> [--dir <world>] [--out <zip>]
 * [--mc <version>]}. Prints the same JSON verdict; exit 0 on success, 1 on failure. The target version is --mc, else
 * the CBSCRIPT_MC environment variable, else 1.21.1. Imports resolve against the script's own directory.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String script = null;
        String dir = null;
        String out = null;
        String mc = System.getenv("CBSCRIPT_MC");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = args[++i];
                case "--out" -> out = args[++i];
                case "--mc" -> mc = args[++i];
                default -> script = args[i];
            }
        }
        if (script == null) {
            System.err.println("usage: <script.cbscript> [--dir <world_dir>] [--out <zip_path>] [--mc <version>]");
            System.exit(2);
        }
        Path scriptPath = Path.of(script).toAbsolutePath();
        String source = new String(Files.readAllBytes(scriptPath), StandardCharsets.UTF_8);
        CompileResult r = CbScript.compile(scriptPath.getFileName().toString(), source, mc,
                CbScript.directory(scriptPath.getParent()));
        String output = null;
        if (r.ok()) {
            Path target;
            if (out != null) {
                target = Path.of(out).toAbsolutePath();
            } else {
                Path base = dir != null ? Path.of(dir).toAbsolutePath() : scriptPath.getParent();
                target = base.resolve("datapacks").resolve(r.namespace() + ".zip");
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, r.toZip());
            output = target.toString();
        }
        PrintStream o = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        o.println(json(r, output));
        System.exit(r.ok() ? 0 : 1);
    }

    static String json(CompileResult r, String output) {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"ok\": ").append(r.ok()).append(",\n");
        b.append("  \"namespace\": ").append(str(r.namespace())).append(",\n");
        b.append("  \"output\": ").append(str(output)).append(",\n");
        b.append("  \"log\": ").append(list(r.log())).append(",\n");
        b.append("  \"error\": ").append(str(r.error())).append("\n}");
        return b.toString();
    }

    private static String list(List<String> l) {
        if (l.isEmpty()) {
            return "[]";
        }
        StringBuilder b = new StringBuilder("[\n");
        for (int i = 0; i < l.size(); i++) {
            b.append("    ").append(str(l.get(i))).append(i < l.size() - 1 ? ",\n" : "\n");
        }
        return b.append("  ]").toString();
    }

    private static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
