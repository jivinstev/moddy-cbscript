"""The corpus's cases, shared by the golden generator, the coverage report and the mutator.

A case is one .cbscript file. Its id is its path under the repository, and its namespace is its file name, as the
compiler derives it. What else the compiler can see while compiling it (its "sandbox") depends on where it lives:

  corpus/programs/        the mod's setup: the script plus every Scripts/*.cblib, nothing else
  corpus/programs-blocks/ the same plus Scripts/blocks.json (block switches read it)
  corpus/malformed/       the mod's setup; written to be refused (or to probe the edge of acceptance)
  corpus/mutants/         the mod's setup; seeded mutations of corpus/programs (tools/corpus_mutate.py)
  Scripts/**              the repository's own examples, compiled in their own directory as a person would (plus
                          the Scripts/*.cblib their folder lacks), minus corpus/examples-excluded.txt
"""
import glob
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CORPUS = os.path.join(ROOT, 'corpus')


class Case(object):
	def __init__(self, path, kind):
		self.path = path
		self.kind = kind
		self.id = os.path.relpath(path, ROOT).replace(os.sep, '/')
		with open(path, 'r', encoding='utf-8') as f:
			self.text = f.read()

	@property
	def golden_name(self):
		return self.id.replace('/', '__').replace(' ', '_') + '.json'

	def sandbox_files(self):
		"""(source path, name in the sandbox) pairs the compiler can see beside the script."""
		libs = sorted(glob.glob(os.path.join(ROOT, 'Scripts', '*.cblib')))
		if self.kind == 'example':
			d = os.path.dirname(self.path)
			own = [(p, os.path.basename(p)) for p in sorted(glob.glob(os.path.join(d, '*')))
				if os.path.isfile(p) and p != self.path]
			names = {n for _, n in own}
			# An archived script imports the shared libraries from Scripts/ when its folder has no copy.
			return own + [(p, os.path.basename(p)) for p in libs if os.path.basename(p) not in names]
		files = [(p, os.path.basename(p)) for p in libs]
		if self.kind == 'blocks':
			files.append((os.path.join(ROOT, 'Scripts', 'blocks.json'), 'blocks.json'))
		return files


def excluded_examples():
	path = os.path.join(CORPUS, 'examples-excluded.txt')
	out = set()
	if os.path.exists(path):
		with open(path) as f:
			for line in f:
				line = line.split('#', 1)[0].strip()
				if line:
					out.add(line)
	return out


def all_cases():
	cases = []
	for sub, kind in (('programs', 'program'), ('programs-blocks', 'blocks'), ('malformed', 'malformed'),
			('mutants', 'mutant')):
		for p in sorted(glob.glob(os.path.join(CORPUS, sub, '*.cbscript'))):
			cases.append(Case(p, kind))
	skip = excluded_examples()
	for p in sorted(glob.glob(os.path.join(ROOT, 'Scripts', '**', '*.cbscript'), recursive=True)):
		c = Case(p, 'example')
		if c.id not in skip:
			cases.append(c)
	return cases
