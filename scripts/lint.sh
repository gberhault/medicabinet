#!/usr/bin/env bash
# SwiftLint gate. Fails on violations not in the committed baseline.
#   bash scripts/lint.sh                    check
#   bash scripts/lint.sh --update-baseline  regenerate baseline (only after FIXING findings;
#                                           never to hide a new one)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v swiftlint >/dev/null 2>&1; then
  echo "swiftlint not found — install with: brew install swiftlint" >&2
  exit 1
fi

BASELINE=".swiftlint-baseline.json"
if [[ "${1:-}" == "--update-baseline" ]]; then
  swiftlint lint --quiet --write-baseline "$BASELINE"
  echo "Wrote $BASELINE"
  exit 0
fi

swiftlint lint --strict --quiet --baseline "$BASELINE"
echo "SwiftLint OK"
