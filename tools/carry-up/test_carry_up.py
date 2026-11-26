#!/usr/bin/env python3
"""Fixture tests for tools/carry-up/carry_up.py: a throwaway remote with master and three version branches.

    python3 tools/carry-up/test_carry_up.py

The parity command is a stand-in: it fails, naming a case, when parity.txt in the merged tree says RED. The issue
sync runs against a fake `gh` on PATH that records its calls and serves a list of open issues.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import carry_up  # noqa: E402

BRANCHES = ['java-port-1.0', 'java-port-2.0', 'java-port-3.0']
PARITY = ('if grep -q RED parity.txt; then echo "MISMATCH 2.0 corpus/programs/basics.cbscript"; '
		  'echo "    output file data/x.mcfunction differs"; echo "PARITY-FAIL: java/build.sh test"; exit 1; fi; '
		  'echo "TOTAL    3/3 (100.00%)"; echo PARITY-OK')


def sh(cwd, *args):
	p = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
	if p.returncode:
		raise AssertionError(f'{args}: {p.stderr}')
	return p.stdout.strip()


class Fixture:
	"""remote.git (bare) seeded with master and BRANCHES, each branch adding its own version line to version.txt's
	neighbour file; work/ is the clone the carry-up runs in."""

	def __init__(self):
		self.tmp = tempfile.TemporaryDirectory()
		t = self.tmp.name
		self.remote, self.seed, self.work = f'{t}/remote.git', f'{t}/seed', f'{t}/work'
		sh(t, 'git', 'init', '-q', '--bare', '-b', 'master', self.remote)
		sh(t, 'git', 'clone', '-q', self.remote, self.seed)
		self.g('config', 'user.name', 'fixture'); self.g('config', 'user.email', 'fixture@example.invalid')
		self.write('compiler.txt', 'shared line\n')
		self.write('mc_target.txt', 'base 1.21.8\n')
		self.write('parity.txt', 'green\n')
		self.commit('base')
		self.g('push', '-q', 'origin', 'master')
		prev = 'master'
		for i, b in enumerate(BRANCHES):  # a ladder: each version branch made from the one before it
			self.g('checkout', '-q', '-b', b, prev)
			self.write('mc_target.txt', self.read('mc_target.txt') + f'version {b}\n')
			self.commit(f'{b}: its spellings')
			self.g('push', '-q', 'origin', b)
			prev = b
		self.g('checkout', '-q', 'master')
		sh(t, 'git', 'clone', '-q', self.remote, self.work)
		for k, v in (('user.name', 'carry-up'), ('user.email', 'carry-up@example.invalid')):
			sh(self.work, 'git', 'config', k, v)

	def g(self, *a):
		return sh(self.seed, 'git', *a)

	def write(self, name, text):
		with open(os.path.join(self.seed, name), 'w') as f:
			f.write(text)

	def read(self, name):
		with open(os.path.join(self.seed, name)) as f:
			return f.read()

	def commit(self, msg):
		self.g('add', '-A'); self.g('commit', '-q', '-m', msg)

	def on(self, branch, name, text, msg):
		self.g('checkout', '-q', branch); self.write(name, text); self.commit(msg)
		self.g('push', '-q', 'origin', branch); self.g('checkout', '-q', 'master')

	def remote_sha(self, ref):
		return sh(self.remote, 'git', 'rev-parse', ref)

	def master_in(self, branch):
		return subprocess.run(['git', 'merge-base', '--is-ancestor', 'master', branch], cwd=self.remote).returncode == 0

	def carry(self, push=True):
		old = os.getcwd()
		os.chdir(self.work)
		try:
			return carry_up.run(BRANCHES, 'origin', 'master', PARITY, push)
		finally:
			os.chdir(old)


class CarryUp(unittest.TestCase):
	def setUp(self):
		self.f = Fixture()
		self.addCleanup(self.f.tmp.cleanup)

	def statuses(self, report):
		return [r['status'] for r in report['results']]

	def test_clean_and_green_pushes_every_branch_as_a_merge_commit(self):
		self.f.on('master', 'compiler.txt', 'shared line, fixed\n', 'master: a fix')
		before = {b: self.f.remote_sha(b) for b in BRANCHES}
		rep = self.f.carry()
		self.assertEqual(self.statuses(rep), ['pushed'] * 3)
		self.assertIsNone(rep['blocked'])
		for b in BRANCHES:
			self.assertTrue(self.f.master_in(b), b)
			parents = sh(self.f.remote, 'git', 'log', '-1', '--format=%P', b).split()
			self.assertEqual(parents, [before[b], self.f.remote_sha('master')], f'{b}: a merge commit on the old tip')
			text = sh(self.f.remote, 'git', 'show', f'{b}:mc_target.txt')
			self.assertIn(f'version {b}', text, 'the version branch keeps its own spellings')
			self.assertEqual(sh(self.f.remote, 'git', 'show', f'{b}:compiler.txt'), 'shared line, fixed')

	def test_second_run_is_a_no_op(self):
		self.f.on('master', 'compiler.txt', 'x\n', 'master: x')
		self.f.carry()
		heads = {b: self.f.remote_sha(b) for b in BRANCHES}
		rep = self.f.carry()
		self.assertEqual(self.statuses(rep), ['up-to-date'] * 3)
		self.assertEqual(heads, {b: self.f.remote_sha(b) for b in BRANCHES})

	def test_conflict_stops_that_version_and_every_later_one(self):
		self.f.on('java-port-2.0', 'compiler.txt', 'shared line, 2.0 spelling\n', '2.0: a spelling')
		self.f.on('master', 'compiler.txt', 'shared line, master change\n', 'master: a change')
		before = {b: self.f.remote_sha(b) for b in BRANCHES}
		rep = self.f.carry()
		self.assertEqual(self.statuses(rep), ['pushed', 'conflict', 'waiting'])
		self.assertEqual(rep['blocked'], 'java-port-2.0')
		self.assertEqual(rep['results'][1]['files'], ['compiler.txt'])
		self.assertEqual(rep['results'][1]['version'], '2.0')
		self.assertTrue(self.f.master_in('java-port-1.0'))
		for b in BRANCHES[1:]:
			self.assertEqual(self.f.remote_sha(b), before[b], f'{b} must not be pushed')
		body = carry_up.issue_body(rep)
		self.assertIn('**`java-port-2.0`** (Minecraft **2.0**): merge conflict', body)
		self.assertIn('- `compiler.txt`', body)
		self.assertIn('waiting behind it:** `java-port-3.0`', body)

	def test_red_parity_stops_that_version_and_every_later_one(self):
		# master's change merges cleanly everywhere, but only 2.0 (and so 3.0, made from it) goes red with it
		self.f.on('java-port-2.0', 'red_marker.txt', 'x\n', '2.0: a file that turns red once master is fixed')
		parity = PARITY.replace('grep -q RED parity.txt', 'test -f red_marker.txt && grep -q fixed compiler.txt')
		self.f.on('master', 'compiler.txt', 'shared line, fixed\n', 'master: a fix')
		before = {b: self.f.remote_sha(b) for b in BRANCHES}
		old = os.getcwd(); os.chdir(self.f.work)
		try:
			rep = carry_up.run(BRANCHES, 'origin', 'master', parity, True)
		finally:
			os.chdir(old)
		self.assertEqual(self.statuses(rep), ['pushed', 'red', 'waiting'])
		red = rep['results'][1]
		self.assertIn('MISMATCH 2.0 corpus/programs/basics.cbscript', red['cases'])
		self.assertIn('PARITY-FAIL: java/build.sh test', red['cases'])
		self.assertIn('compiler.txt', red['files'])
		for b in BRANCHES[1:]:
			self.assertEqual(self.f.remote_sha(b), before[b], f'{b} must not be pushed')
		body = carry_up.issue_body(rep, 'https://example.invalid/run/1')
		self.assertIn('parity is red after a clean merge', body)
		self.assertIn('MISMATCH 2.0 corpus/programs/basics.cbscript', body)
		self.assertIn('Run: https://example.invalid/run/1', body)

	def test_without_push_nothing_reaches_the_remote(self):
		self.f.on('master', 'compiler.txt', 'y\n', 'master: y')
		before = {b: self.f.remote_sha(b) for b in BRANCHES}
		rep = self.f.carry(push=False)
		self.assertEqual(self.statuses(rep), ['merged'] * 3)
		self.assertEqual(before, {b: self.f.remote_sha(b) for b in BRANCHES})

	def test_config_order_comments_and_duplicates(self):
		with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as f:
			f.write('# header\njava-port-1.21.10\n\njava-port-26.3   # newest\n')
		self.addCleanup(os.unlink, f.name)
		self.assertEqual(carry_up.read_config(f.name), ['java-port-1.21.10', 'java-port-26.3'])
		with open(f.name, 'a') as g:
			g.write('java-port-26.3\n')
		self.assertRaises(ValueError, carry_up.read_config, f.name)
		repo_cfg = os.path.join(HERE, '..', '..', '.github', 'carry-up-branches.txt')
		self.assertEqual(carry_up.read_config(repo_cfg)[0], 'java-port-1.21.10')


class IssueSync(unittest.TestCase):
	"""sync_issue against a fake gh: open when none, update the open one, close it when nothing is blocked."""

	def setUp(self):
		self.tmp = tempfile.TemporaryDirectory()
		self.addCleanup(self.tmp.cleanup)
		self.log = os.path.join(self.tmp.name, 'calls.jsonl')
		self.issues = os.path.join(self.tmp.name, 'issues.json')
		fake = os.path.join(self.tmp.name, 'gh')
		with open(fake, 'w') as f:
			f.write(f'''#!{sys.executable}
import json, sys
args = sys.argv[1:]
body = json.load(sys.stdin) if '--input' in args else None
open({self.log!r}, 'a').write(json.dumps({{'args': args, 'body': body}}) + '\\n')
if args[1].startswith('repos/o/r/issues?'):
    print(open({self.issues!r}).read())
elif args[1] == 'repos/o/r/issues' and '-X' in args:
    print(json.dumps({{'number': 42}}))
else:
    print('{{}}')
''')
		os.chmod(fake, 0o755)
		self.path = os.environ['PATH']
		os.environ['PATH'] = self.tmp.name + os.pathsep + self.path
		self.addCleanup(os.environ.__setitem__, 'PATH', self.path)

	def calls(self):
		with open(self.log) as f:
			return [json.loads(l) for l in f]

	def serve(self, issues):
		with open(self.issues, 'w') as f:
			json.dump(issues, f)

	BLOCKED = {'master': 'abcdef0123456789', 'blocked': 'java-port-26.1.2', 'results': [
		{'branch': 'java-port-1.21.10', 'version': '1.21.10', 'status': 'pushed'},
		{'branch': 'java-port-26.1.2', 'version': '26.1.2', 'status': 'conflict', 'files': ['mc_target.py'], 'cases': []},
		{'branch': 'java-port-26.3', 'version': '26.3', 'status': 'waiting'}]}

	def test_opens_one_labelled_issue_when_none_is_open(self):
		self.serve([])
		self.assertEqual(carry_up.sync_issue(self.BLOCKED, 'o/r'), 'opened 42')
		post = [c for c in self.calls() if c['args'][1] == 'repos/o/r/issues' and '-X' in c['args']][0]['body']
		self.assertEqual(post['labels'], ['carry-up-blocked'])
		self.assertIn('java-port-26.1.2 (26.1.2): merge conflict', post['title'])
		self.assertIn('`mc_target.py`', post['body'])

	def test_updates_the_open_issue_instead_of_opening_another(self):
		self.serve([{'number': 7}, {'number': 9, 'pull_request': {}}])
		self.assertEqual(carry_up.sync_issue(self.BLOCKED, 'o/r'), 'updated 7')
		self.assertFalse([c for c in self.calls() if c['args'][1] == 'repos/o/r/issues' and '-X' in c['args']])
		patch = [c for c in self.calls() if c['args'][1] == 'repos/o/r/issues/7'][0]
		self.assertIn('PATCH', patch['args'])

	def test_closes_the_issue_once_everything_is_carried(self):
		self.serve([{'number': 7}])
		green = {'master': 'abcdef0123456789', 'blocked': None, 'results': []}
		self.assertEqual(carry_up.sync_issue(green, 'o/r'), 'closed 7')
		self.assertEqual([c['body'] for c in self.calls() if c['args'][1] == 'repos/o/r/issues/7'], [{'state': 'closed'}])

	def test_nothing_open_and_nothing_blocked_does_nothing(self):
		self.serve([])
		self.assertEqual(carry_up.sync_issue({'master': 'a' * 10, 'blocked': None, 'results': []}, 'o/r'), 'nothing to do')


if __name__ == '__main__':
	unittest.main(verbosity=2)
