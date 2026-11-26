// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import com.moddingfromamod.cbscript.compiler.Driver;
import com.moddingfromamod.cbscript.compiler.SourceFiles;

/**
 * The CBScript compiler, in Java: the stable entry point.
 *
 * <pre>{@code
 * CompileResult r = CbScript.compile("my_pack", sourceText, "1.21.1");
 * if (r.ok()) { byte[] zip = r.toZip(); } else { report(r.error(), r.log()); }
 * }</pre>
 *
 * <p>The output is byte-for-byte what the Python compiler ({@code compile_once.py} with {@code CBSCRIPT_MC} set to the
 * same version) produces for the same source, and a source it refuses this refuses, with the same message: the corpus
 * in {@code corpus/} holds the two to that on every PR. The source is what the script file holds (its {@code dir} and
 * {@code desc} header included); {@code import name} reads {@code name.cblib} from the libraries bundled in this jar
 * (the repository's Scripts/*.cblib), as the mod's compile directory holds them.
 *
 * <p>Thread-safe: each call is independent.
 */
public final class CbScript {
    /** The Minecraft version assumed when none is given, as the Python compiler assumes with CBSCRIPT_MC unset. */
    public static final String DEFAULT_MINECRAFT = "1.21.1";

    private CbScript() {
    }

    /**
     * Compile {@code source} for {@code minecraftVersion} (e.g. "1.21.1"; null for {@link #DEFAULT_MINECRAFT}).
     *
     * @param namespace the datapack namespace, which is the script's file name in the Python compiler (it is taken up
     *                  to its first dot and lower-cased, as the Python compiler takes it)
     */
    public static CompileResult compile(String namespace, String source, String minecraftVersion) {
        return compile(namespace, source, minecraftVersion, bundledLibraries());
    }

    /** As {@link #compile(String, String, String)}, with the files beside the script supplied by the caller. */
    public static CompileResult compile(String namespace, String source, String minecraftVersion, SourceFiles files) {
        return compile(namespace, source, minecraftVersion, files, null);
    }

    /** What a compile that ran out of time reports (the wording the mod used for a timed-out Python compile). */
    public static final String TIMED_OUT = "CBScript compile timed out.";

    /**
     * As {@link #compile(String, String, String, SourceFiles)}, stopped after {@code timeout} (null: no limit). A
     * stopped compile is refused with {@link #TIMED_OUT}: a program can ask for unbounded work at compile time (a
     * {@code for $i in $range(10**12)}), which the Python compiler's caller ended by killing its process.
     */
    public static CompileResult compile(String namespace, String source, String minecraftVersion, SourceFiles files,
                                        java.time.Duration timeout) {
        // On its own thread with a deep stack: java.util.regex recurses on long string literals, and the compiler
        // recurses as deep as a program nests.
        Driver.Result[] out = new Driver.Result[1];
        RuntimeException[] failure = new RuntimeException[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = Driver.compile(namespace, source, minecraftVersion, files);
            } catch (RuntimeException e) {
                failure[0] = e;
            } catch (com.moddingfromamod.cbscript.py.Cancelled e) {
                // out[0] stays null: timed out
            }
        }, "cbscript-compile", STACK_BYTES);
        t.setDaemon(true);
        t.start();
        boolean interrupted = false;
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                long left = deadline - System.nanoTime();
                if (timeout != null && left <= 0) {
                    t.interrupt();
                    t.join(5_000);
                    break;
                }
                if (timeout == null) {
                    t.join();
                } else {
                    t.join(Math.max(1, left / 1_000_000));
                }
                if (!t.isAlive()) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        Driver.Result r = out[0];
        if (r == null) {
            return new CompileResult(false, Driver.namespaceOf(namespace), TIMED_OUT, java.util.List.of(), java.util.List.of());
        }
        return new CompileResult(r.ok(), r.namespace(), r.error(), r.log(), r.files());
    }

    private static final long STACK_BYTES = 256L << 20;

    /** The libraries bundled in this jar (Scripts/*.cblib); nothing else is beside the script. */
    public static SourceFiles bundledLibraries() {
        return resources(name -> {
            if (!name.endsWith(".cblib") || name.contains("/") || name.contains("\\")) {
                return null;
            }
            return "/com/moddingfromamod/cbscript/lib/" + name;
        }, "<bundled>");
    }

    /** The files in {@code directory}, as a script saved there sees them. */
    public static SourceFiles directory(Path directory) {
        return new SourceFiles() {
            @Override
            public String read(String name) {
                Path p = directory.resolve(name);
                if (!Files.isRegularFile(p)) {
                    return null;
                }
                try {
                    return Driver.universalNewlines(Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String absolute(String name) {
                return directory.toAbsolutePath().resolve(name).normalize().toString();
            }
        };
    }

    private static SourceFiles resources(Function<String, String> resourceOf, String shownDirectory) {
        return new SourceFiles() {
            @Override
            public String read(String name) {
                String res = resourceOf.apply(name);
                if (res == null) {
                    return null;
                }
                try (InputStream in = CbScript.class.getResourceAsStream(res)) {
                    return in == null ? null : Driver.universalNewlines(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String absolute(String name) {
                return shownDirectory + "/" + name;
            }
        };
    }
}
