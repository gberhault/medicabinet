#!/usr/bin/env bash
# Root gate: shared checks, then each product's gate. Exits non-zero and names the failing step.
#   bash scripts/local-ci.sh              everything
#   bash scripts/local-ci.sh shared|ios|android    one part
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

WHICH="${1:-all}"
CURRENT_STEP="setup"
trap 'status=$?; [[ $status -eq 0 ]] || echo "✗ local-ci FAILED at step: $CURRENT_STEP" >&2' EXIT
step() { CURRENT_STEP="$1"; echo "▶ $1"; }

case "$WHICH" in all|shared|ios|android) ;; *) echo "usage: local-ci.sh [all|shared|ios|android]" >&2; exit 2 ;; esac

if [[ "$WHICH" == all || "$WHICH" == shared ]]; then
  step "shared: fixtures are valid"
  python3 -I scripts/check-fixtures.py
  bash scripts/tests/test-check-fixtures.sh

  step "shared: reference data (build script, committed bundle, golden list)"
  python3 -I scripts/tests/test-build-reference-db.py
  python3 -I scripts/build-reference-db.py verify --apps ios
  bash scripts/tests/test-reference-data-break.sh

  step "shared: iOS and Android say the same thing"
  python3 -I scripts/check-string-parity.py

  step "shared: version checker self-test"
  bash scripts/tests/test-check-version.sh
fi

if [[ "$WHICH" == all || "$WHICH" == ios ]]; then
  step "iOS gate"
  bash scripts/ios-local-ci.sh
fi

if [[ "$WHICH" == all || "$WHICH" == android ]]; then
  if [[ -f scripts/android-local-ci.sh ]]; then
    step "Android gate"
    bash scripts/android-local-ci.sh
  else
    echo "• Android gate not available yet (scripts/android-local-ci.sh)"
  fi
fi

CURRENT_STEP="done"
echo "✓ local-ci OK ($WHICH)"
