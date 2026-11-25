// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.moddingfromamod.cbscript.compiler.Driver;
import com.moddingfromamod.cbscript.compiler.Json;
import com.moddingfromamod.cbscript.compiler.SourceFiles;

/**
 * The differential: every corpus case, for every target version, compiled by the Java compiler and compared with the
 * Python compiler's golden ({@code corpus/golden/<version>/}, written by tools/corpus_golden.py). Fails on any
 * accept/refuse mismatch, any difference in the error, the log (Python tracebacks compared by their last line), the
 * file list, its order, or any file's bytes.
 *
 * <pre>java -cp build/classes:build/test-classes com.moddingfromamod.cbscript.CorpusDifferential --root .. [--only prefix] [--show 20]</pre>
 */
public final class CorpusDifferential {
    private static final String SANDBOX = "<sandbox>";

    private CorpusDifferential() {
    }

    record Outcome(String version, String id, String problem) {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("..");
        String only = null;
        int show = 25;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--only" -> only = args[++i];
                case "--show" -> show = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        root = root.toAbsolutePath().normalize();
        List<String> versions = new ArrayList<>();
        for (String l : Files.readAllLines(root.resolve("corpus/versions.txt"))) {
            String v = l.split("#", 2)[0].strip();
            if (!v.isEmpty()) {
                versions.add(v);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(null, r, "cbs", 256L << 20);
                    t.setDaemon(true);
                    return t;
                });
        Map<String, int[]> totals = new TreeMap<>();
        List<Outcome> failures = new ArrayList<>();
        for (String version : versions) {
            Path dir = root.resolve("corpus/golden").resolve(version);
            List<Path> goldens;
            try (Stream<Path> s = Files.list(dir)) {
                goldens = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Path g : goldens) {
                final Path rootF = root;
                futures.add(pool.submit(() -> check(rootF, version, g)));
            }
            int pass = 0;
            int total = 0;
            for (Future<Outcome> f : futures) {
                Outcome o = f.get();
                if (only != null && !o.id().startsWith(only)) {
                    continue;
                }
                total++;
                if (o.problem() == null) {
                    pass++;
                } else {
                    failures.add(o);
                }
            }
            totals.put(version, new int[] {pass, total});
        }
        pool.shutdownNow();
        int shown = 0;
        for (Outcome o : failures) {
            if (shown++ >= show) {
                break;
            }
            System.out.println("MISMATCH " + o.version() + " " + o.id() + "\n    " + o.problem().replace("\n", "\n    "));
        }
        int allPass = 0;
        int all = 0;
        for (Map.Entry<String, int[]> e : totals.entrySet()) {
            System.out.printf("%-8s %d/%d cases match%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            allPass += e.getValue()[0];
            all += e.getValue()[1];
        }
        System.out.printf("TOTAL    %d/%d (%.2f%%)%n", allPass, all, all == 0 ? 0.0 : 100.0 * allPass / all);
        System.exit(allPass == all ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    static Outcome check(Path root, String version, Path goldenFile) {
        Map<String, Object> g;
        try {
            g = (Map<String, Object>) Json.loads(Files.readString(goldenFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String id = (String) g.get("id");
        try {
            Path script = root.resolve(id);
            String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
            CompileResult r = CbScript.compile(script.getFileName().toString(), source, version, sandbox(root, id));
            return new Outcome(version, id, compare(g, r));
        } catch (Throwable t) {
            return new Outcome(version, id, "Java threw " + t);
        }
    }

    /** The files a case's sandbox holds (tools/corpus_cases.py, Case.sandbox_files). */
    static SourceFiles sandbox(Path root, String id) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        Path scripts = root.resolve("Scripts");
        List<Path> libs;
        try (Stream<Path> s = Files.list(scripts)) {
            libs = s.filter(p -> p.toString().endsWith(".cblib")).sorted().toList();
        }
        Path script = root.resolve(id);
        if (id.startsWith("Scripts/")) {
            try (Stream<Path> s = Files.list(script.getParent())) {
                for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.equals(script)).toList()) {
                    files.put(p.getFileName().toString(), p);
                }
            }
            for (Path lib : libs) {
                files.putIfAbsent(lib.getFileName().toString(), lib);
            }
        } else {
            for (Path lib : libs) {
                files.put(lib.getFileName().toString(), lib);
            }
            if (id.startsWith("corpus/programs-blocks/")) {
                files.put("blocks.json", scripts.resolve("blocks.json"));
            }
        }
        return new SourceFiles() {
            @Override
            public String read(String name) {
                Path p = files.get(name);
                if (p == null) {
                    return null;
                }
                try {
                    return Driver.universalNewlines(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String absolute(String name) {
                return SANDBOX + "/" + name;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static String compare(Map<String, Object> g, CompileResult r) {
        List<String> problems = new ArrayList<>();
        boolean ok = (Boolean) g.get("ok");
        if (ok != r.ok()) {
            problems.add("ok: python " + ok + ", java " + r.ok() + (r.ok() ? "" : " (" + firstLine(r.error()) + ")"));
        }
        if (!String.valueOf(g.get("namespace")).equals(r.namespace())) {
            problems.add("namespace: python " + g.get("namespace") + ", java " + r.namespace());
        }
        String javaError = normalizeError(r.error());
        if (!java.util.Objects.equals(g.get("error"), javaError)) {
            problems.add("error:\n  python " + g.get("error") + "\n  java   " + javaError);
        }
        List<String> glog = (List<String>) (List<?>) g.get("log");
        List<String> jlog = normalizeLog(r.log());
        if (!glog.equals(jlog)) {
            problems.add("log:\n" + diffLines(glog, jlog));
        }
        List<List<Object>> gfiles = (List<List<Object>>) (List<?>) g.get("files");
        List<Map.Entry<String, byte[]>> jfiles = r.files();
        if (gfiles.size() != jfiles.size()) {
            problems.add("files: python " + gfiles.size() + ", java " + jfiles.size());
        }
        for (int i = 0; i < Math.min(gfiles.size(), jfiles.size()); i++) {
            List<Object> gf = gfiles.get(i);
            String name = (String) gf.get(0);
            byte[] want = gf.get(1) != null ? ((String) gf.get(1)).getBytes(StandardCharsets.UTF_8)
                    : HexFormat.of().parseHex((String) gf.get(2));
            if (!name.equals(jfiles.get(i).getKey())) {
                problems.add("file " + i + ": python " + name + ", java " + jfiles.get(i).getKey());
                break;
            }
            if (!java.util.Arrays.equals(want, jfiles.get(i).getValue())) {
                problems.add("file " + name + " differs:\n" + diffLines(
                        List.of(new String(want, StandardCharsets.UTF_8).split("\n", -1)),
                        List.of(new String(jfiles.get(i).getValue(), StandardCharsets.UTF_8).split("\n", -1))));
                break;
            }
        }
        return problems.isEmpty() ? null : String.join("\n", problems);
    }

    static String firstLine(String s) {
        return s == null ? "null" : s.split("\n", 2)[0];
    }

    /** tools/corpus_golden.py normalize_error. */
    static String normalizeError(String error) {
        if (error == null) {
            return null;
        }
        int cut = error.indexOf("\nTraceback (most recent call last):");
        if (cut >= 0) {
            error = error.substring(0, cut);
        }
        return error.replaceAll("\n+$", "");
    }

    /** tools/corpus_golden.py normalize_log. */
    static List<String> normalizeLog(List<String> lines) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.equals("Traceback (most recent call last):")) {
                int j = i + 1;
                while (j < lines.size() && (lines.get(j).startsWith(" ") || lines.get(j).isEmpty())) {
                    j++;
                }
                out.add("<traceback> " + (j < lines.size() ? lines.get(j) : ""));
                i = j + 1;
                continue;
            }
            if (line.startsWith("During handling of the above exception") || line.startsWith("The above exception was the direct cause")) {
                i++;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    static String diffLines(List<String> a, List<String> b) {
        StringBuilder s = new StringBuilder();
        int n = Math.max(a.size(), b.size());
        int shown = 0;
        for (int i = 0; i < n && shown < 6; i++) {
            String x = i < a.size() ? a.get(i) : "<none>";
            String y = i < b.size() ? b.get(i) : "<none>";
            if (!x.equals(y)) {
                s.append("  @").append(i + 1).append(" python: ").append(x).append("\n       java:   ").append(y).append('\n');
                shown++;
            }
        }
        if (a.size() != b.size()) {
            s.append("  (python ").append(a.size()).append(" lines, java ").append(b.size()).append(")\n");
        }
        return s.toString();
    }
}
