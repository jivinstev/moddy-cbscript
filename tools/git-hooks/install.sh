#!/usr/bin/env bash
# Turn on this repository's checks in a clone: git then runs tools/git-hooks/pre-commit before every commit and
# tools/git-hooks/pre-push before every push. Both run tools/licence-scan.py (README, "Third-party code").
#
#   ./tools/git-hooks/install.sh            # on
#   ./tools/git-hooks/install.sh --check    # exit 0 if on, 1 if not
#
# It points core.hooksPath at tools/git-hooks, so the hooks are the committed ones and change with a pull. A clone
# whose hooksPath already points somewhere else is left alone and named.
set -euo pipefail
top="$(git rev-parse --show-toplevel)"
want="tools/git-hooks"
have="$(git -C "$top" config --get core.hooksPath || true)"
if [ "${1:-}" = "--check" ]; then
    [ "$have" = "$want" ] && { echo "git-hooks: on in $top"; exit 0; }
    echo "git-hooks: OFF in $top (core.hooksPath=${have:-unset}); run ./tools/git-hooks/install.sh" >&2
    exit 1
fi
[ -z "${1:-}" ] || { echo "git-hooks: unknown argument $1" >&2; exit 2; }
if [ -n "$have" ] && [ "$have" != "$want" ]; then
    echo "git-hooks: core.hooksPath is already '$have' in $top; left alone. Point it at $want by hand." >&2
    exit 1
fi
chmod +x "$top/$want/pre-commit" "$top/$want/pre-push"
git -C "$top" config core.hooksPath "$want"
echo "git-hooks: licence check on in $top (pre-commit + pre-push)"
