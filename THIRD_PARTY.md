# Third-party files

Every committed file that carries a copyright that is not ours, or comes from someone else's work, has a row here:
where it came from, its licence or permission, and why it is here. `tools/licence-scan.py --tree` (CI) fails a foreign
copyright in a file with no row, a row that matches no tracked file, and a licence that is not on the allow-list in
`tools/licence-rules.json` (see the README, "Third-party code"). A path may be a glob.

`PERMISSION cbscript` means the original CBScript code, which SethBling allows this fork to use and modify but which
has no open-source licence (`NOTICE.md`). The fork's own changes are MPL-2.0 (`LICENSE`).

| file | source | licence | why |
|---|---|---|---|
| `*.py` | CBScript by SethBling, github.com/SethBling/cbscript, and this fork's changes | PERMISSION cbscript | the Python compiler |
| `Scripts/*` | CBScript's library and example scripts, by SethBling, and this fork's changes | PERMISSION cbscript | the compile-time libraries (`import name`) |
| `java/src/*` | the Java translation of CBScript, made in this fork | PERMISSION cbscript | the Java compiler (`java/THIRD_PARTY.md` has the per-file detail) |
| `java/src/main/java/com/moddingfromamod/cbscript/py/FdHyp.java` | sinh, cosh and tanh written here by fdlibm's formulas; fdlibm by Sun Microsystems | SunPro | its notice is preserved in the file, as it asks |
| `java/THIRD_PARTY.md` | quotes fdlibm's notice for `FdHyp.java` | SunPro | the Java port's licence table |
