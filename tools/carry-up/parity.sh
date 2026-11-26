#!/usr/bin/env bash
# The parity checks of .github/workflows/java-parity.yml, run in one place (the carry-up runs them on each merge).
#   tools/carry-up/parity.sh [repo-root]      (default: the current directory, so the tree being checked)
# Needs python3 with ply==3.11 and pyyaml, and a JDK 21+. Exits non-zero on the first failing check, after printing
# a line "PARITY-FAIL: <check>" that the carry-up puts in its issue.
set -uo pipefail
cd "${1:-.}"
PY=${PYTHON:-python3}
fail() { echo "PARITY-FAIL: $1"; exit 1; }
step() { echo "== $1"; }

step "parser tables are current"
$PY tools/export_parser_tables.py || fail "export_parser_tables.py"
git diff --exit-code --stat -- java/src/main/resources/com/moddingfromamod/cbscript/parser_tables.txt || fail "parser tables are stale"
step "every grammar production is in the corpus"
$PY tools/corpus_coverage.py --strict || fail "corpus_coverage.py --strict"
step "expression-evaluator cases are current"
$PY tools/pyeval_cases.py || fail "pyeval_cases.py"
git diff --exit-code --stat -- java/src/test/resources/pyeval_cases.tsv || fail "pyeval cases are stale"
step "goldens are current"
$PY tools/corpus_golden.py --check || fail "goldens are stale (tools/corpus_golden.py --check)"
step "java: build, unit tests, corpus differential"
java/build.sh test || fail "java/build.sh test"
echo "PARITY-OK"
