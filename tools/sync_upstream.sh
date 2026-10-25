#!/usr/bin/env bash
# Merges upstream TV Bro (truefedex/tv-bro, master) into the current branch, following UPSTREAM_SYNC.md:
#  - files only upstream changed: taken as they are;
#  - files this fork changed too: the fork's version is kept, and they're listed for a decision by hand (a major or
#    breaking fix upstream gets merged in by hand).
# The merge is recorded, so the next sync only brings what's new upstream since this one.
#
#   tools/sync_upstream.sh            # merges, commits, writes the report to sync-report.md
#
# Exit code 0 with nothing to do, 0 after a merge (the report says what needs a look), 1 on errors.
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_URL=https://github.com/truefedex/tv-bro.git
REPORT=sync-report.md

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
REF=${UPSTREAM_REF:-upstream/master}   # UPSTREAM_REF: another ref to merge (to try the script)
if [ -z "${UPSTREAM_REF:-}" ]; then git fetch -q upstream master; fi

if git merge-base --is-ancestor $REF HEAD; then
  echo "Already up to date with upstream."
  rm -f "$REPORT"
  exit 0
fi

BASE=$(git merge-base HEAD $REF)
ours=$(git diff --name-only "$BASE" HEAD | sort)
theirs=$(git diff --name-only "$BASE" $REF | sort)
both=$(comm -12 <(echo "$ours") <(echo "$theirs") | sed '/^$/d')
safe=$(comm -13 <(echo "$ours") <(echo "$theirs") | sed '/^$/d')

git merge --no-commit --no-ff -X ours $REF >/dev/null 2>&1 || true
# The fork's version of every file both sides changed (also where -X ours merged upstream's other hunks in).
for f in $both; do
  if git cat-file -e "HEAD:$f" 2>/dev/null; then
    git checkout HEAD -- "$f"
  else
    git rm -q --ignore-unmatch -- "$f"   # deleted in the fork, changed upstream: stays deleted
  fi
done
if [ -n "$(git diff --name-only --diff-filter=U)" ]; then
  echo "Unresolved conflicts left:" >&2
  git diff --name-only --diff-filter=U >&2
  exit 1
fi

commits=$(git log --oneline --no-merges HEAD..$REF)
git commit -q -m "Merge upstream TV Bro $(git rev-parse --short $REF)

Files only upstream changed are taken; files this fork changed too keep the fork's version
(see UPSTREAM_SYNC.md and the list below).

Kept the fork's version:
$(echo "${both:-(none)}" | sed 's/^/  /')"

{
  echo "Upstream TV Bro, merged up to \`$(git rev-parse --short $REF)\`."
  echo
  echo "## Upstream commits"
  echo
  echo '```'
  echo "${commits:-(only merges)}"
  echo '```'
  echo
  echo "## Taken from upstream ($(echo "$safe" | sed '/^$/d' | wc -l | tr -d ' ') files, untouched by TV Akhi)"
  echo
  if [ -n "$safe" ]; then echo "$safe" | sed 's/^/- `/; s/$/`/'; else echo "None."; fi
  echo
  echo "## Kept TV Akhi's version: decide by hand"
  echo
  echo "Upstream changed these too. Merge their change in by hand only for a major or breaking fix (new"
  echo "GeckoView/WebView API, security, crash). Upstream's change of each:"
  echo
  if [ -n "$both" ]; then
    for f in $both; do
      echo "<details><summary><code>$f</code></summary>"
      echo
      echo '```diff'
      git diff "$BASE" $REF -- "$f" | head -200
      echo '```'
      echo "</details>"
    done
  else
    echo "None."
  fi
} > "$REPORT"

echo "Merged. Report: $REPORT"
