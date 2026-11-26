# moddy-cbscript: working notes for Claude

This is the fork of the CBScript compiler that Moddy the Wish Watch bundles. It has two compilers kept byte-for-byte in
step: Python (the reference) and its Java port in `java/`. Read `README.md` and `JAVA_PORT.md` before changing
either. A change to the language or its output lands in both compilers in the same PR, with corpus cases and
regenerated goldens.

## Branches

- **`master` is the base version, Minecraft 1.21.8.** Its corpus covers 1.21.1, 1.21.4, 1.21.5 and 1.21.8
  (`corpus/versions.txt`). Work lands here, by PR, first.
- **One `java-port-<v>` branch per newer Minecraft version:** `java-port-1.21.10`, `java-port-1.21.11`,
  `java-port-26.1.2`, `java-port-26.2-ladder`, `java-port-26.3`. Each one is master plus that version's own
  spellings and ids, with goldens for every version up to its own. The list, oldest first, is
  `.github/carry-up-branches.txt`.
- Commit only what is specific to a version to its branch. Everything else goes to master and reaches the branches
  through the carry-up. Never rebase a version branch or force-push one: the carry-up and the Moddy core both build
  on their history.
- Adding a version: create `java-port-<v>` from the newest branch, add its spellings and goldens there (see "Where
  version-specific spellings belong"), then add its line at the end of `.github/carry-up-branches.txt` on master.

## The carry-up is automatic

`.github/workflows/carry-up.yml` runs on every push to master, and from Actions → carry-up → Run workflow. No model
is involved: the logic is `tools/carry-up/carry_up.py`. For each branch in the list, in order, it merges master in
with a merge commit and runs `tools/carry-up/parity.sh`, which runs `java-parity.yml`'s checks. A merge that is
clean and green is pushed. The first conflict or red parity stops the run, and that branch and every later one are
left unpushed. Do not merge master into the version branches by hand while the carry-up is working.

**When the `carry-up-blocked` issue opens.** It names the blocked version, the conflicted files or failing cases,
and the branches waiting behind it. To clear it:

1. Check out the blocked branch and run `git merge origin/master`, a merge and never a rebase.
2. Resolve the conflicts, keeping that version's own spellings and ids: `mc_target.py`, `nbt_case.py`,
   `attribute_ids.py`, `component_syntax.py`, `loot_syntax.py`, and in Java `compiler/McTarget.java` and
   `compiler/ItemModifiers.java`. Keep master's version of everything else.
3. If master changed compiler output, regenerate this branch's goldens with `python3 tools/corpus_golden.py`
   (ply 3.11, CPython 3.13 on Ubuntu 24.04: the goldens' platform).
4. Run `tools/carry-up/parity.sh` until it prints `PARITY-OK`. If the Java side differs, fix it in the compiler, on
   master when the cause is there.
5. Push the merge commit to the branch, or open a PR into it. Then run the carry-up again. It carries the later
   branches and closes the issue.

Tests for the carry-up itself: `python3 tools/carry-up/test_carry_up.py`, which builds a fixture repository and fakes
`gh`.

## Where version-specific spellings belong

**Put them in the compiler, never in a lesson.** A spelling that changes between Minecraft versions belongs in the
compiler's version tables, keyed on the target version, in both Python and Java:

- pack format and meta: `mc_target.py`, `mcworld.py` / `McTarget.java`;
- NBT key case: `nbt_case.py`; attribute ids: `attribute_ids.py`;
- component, text and click syntax: `component_syntax.py`;
- game-rule ids and item-modifier keys: `mc_target.py`, `loot_syntax.py` / `McTarget.java`, `ItemModifiers.java`.

Each one gets corpus cases and goldens. The compiler then writes the right spelling for whichever Minecraft the
player runs, from one source. `CBSCRIPT_LESSONS.md` is different: it is data for the wish agent, it covers every
version at once, and it has to stay version-neutral. A lesson there that says "on 26.3 write X" is wrong for every
other version. Make the compiler emit X instead.

## How a change reaches Moddy players

Merging to master and carrying up is not a release. The Moddy core vendors each version's Java compiler from that
version's `java-port-<v>` branch, in its weekly version round, which re-vendors every branch. A fix reaches
players on a given Minecraft version once three things have happened: it is on master, the carry-up has pushed it
to that version's branch, and the core's next weekly round has re-vendored that branch. While a `carry-up-blocked`
issue is open, the blocked version and every later one stay on their old compiler.

## Licences

- CBScript's original code is the author's. It is not open-source licensed. This fork uses it, and translates it
  into Java, by permission. Wherever that permission is recorded or described (commits, PRs, docs, code
  comments), say only **"used with the author's written permission, held by the maintainer"**. Do not quote,
  summarise, date or link the permission, and do not add details about it.
- `LICENSE` covers only this fork's own modifications (`NOTICE.md`).
- Bring in third-party code only under a licence on the allow-list in `tools/licence-rules.json`. Anything else,
  or code of unknown origin, stops the work and goes to the maintainer before it is committed. Reading such code to
  understand a problem is fine; writing ours from it counts as a port. Every kept third-party file has a row in
  `THIRD_PARTY.md`. `tools/licence-scan.py` checks all of this in CI; run `tools/git-hooks/install.sh` once per clone
  to have it check at commit and push as well.
- Pushing a branch to this repository publishes it. Never copy content from a private repository into this one, and
  never name a private repository here.
