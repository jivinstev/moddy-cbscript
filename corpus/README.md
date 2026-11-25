# Parity corpus: the Python compiler's behaviour, recorded, for the Java compiler to match

Every program here is written for the corpus or is one of this repository's own `Scripts/` examples. **No
player-written text** goes into a corpus program.

| Directory | What | Compiled with |
|---|---|---|
| `programs/` | Synthetic programs: every grammar production (measured: `tools/corpus_coverage.py --strict`), the compile-time library (`library.cbscript`, `pyexpr.cbscript`), and the commands from this repository's `test_*.py` (`transforms.cbscript`) | the script + `Scripts/*.cblib` (the mod's compile directory) |
| `programs-blocks/` | Block switches, which read `blocks.json` | the same + `Scripts/blocks.json` |
| `malformed/` | Hand-written refusals and edge cases: lexer, parser (including PLY's error recovery after an action's `SyntaxError`), semantic errors, Python's own bugs in error paths, encodings and line endings | the mod's compile directory |
| `mutants/` | Seeded one-edit mutations of `programs/` (`tools/corpus_mutate.py`): mostly refused, some still compile | the mod's compile directory |
| `../Scripts/**` | The repository's example scripts, minus `examples-excluded.txt` (each with its reason) | their own folder, plus the `Scripts/*.cblib` it lacks |

`versions.txt` lists the Minecraft versions (`CBSCRIPT_MC`) every case is compiled for on this branch. `golden/<version>/`
holds what the Python compiler did for each case: `ok`, the namespace, the error (an unexpected error's Python
traceback cut off), the log (each Python traceback collapsed to its last line) and every file in the zip, in order.
Goldens are made by running `compile_once.py` exactly as the mod runs it, one process per compile, with
`PYTHONHASHSEED=0` (the compiler hashes selector names for indexed entities, which is otherwise random per process).

```
python3 tools/corpus_mutate.py      # regenerate mutants/ (deterministic)
python3 tools/corpus_golden.py      # regenerate golden/ (needs ply 3.11; --check verifies instead of writing)
python3 tools/corpus_coverage.py    # which productions and $functions the corpus exercises
java/build.sh test                  # the Java compiler against every golden
```

Not in the corpus, by design:

- an empty source file: the Python compiler waits forever for it to become non-empty (`source_file.get_text`); the mod
  always writes a header, so it never sees one;
- `import name.py` of a Python file that exists (`Scripts/mtg`): running Python is outside what the Java compiler does
  (`JAVA_PORT.md`, Boundary). A missing one is in `malformed/` and is refused identically.
