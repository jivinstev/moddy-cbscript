# Java port of the CBScript compiler: plan

**Goal.** Moddy the Wish Watch runs this compiler as `python3 compile_once.py` for every Magician datapack. A
Java compiler with byte-for-byte parity removes the Python 3 requirement from the mod (and from its store pages).
The Python compiler stays: it is the reference, and the two are kept in step (see "Keeping them in step").

CBScript is SethBling's; this fork and its Java port are used with his permission (`NOTICE.md`). The Java code is a
translation of the Python code, carries the same attribution, and is covered by `LICENSE` exactly as the rest of the
fork is: Moddy's own modifications only.

## What the Python compiler is (survey)

| Stage | Python | Notes for the port |
|---|---|---|
| Lexer | `scriptlex.py` (PLY `lex`) | One master regex: function rules in definition order, then string rules by decreasing regex length; first alternative wins, not the longest. `COMMAND` is `(?m:^\s*\/.+)` and swallows the newlines before it (its line number is where its leading whitespace began). `None`/`True`/`False` become `DECIMAL` even as a prefix (`Nonexistent` lexes as `0`,`xistent`). Python `\s`, `\d` and `.` are Unicode-aware and differ from Java's defaults. |
| Parser | `scriptparse.py` (PLY `yacc`, LALR, 362 actions, 154 s/r + 267 r/r conflicts) | The syntax-error text names the **LR state number**, and an action that raises `SyntaxError` sends PLY into its error-recovery loop rather than failing. So the Java parser runs **PLY's own tables** (exported by `tools/export_parser_tables.py`) through a line-by-line port of PLY 3.11's `parseopt` loop, including default reductions and recovery; only the 362 semantic actions are hand-written. |
| Compile-time Python | `interpreted_python` → `eval(expr, mcfunction globals, dollar-ids)`; `import x.py` → `exec` | Every `$expr`, `$"…"`, `for $i in …`, `if $…`, macro argument and template argument is a Python expression. The port carries a Python-expression evaluator with Python value semantics (arbitrary-precision ints, float `repr`, `str`/`repr` of lists, tuples and dicts, `//`, `%`, `**`, slicing, comprehensions, `math`, string methods, the builtins). `import x.py` (running a whole Python file) cannot be honoured by a Java compiler; see "Boundary". |
| Semantic passes / codegen | `cbscript.py`, `global_context.py`, `environment.py`, `mcfunction.py`, `block_types/`, `variable_types/`, `scalar_expressions/`, `vector_expressions/`, `data_types/`, `nbt_types/`, `selector_definition.py`, `tellraw.py`, `scratch_tracker.py` | Ported class for class, keeping Python's evaluation order, dict insertion order, and its bugs (an undefined name in an error path is a `NameError`, not the intended message), because the output and the refusals must match. |
| Version tables | `mc_target.py`, `nbt_case.py`, `attribute_ids.py`, `component_syntax.py` (+ `loot_syntax.py` on mc/26.3) | Keyed on `CBSCRIPT_MC`; in Java the target version is an argument. Warnings are printed once per compile (Python: once per process, and the mod runs one process per compile). |
| Output | `mcworld.py` | Zip entry order and `json.dumps(indent=4)` text (ASCII-escaped) reproduced exactly. |
| Driver | `compile_once.py` | JSON verdict `{ok, namespace, output, log, error}`; the Java CLI prints the same shape. |

Branches: `master` (oldest Minecraft Moddy supports) → `mc/1.21.10` → `mc/1.21.11` → `mc/26.1.2` → `mc/26.2-ladder` →
`mc/26.3`, each made by merging the one before it and adding its own version's spellings (pack format/meta, the
chain-length game rule id, the loot-function key). The Java port lands on `master` and is carried up the ladder by the
same merges, with each branch's delta ported in its merge.

## Order of work

1. **Corpus first** (`corpus/`, see its README):
   - synthetic programs covering every grammar production and every compile-time library function, measured, not
     claimed (`tools/corpus_coverage.py` reports any production no case reduces);
   - the repository's own example scripts (`Scripts/`) and the cases in its `test_*.py`;
   - malformed programs for refusal parity, hand-written plus seeded mutations of the valid ones;
   - golden output from the Python compiler, per target version, per branch (`tools/corpus_golden.py`, which runs
     `compile_once.py` exactly as the mod does, one process per compile);
   - a differential runner that fails on any accept/refuse mismatch, any difference in the error message, the
     warnings or any output file's bytes.
   No player-written text in committed programs: every program is written for the corpus or taken from `Scripts/`.
2. **Java compiler**, JDK only (Java 21; runs on 21 and 25), a library jar with one entry point:
   `CbScript.compile(namespace, source, minecraftVersion[, options])` → files or diagnostics. Plus a CLI with
   `compile_once.py`'s arguments and JSON output.
3. **Iterate to 100%** of the corpus on every target version, then CI (`.github/workflows/java-parity.yml`) runs the
   differential on every PR, on JDK 21 and 25, and regenerates the goldens with Python to prove they are current.
4. README: the two compilers stay in step; a language change lands in both, with corpus cases.
5. PRs: `master` first, then the mc/* ladder by merge.

## Parity: what is compared

For every case and target version: `ok`; the `error` text (an "Unexpected compiler error" is compared up to its
Python traceback, which names Python source lines); the log with Python tracebacks collapsed to their final line;
and every output file's name, order and bytes.

## Boundary (documented, tested as refusals)

- `import name.py` executes a Python file. Java cannot; it refuses with the message Python gives when the file is
  absent. In the mod the agent writes one source and no `.py` files, so Python refuses these too.
- `$"…"` evaluates an arbitrary Python expression. The evaluator covers Python's expression grammar over the
  value types a CBScript program can produce; names Python would find but that reach outside the compiler
  (`__import__`, `open`, `exec`, `eval`, `globals`, …) are refused. (Python today executes them: a security
  hole the port closes rather than copies.)
- Output Python makes nondeterministic is pinned for the corpus: `hash()` of a selector name (indexed `create`,
  `@x[5]`) is reproduced for `PYTHONHASHSEED=0`, which the golden generator sets; a bound method's address is
  never compared.

## Keeping them in step

A change to the language or its output lands in Python and Java in the same PR, with corpus cases, and the goldens
regenerated (`tools/corpus_golden.py`). CI fails if the goldens are stale or the Java output differs.
