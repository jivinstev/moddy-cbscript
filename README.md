> **This is moddy-cbscript**, a modified fork of [SethBling's CBScript](https://github.com/SethBling/cbscript) maintained
> for the Minecraft mod **Moddy the Wish Watch**. It is used with the original author's permission; the original code
> is not open-source licensed. See [`NOTICE.md`](NOTICE.md) for exactly what is and is not covered by this
> repository's `LICENSE`. Fixes that are not Moddy-specific are welcome to go back upstream.

# Introduction

CBScript is a transpiled language, designed by SethBling. This compiler will compile CBScript files into Minecraft datapack zip files. It has many higher level language features that don't exist at the Minecraft command level. Awareness of implementation details will help you avoid performance overhead and bugs. The files in the datapack are generally organized by source file line numbers to make it easier to find the particular compiled mcfunction files you're looking for.

# Two compilers: Python and Java

This fork has **two implementations of the same compiler**, kept byte-for-byte in step:

* the original **Python** compiler (`compile.py`, `compile_once.py`, and the modules beside them), which is the
  reference; and
* a **Java** port under [`java/`](java/) (JDK 21 or newer, no other dependencies), so a Java program such as the
  Moddy mod can compile CBScript without Python installed. It is a translation of this Python code (see
  [`NOTICE.md`](NOTICE.md)), and is built with `java/build.sh` into `java/build/moddy-cbscript.jar`: a library whose
  one entry point is `CbScript.compile(namespace, source, minecraftVersion)` (datapack files, or the error and log),
  and a command line with `compile_once.py`'s arguments and JSON output
  (`java -jar moddy-cbscript.jar script.cbscript --out pack.zip --mc 1.21.1`).

**They stay in step, and the corpus holds them to it.** [`corpus/`](corpus/) has programs covering every grammar
production and the compile-time library, this repository's own example scripts, malformed and mutated programs for
refusal parity, and the Python compiler's output for each of them, for every Minecraft version the branch targets
(`corpus/golden/`). CI ([`.github/workflows/java-parity.yml`](.github/workflows/java-parity.yml)) fails a PR if the
goldens are not what the Python compiler produces now, or if the Java compiler differs from them in any accept/refuse
decision, error message, warning or output byte. So **a language or output change lands in both compilers in the same
PR, with corpus cases for it and the goldens regenerated**:

```
python3 tools/export_parser_tables.py   # after any change to scriptparse.py / scriptlex.py (then update java/.../parse/Actions.java)
python3 tools/corpus_golden.py          # regenerate the goldens (needs ply 3.11)
java/build.sh test                      # Java unit tests + the differential against every golden
```

See [`JAVA_PORT.md`](JAVA_PORT.md) for how the port is built, what is compared, and its documented boundary (it
does not run `import name.py` Python files, and it refuses `$"..."` expressions that reach outside the compiler).

# Installation and Requirements

The Python CBScript compiler requires python 3 (the Java one, above, needs only a JDK). In order to run it via the run.cmd file, you'll need the Python "py launcher", which comes with newer installations of Python, and can be used to launch a specific version. You'll need to install the Python dependencies with

```pip install -r requirements.txt```

There are instructions in run.cmd for setting up your Windows registry to be able to double click .cbscript files in order to run the compiler.

You can use cbscript-npp-highlighting.xml with Notepad++ to add syntax highlighting.

# Running the Compiler

When you use run.cmd with your .cbscript file as an argument, a command window will pop up. You can also invoke compile.py directly. This command window will monitor your .cbscript file for changes, and recompile any time it observes the file has been changed. Each cbscript file has a world file at the beginning that specifies where to place the compiled datapack. The compiled datapack will be placed in that world's /datapacks folder, with the same base name as the .cbscript file, overwriting it as necessary. You can use /reload in game to reload the datapack when it's been recompiled.

# Features

CBScript includes many high level features that simplify the syntax and construction of datapacks, as well as make them easier to maintain. Look at the scripts in the "/scripts" folder for examples. There are also archived script subfolders that contain datapacks for older versions of the game.

* Include files
* Arithmetic expressions
* For/while loops
* If/else if/else blocks
* Execute syntax
* Tellraw with simplified syntax
* Title/subtitle/actionbar with simplified syntax.
* Macro function support
* Entity selector definitions, including data paths
* Advancement/loot table/block tag/predicate definitions.
* Function/method calls with parameters.
* Switch, supporting both numbers and block types
* Compile-time variables
* Compile-time loop unrolling
* Compile-time macros
* Template functions
* Coordinate vectors
