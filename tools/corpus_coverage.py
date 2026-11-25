#!/usr/bin/env python3
"""Which grammar productions and compile-time library functions the corpus exercises.

    python3 tools/corpus_coverage.py            # summary + every production no corpus case reduces
    python3 tools/corpus_coverage.py --strict   # exit 1 if any production is uncovered

Productions are counted by running the real PLY parser over every corpus case (and every .cblib it imports), so
"covered" means a reduction really happened, not that a keyword appears somewhere.
"""
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import scriptparse  # noqa: E402
from corpus_cases import all_cases  # noqa: E402

# Productions that cannot be reached: PLY's conflict resolution always picks another rule. Listed with the reason.
UNREACHABLE = {
	'parsed -> expr': 'compile_all always appends a newline, and no expression can be followed by one',
}


def main():
	strict = '--strict' in sys.argv
	parser = scriptparse.bparser
	counts = [0] * len(parser.productions)
	for i, prod in enumerate(parser.productions):
		if prod.callable is None:
			continue
		def wrap(fn, i=i):
			def counted(p):
				counts[i] += 1
				return fn(p)
			return counted
		prod.callable = wrap(prod.callable)

	texts = []
	for case in all_cases():
		texts.append(('program', case.text + '\n'))
	for lib in glob.glob(os.path.join(ROOT, 'Scripts', '*.cblib')):
		with open(lib) as f:
			texts.append(('lib', 'import ' + f.read() + '\n'))
	import io, contextlib
	for kind, text in texts:
		with contextlib.redirect_stdout(io.StringIO()):
			try:
				scriptparse.parse(text)
			except Exception:
				pass

	uncovered = []
	for i, prod in enumerate(parser.productions):
		if i == 0:
			continue
		if counts[i] == 0 and prod.str not in UNREACHABLE:
			uncovered.append(f'{i}\t{prod.func}\t{prod.str}')
	total = len(parser.productions) - 1
	print(f'productions reduced by the corpus: {total - len(uncovered) - len(UNREACHABLE)}/{total}'
		f' ({len(UNREACHABLE)} unreachable: ' + '; '.join(f'{k}: {v}' for k, v in UNREACHABLE.items()) + ')')
	for line in uncovered:
		print('  UNCOVERED ' + line)

	# Library functions a $expression can call (builtins + mcfunction's globals) that the corpus calls.
	called = set()
	for case in all_cases():
		called.update(re.findall(r'\$([A-Za-z_][A-Za-z0-9_]*)\(', case.text))
		called.update(re.findall(r'\$([A-Za-z_][A-Za-z0-9_]*)\.\$([A-Za-z_][A-Za-z0-9_]*)\(', case.text) and
			[a + '.' + b for a, b in re.findall(r'\$([A-Za-z_][A-Za-z0-9_]*)\.\$([A-Za-z_][A-Za-z0-9_]*)\(', case.text)] or [])
	print(f'distinct $functions called: {len(called)}: {" ".join(sorted(called))}')
	if strict and uncovered:
		sys.exit(1)


if __name__ == '__main__':
	main()
