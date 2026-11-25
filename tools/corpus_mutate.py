#!/usr/bin/env python3
"""Write corpus/mutants/: seeded single-edit mutations of every corpus program, for refusal (and acceptance) parity.

    python3 tools/corpus_mutate.py

Deterministic: the same programs give the same mutants. Most mutants are refused somewhere in the lexer, the parser
(often in its error-recovery path, which an action's SyntaxError enters) or a semantic pass; some still compile, and
then their output is compared byte for byte like any program's.
"""
import glob
import os
import random
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'corpus', 'mutants')
PER_PROGRAM = 24
SEED = 20261125

VOCAB = ['end', 'if', 'else', 'as', 'at', 'do', 'then', 'for', 'in', 'to', 'by', 'switch', 'case', 'define',
	'function', 'reset', 'clock', 'macro', 'create', 'with', 'return', 'not', 'and', 'or', 'block', 'push', 'pop',
	'(', ')', '[', ']', '{', '}', '<', '>', '=', '==', '+=', '-', '*', '/', '%', '^', '$', '@', ':', '.', ',', ';',
	'~', '!', '&', '#', '"', "'", '0', '-1', '1.5', '0x10', '$x', '@s', '@Q', 'True', 'None', 'Nonexistent']
TOKEN = re.compile(r'\s+|[A-Za-z_][A-Za-z0-9_]*\(?|\d+(?:\.\d+)?|"[^"\n]*"|\'[^\'\n]*\'|.', re.S)


def mutate(text, rng):
	lines = text.split('\n')
	body = [i for i, l in enumerate(lines) if l.strip()]
	op = rng.randrange(8)
	i = rng.choice(body)
	if op == 0:
		del lines[i]
		what = f'delete line {i + 1}'
	elif op == 1:
		lines.insert(i, lines[i])
		what = f'duplicate line {i + 1}'
	elif op == 2 and i + 1 < len(lines):
		lines[i], lines[i + 1] = lines[i + 1], lines[i]
		what = f'swap lines {i + 1} and {i + 2}'
	elif op == 3:
		lines = lines[:i]
		what = f'truncate before line {i + 1}'
	elif op == 4:
		lines.insert(i, '\tend' if rng.random() < 0.5 else 'end')
		what = f'insert end at line {i + 1}'
	else:
		toks = [m.group(0) for m in TOKEN.finditer(lines[i])]
		spots = [k for k, t in enumerate(toks) if not t.isspace()]
		if not spots:
			return mutate(text, rng)
		k = rng.choice(spots)
		if op == 5:
			what = f'delete token {toks[k]!r} on line {i + 1}'
			toks[k] = ''
		elif op == 6:
			new = rng.choice(VOCAB)
			what = f'replace token {toks[k]!r} with {new!r} on line {i + 1}'
			toks[k] = new
		else:
			new = rng.choice(VOCAB)
			what = f'insert {new!r} before {toks[k]!r} on line {i + 1}'
			toks[k] = new + ' ' + toks[k]
		lines[i] = ''.join(toks)
	return '\n'.join(lines), what


def main():
	for old in glob.glob(os.path.join(OUT, '*.cbscript')):
		os.remove(old)
	os.makedirs(OUT, exist_ok=True)
	n = 0
	sources = sorted(glob.glob(os.path.join(ROOT, 'corpus', 'programs', '*.cbscript')))
	for src in sources:
		base = os.path.splitext(os.path.basename(src))[0]
		with open(src, encoding='utf-8') as f:
			text = f.read()
		rng = random.Random(f'{SEED}:{base}')
		seen = set()
		k = 0
		while k < PER_PROGRAM:
			mutated, what = mutate(text, rng)
			if mutated in seen or mutated == text:
				continue
			seen.add(mutated)
			k += 1
			name = f'{base}_m{k:02d}.cbscript'
			with open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n') as f:
				f.write(f'# mutant of corpus/programs/{base}.cbscript: {what}\n' + mutated)
			n += 1
	print(f'wrote {n} mutants')


if __name__ == '__main__':
	main()
