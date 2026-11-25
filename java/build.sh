#!/usr/bin/env bash
# Build the Java CBScript compiler with the JDK alone (Java 21+; no Gradle, no Maven, no downloads).
#
#   java/build.sh            -> java/build/moddy-cbscript.jar (library + CLI), and the test classes
#   java/build.sh test       -> also run the unit tests and the corpus differential (every version in corpus/versions.txt)
#
# The jar bundles the parser tables (src/main/resources, made by tools/export_parser_tables.py), the repository's
# Scripts/*.cblib (what `import name` reads), and LICENSE + NOTICE.md.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"
javac --release 21 -encoding UTF-8 -Xlint:-options -d "$OUT/classes" $(find src/main/java -name '*.java')
cp -R src/main/resources/. "$OUT/classes/"
mkdir -p "$OUT/classes/com/moddingfromamod/cbscript/lib" "$OUT/classes/META-INF"
cp "$ROOT"/Scripts/*.cblib "$OUT/classes/com/moddingfromamod/cbscript/lib/"
cp "$ROOT/LICENSE" "$OUT/classes/META-INF/LICENSE"
cp "$ROOT/NOTICE.md" "$OUT/classes/META-INF/NOTICE.md"
jar --create --file "$OUT/moddy-cbscript.jar" --main-class com.moddingfromamod.cbscript.Main -C "$OUT/classes" .
javac --release 21 -encoding UTF-8 -Xlint:-options -cp "$OUT/classes" -d "$OUT/test-classes" $(find src/test/java -name '*.java')
if [ -d src/test/resources ]; then cp -R src/test/resources/. "$OUT/test-classes/"; fi
echo "built $OUT/moddy-cbscript.jar"
if [ "${1:-}" = "test" ]; then
    for t in $(cd src/test/java && grep -rl 'public static void main' --include='*Test.java' . | sed 's|^\./||; s|\.java$||; s|/|.|g'); do
        echo "== $t"
        java -cp "$OUT/classes:$OUT/test-classes" "$t"
    done
    java -cp "$OUT/classes:$OUT/test-classes" com.moddingfromamod.cbscript.CorpusDifferential --root "$ROOT"
fi
