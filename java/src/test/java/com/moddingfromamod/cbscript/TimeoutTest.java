// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript;

import java.time.Duration;

/** A compile that asks for unbounded work is stopped by its timeout and refused; a normal one is not affected. */
public final class TimeoutTest {
    private TimeoutTest() {
    }

    public static void main(String[] args) {
        String[] runaway = {
                "dir \".\"\nreset\n\tfor $i in $range(10**12)\n\t\t/say $i\n\tend\nend\n",
                "dir \".\"\n$x = $sum($range(10**15))\nreset\nend\n",
        };
        for (String src : runaway) {
            long t0 = System.nanoTime();
            CompileResult r = CbScript.compile("t", src, "1.21.8", CbScript.bundledLibraries(), Duration.ofSeconds(2));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(!r.ok() && CbScript.TIMED_OUT.equals(r.error()), "runaway compile refused as timed out: " + r.error());
            check(ms < 10_000, "stopped promptly (" + ms + " ms)");
        }
        CompileResult ok = CbScript.compile("t", "dir \".\"\nreset\n\t/say hi\nend\n", "1.21.8", CbScript.bundledLibraries(),
                Duration.ofSeconds(30));
        check(ok.ok(), "a normal compile with a timeout succeeds: " + ok.error());
        System.out.println("TimeoutTest: OK");
    }

    private static void check(boolean b, String what) {
        if (!b) {
            System.out.println("TimeoutTest FAILED: " + what);
            System.exit(1);
        }
    }
}
