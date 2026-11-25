#!/usr/bin/env python3
"""Compile every corpus case with the Python compiler and record what it did: the goldens the Java compiler is held to.

    python3 tools/corpus_golden.py                  # (re)write corpus/golden/<version>/*.json for corpus/versions.txt
    python3 tools/corpus_golden.py --check          # regenerate in memory; exit 1 if any committed golden differs
    python3 tools/corpus_golden.py --only Scripts/  # cases whose id starts with a prefix

Each compile is `compile_once.py <script> --out <zip>` in its own process, in a sandbox holding exactly the files the
case may see (tools/corpus_cases.py), with CBSCRIPT_MC set to the target version: what the mod runs. PYTHONHASHSEED=0
pins the one nondeterminism the compiler has (hash() of a selector name for indexed entities).

A golden records ok, the namespace, the error (an unexpected-error traceback cut off, since it names Python source
lines), the log (each Python traceback collapsed to "<traceback> " + its last line) and every file in the zip, in zip
order, with its exact text.
"""
import argparse
import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from corpus_cases import all_cases, CORPUS  # noqa: E402

TIMEOUT = 120
# What a sandbox directory is called in a golden (the Java runner names its virtual directory the same).
SANDBOX = '<sandbox>'


def normalize_error(error):
	if error is None:
		return None
	cut = error.find('\nTraceback (most recent call last):')
	if cut >= 0:
		error = error[:cut]
	return error.rstrip('\n')


def normalize_log(lines):
	"""Collapse each traceback block into one line naming its exception."""
	out = []
	i = 0
	while i < len(lines):
		line = lines[i]
		if line == 'Traceback (most recent call last):':
			j = i + 1
			while j < len(lines) and (lines[j].startswith(' ') or lines[j] == ''):
				j += 1
			last = lines[j] if j < len(lines) else ''
			out.append('<traceback> ' + last)
			i = j + 1
			continue
		if line.startswith('During handling of the above exception') or line.startswith(
				'The above exception was the direct cause'):
			i += 1
			continue
		out.append(line)
		i += 1
	return out


def versions():
	with open(os.path.join(CORPUS, 'versions.txt')) as f:
		return [l.split('#', 1)[0].strip() for l in f if l.split('#', 1)[0].strip()]


def compile_case(case, version, python):
	work = tempfile.mkdtemp(prefix='cbs-golden-')
	try:
		for src, name in case.sandbox_files():
			shutil.copyfile(src, os.path.join(work, name))
		script = os.path.join(work, os.path.basename(case.path))
		shutil.copyfile(case.path, script)
		out_zip = os.path.join(work, 'out', 'pack.zip')
		env = dict(os.environ)
		env['CBSCRIPT_MC'] = version
		env['PYTHONHASHSEED'] = '0'
		env['PYTHONDONTWRITEBYTECODE'] = '1'
		try:
			proc = subprocess.run([python, os.path.join(ROOT, 'compile_once.py'), script, '--out', out_zip],
				cwd=work, env=env, stdin=subprocess.DEVNULL, capture_output=True, timeout=TIMEOUT)
		except subprocess.TimeoutExpired:
			return {'id': case.id, 'version': version, 'timeout': True}
		# The sandbox's own path appears in file-not-found messages; it is not the compiler's to choose.
		stdout = proc.stdout.decode('utf-8', 'replace').replace(json.dumps(work)[1:-1], SANDBOX)
		start = stdout.rfind('\n{')
		verdict = json.loads(stdout[start + 1 if start >= 0 else 0:])
		files = []
		if verdict['ok']:
			with zipfile.ZipFile(out_zip) as z:
				for info in z.infolist():
					data = z.read(info)
					try:
						files.append([info.filename, data.decode('utf-8')])
					except UnicodeDecodeError:
						files.append([info.filename, None, data.hex()])
		return {
			'id': case.id,
			'version': version,
			'ok': verdict['ok'],
			'namespace': verdict['namespace'],
			'error': normalize_error(verdict['error']),
			'log': normalize_log(verdict['log']),
			'files': files,
		}
	finally:
		shutil.rmtree(work, ignore_errors=True)


def golden_path(version, case):
	return os.path.join(CORPUS, 'golden', version, case.golden_name)


def render(record):
	return json.dumps(record, indent=1, ensure_ascii=False) + '\n'


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument('--check', action='store_true')
	ap.add_argument('--only', default=None)
	ap.add_argument('--version', action='append', default=None)
	ap.add_argument('--jobs', type=int, default=os.cpu_count() or 2)
	ap.add_argument('--python', default=sys.executable)
	args = ap.parse_args()

	cases = [c for c in all_cases() if args.only is None or c.id.startswith(args.only)]
	vs = args.version or versions()
	jobs = [(c, v) for v in vs for c in cases]
	stale, timeouts, written = [], [], 0
	with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as ex:
		futures = {ex.submit(compile_case, c, v, args.python): (c, v) for c, v in jobs}
		for fut in concurrent.futures.as_completed(futures):
			c, v = futures[fut]
			rec = fut.result()
			if rec.get('timeout'):
				timeouts.append(f'{v} {c.id}')
				continue
			text = render(rec)
			path = golden_path(v, c)
			if args.check:
				old = open(path, encoding='utf-8').read() if os.path.exists(path) else None
				if old != text:
					stale.append(f'{v} {c.id}')
			else:
				os.makedirs(os.path.dirname(path), exist_ok=True)
				with open(path, 'w', encoding='utf-8', newline='\n') as f:
					f.write(text)
				written += 1
	if not args.check and args.only is None and args.version is None:
		# Goldens of cases that no longer exist.
		keep = {golden_path(v, c) for c, v in jobs}
		for v in os.listdir(os.path.join(CORPUS, 'golden')):
			for name in os.listdir(os.path.join(CORPUS, 'golden', v)):
				p = os.path.join(CORPUS, 'golden', v, name)
				if p not in keep:
					os.remove(p)
	for t in sorted(timeouts):
		print('TIMEOUT', t)
	if args.check:
		for s in sorted(stale):
			print('STALE', s)
		print(f'{len(jobs) - len(stale) - len(timeouts)}/{len(jobs)} goldens current')
		sys.exit(1 if stale or timeouts else 0)
	print(f'wrote {written} goldens ({len(cases)} cases x {len(vs)} versions)')
	sys.exit(1 if timeouts else 0)


if __name__ == '__main__':
	main()
