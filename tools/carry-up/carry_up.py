#!/usr/bin/env python3
"""Carry master up the version branches (.github/workflows/carry-up.yml; CLAUDE.md, "Branches").

For each branch in the config file, in order: merge master into it (a merge commit, never a rebase), run the parity
checks on the result, and push it when the merge was clean and parity is green. The first conflict or red parity
stops the run: that branch and every later one are not pushed, and the report names the version, the files and the
failing cases.

    carry_up.py [--config .github/carry-up-branches.txt] [--remote origin] [--master master]
                [--parity 'tools/carry-up/parity.sh .'] [--push] [--report carry-up.json]
    carry_up.py --sync-issue carry-up.json --repo owner/name     # open, update or close the carry-up-blocked issue

Standard library only. The run happens in the current clone: it checks out each branch in turn, and leaves the clone
on a detached HEAD at the end.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys

LABEL = 'carry-up-blocked'
ISSUE_TITLE = 'Carry-up blocked: master could not be merged into every version branch'
# Lines of tools/carry-up/parity.sh's output that name what failed (its tools' own failure lines).
FAILURE_LINE = re.compile(r'^\s*(PARITY-FAIL:|MISMATCH |STALE |TIMEOUT |UNCOVERED |FAIL(ED)?\b|.* [1-9]\d* failed)')
MAX_LINES = 60


def git(*args, check=True):
	p = subprocess.run(['git', *args], capture_output=True, text=True)
	if check and p.returncode != 0:
		raise RuntimeError(f'git {" ".join(args)} failed ({p.returncode}): {p.stderr.strip() or p.stdout.strip()}')
	return p


def read_config(path):
	branches = []
	with open(path, encoding='utf-8') as f:
		for line in f:
			line = line.split('#', 1)[0].strip()
			if line:
				if line in branches:
					raise ValueError(f'{path}: {line} is listed twice')
				branches.append(line)
	if not branches:
		raise ValueError(f'{path}: no branches')
	return branches


def version_of(branch):
	"""java-port-26.3 -> 26.3; anything else is its own name."""
	return branch[len('java-port-'):] if branch.startswith('java-port-') else branch


def failing_lines(output):
	lines = [l.rstrip() for l in output.splitlines() if FAILURE_LINE.match(l)]
	if not lines:  # a crash, or a failure the pattern does not know: show the end of the output instead
		lines = [l.rstrip() for l in output.splitlines() if l.strip() and not l.startswith('Picked up ')][-20:]
	return lines[:MAX_LINES] + ([f'... {len(lines) - MAX_LINES} more'] if len(lines) > MAX_LINES else [])


def carry_branch(branch, remote, master_ref, parity_cmd, push):
	"""Merge master_ref into remote/branch. Returns a result dict; 'status' is one of
	up-to-date, pushed, merged (green but not pushed: --push was not given), conflict, red."""
	r = {'branch': branch, 'version': version_of(branch)}
	git('reset', '-q', '--hard')  # what the last branch's parity run left in tracked files
	git('checkout', '-q', '--detach', f'{remote}/{branch}')
	before = git('rev-parse', 'HEAD').stdout.strip()
	if git('merge-base', '--is-ancestor', master_ref, 'HEAD', check=False).returncode == 0:
		r.update(status='up-to-date', head=before)
		return r
	master_sha = git('rev-parse', '--short', master_ref).stdout.strip()
	m = git('merge', '--no-ff', '--no-edit', '-m', f'{branch}: merge master ({master_sha}) [carry-up]', master_ref,
			check=False)
	if m.returncode != 0:
		files = git('diff', '--name-only', '--diff-filter=U').stdout.split()
		git('merge', '--abort', check=False)
		git('reset', '-q', '--hard', before)
		r.update(status='conflict', files=files or ['(merge failed without conflicted files)'],
				 cases=[] if files else failing_lines(m.stdout + m.stderr))
		return r
	head = git('rev-parse', 'HEAD').stdout.strip()
	changed = git('diff', '--name-only', before, head).stdout.split()
	p = subprocess.run(parity_cmd, shell=True, capture_output=True, text=True)
	output = p.stdout + p.stderr
	if p.returncode != 0:
		r.update(status='red', head=head, files=changed, cases=failing_lines(output))
		git('reset', '-q', '--hard', before)
		return r
	r.update(head=head, files=changed, summary=[l for l in output.splitlines() if l.startswith(('TOTAL', 'PARITY-OK'))])
	if push:
		git('push', '-q', remote, f'{head}:refs/heads/{branch}')
		r['status'] = 'pushed'
	else:
		r['status'] = 'merged'
	return r


def run(branches, remote, master, parity_cmd, push):
	git('fetch', '-q', remote, master, *branches)
	master_ref = f'{remote}/{master}'
	results, blocked = [], None
	for b in branches:
		if blocked:
			results.append({'branch': b, 'version': version_of(b), 'status': 'waiting', 'behind': blocked})
			continue
		res = carry_branch(b, remote, master_ref, parity_cmd, push)
		results.append(res)
		if res['status'] in ('conflict', 'red'):
			blocked = b
	return {'master': git('rev-parse', master_ref).stdout.strip(), 'blocked': blocked, 'results': results}


def issue_body(report, run_url=None):
	res = {r['branch']: r for r in report['results']}
	b = res[report['blocked']]
	what = 'merge conflict' if b['status'] == 'conflict' else 'parity is red after a clean merge'
	out = [f'The automatic carry-up could not merge master (`{report["master"][:10]}`) into **`{b["branch"]}`** '
		   f'(Minecraft **{b["version"]}**): {what}.', '']
	if b['status'] == 'conflict':
		out += ['**Conflicted files:**', ''] + [f'- `{f}`' for f in b['files']] + ['']
	else:
		out += ['**Failing checks and cases** (from `tools/carry-up/parity.sh`):', '', '```'] + b['cases'] + ['```', '']
		if b.get('files'):
			shown = b['files'][:40]
			out += [f'**Files the merge changed** ({len(b["files"])}):', ''] + [f'- `{f}`' for f in shown]
			out += ([f'- ... {len(b["files"]) - 40} more'] if len(b['files']) > 40 else []) + ['']
	waiting = [r['branch'] for r in report['results'] if r['status'] == 'waiting']
	if waiting:
		out += ['**Not pushed, waiting behind it:** ' + ', '.join(f'`{w}`' for w in waiting), '']
	done = [f'`{r["branch"]}` ({r["status"]})' for r in report['results'] if r['status'] in ('pushed', 'up-to-date', 'merged')]
	if done:
		out += ['**Carried up this run:** ' + ', '.join(done), '']
	out += ['**To unblock** (CLAUDE.md, "When the carry-up issue opens"): on the blocked branch, '
			'`git merge origin/master` by hand, resolve keeping that version\'s own spellings and ids, regenerate its '
			'goldens if master changed output (`python3 tools/corpus_golden.py`), run `tools/carry-up/parity.sh`, '
			'and push the merge commit (or open a PR into the branch). Then re-run the carry-up (Actions, `carry-up`, '
			'Run workflow): it carries the later branches and closes this issue.', '']
	if run_url:
		out += [f'Run: {run_url}']
	out += ['', '<!-- carry-up-blocked -->']
	return '\n'.join(out)


def gh(*args, input_json=None):
	p = subprocess.run(['gh', 'api', *args] + (['--input', '-'] if input_json is not None else []),
					   input=json.dumps(input_json) if input_json is not None else None, capture_output=True, text=True)
	return p.returncode, p.stdout, p.stderr


def sync_issue(report, repo, run_url=None):
	"""Open or update the one open carry-up-blocked issue; close it when nothing is blocked. Returns what it did."""
	code, out, err = gh(f'repos/{repo}/issues?labels={LABEL}&state=open&per_page=100')
	if code != 0:
		raise RuntimeError(f'listing issues failed: {err.strip()}')
	open_issues = sorted((i for i in json.loads(out or '[]') if 'pull_request' not in i), key=lambda i: i['number'])
	if not report['blocked']:
		for i in open_issues:
			gh(f'repos/{repo}/issues/{i["number"]}/comments', '-X', 'POST', input_json={
				'body': f'The carry-up merged master (`{report["master"][:10]}`) into every version branch with parity '
						f'green. Closing.' + (f'\n\nRun: {run_url}' if run_url else '')})
			gh(f'repos/{repo}/issues/{i["number"]}', '-X', 'PATCH', input_json={'state': 'closed'})
		return 'closed ' + ','.join(str(i['number']) for i in open_issues) if open_issues else 'nothing to do'
	body = issue_body(report, run_url)
	b = next(r for r in report['results'] if r['branch'] == report['blocked'])
	title = f'Carry-up blocked at {b["branch"]} ({b["version"]}): {"merge conflict" if b["status"] == "conflict" else "parity red"}'
	if open_issues:
		n = open_issues[0]['number']
		code, _, err = gh(f'repos/{repo}/issues/{n}', '-X', 'PATCH', input_json={'title': title, 'body': body})
		if code != 0:
			raise RuntimeError(f'updating issue #{n} failed: {err.strip()}')
		return f'updated {n}'
	gh(f'repos/{repo}/labels', '-X', 'POST', input_json={  # 422 if it exists already: fine
		'name': LABEL, 'color': 'd93f0b', 'description': 'The automatic master carry-up is stopped on a version branch'})
	code, out, err = gh(f'repos/{repo}/issues', '-X', 'POST', input_json={'title': title, 'body': body, 'labels': [LABEL]})
	if code != 0:
		raise RuntimeError(f'opening the issue failed: {err.strip()}')
	return f'opened {json.loads(out)["number"]}'


def main(argv=None):
	ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
	ap.add_argument('--config', default='.github/carry-up-branches.txt')
	ap.add_argument('--remote', default='origin')
	ap.add_argument('--master', default='master')
	ap.add_argument('--parity', default='tools/carry-up/parity.sh .')
	ap.add_argument('--push', action='store_true')
	ap.add_argument('--report', default='carry-up.json')
	ap.add_argument('--sync-issue', metavar='REPORT')
	ap.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY'))
	ap.add_argument('--run-url')
	a = ap.parse_args(argv)
	if a.sync_issue:
		with open(a.sync_issue, encoding='utf-8') as f:
			print(sync_issue(json.load(f), a.repo, a.run_url))
		return 0
	report = run(read_config(a.config), a.remote, a.master, a.parity, a.push)
	with open(a.report, 'w', encoding='utf-8') as f:
		json.dump(report, f, indent=2)
	for r in report['results']:
		extra = ' '.join(r.get('summary', [])) or (', '.join(r.get('files', [])[:5]) if r['status'] == 'conflict' else '')
		print(f'{r["branch"]:28} {r["status"]:10} {extra}')
		for c in r.get('cases', []) if r['status'] == 'red' else []:
			print('    ' + c)
	return 1 if report['blocked'] else 0


if __name__ == '__main__':
	sys.exit(main())
