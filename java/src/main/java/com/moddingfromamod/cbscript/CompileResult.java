// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a compile produced: compile_once.py's verdict ({@code ok}, {@code namespace}, {@code error}, {@code log}) and,
 * when it succeeded, the datapack's files in the order the Python compiler writes them into its zip.
 *
 * @param ok        true if the datapack was produced
 * @param namespace the datapack namespace (the script name, lower-cased, up to its first dot)
 * @param error     null on success; otherwise the message compile_once.py reports
 * @param log       the compiler's printed lines (warnings, and on failure the chain of errors)
 * @param files     path inside the datapack to file bytes, in write order; empty when not ok
 */
public record CompileResult(boolean ok, String namespace, String error, List<String> log, List<Map.Entry<String, byte[]>> files) {

    /** The datapack as zip bytes (deflated, entries in write order, fixed timestamps so the bytes are reproducible). */
    public byte[] toZip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTime(315532800000L);  // 1980-01-01, the zip epoch
                zip.putNextEntry(e);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
