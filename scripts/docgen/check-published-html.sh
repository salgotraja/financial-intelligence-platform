#!/usr/bin/env bash
# Validates the PUBLISHED learning-guide HTML is safe to ship, WITHOUT needing the
# markdown source (which is local-only). Runs in CI and locally. Checks:
#   - zero references to any .md file (the source markdown is never published)
#   - no external image/script src (page must be fully self-contained/offline)
#   - at least one embedded diagram (data: URI), proving assets are inlined
set -euo pipefail

HTML="${1:-docs/learning-guide.html}"
[ -f "$HTML" ] || { echo "FAIL: $HTML not found"; exit 1; }

status=0

# `|| true` on each grep: a no-match (grep exit 1) is the PASS case for md/ext and
# must not abort under `set -e -o pipefail`.
md=$( { grep -oE '[A-Za-z0-9._/-]*\.md\b' "$HTML" || true; } | wc -l | tr -d ' ')
if [ "$md" -ne 0 ]; then
  echo "FAIL: $md markdown-file reference(s) in $HTML (the .md source is not published):"
  { grep -oE '[A-Za-z0-9._/-]*\.md\b' "$HTML" || true; } | sort -u | sed 's/^/  - /'
  status=1
fi

ext=$( { grep -oiE 'src="https?://' "$HTML" || true; } | wc -l | tr -d ' ')
if [ "$ext" -ne 0 ]; then
  echo "FAIL: $ext external image/script src in $HTML (page must be self-contained)"
  status=1
fi

svg=$( { grep -o 'data:image/svg' "$HTML" || true; } | wc -l | tr -d ' ')
if [ "$svg" -lt 1 ]; then
  echo "FAIL: no embedded (data:) diagrams in $HTML (assets not inlined)"
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "OK: $HTML is self-contained (md-refs=$md, embedded-diagrams=$svg, external-src=$ext)"
fi
exit $status
