#!/usr/bin/env bash
# Fails when line coverage is below 80% for MediCabinetCore or the app.
# Needs: `swift test --enable-code-coverage` (core) and build/ci.xcresult (app) from local-ci.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build
CORE_JSON="$(cd Packages/MediCabinetCore && swift test --show-codecov-path)"
[[ -f "$CORE_JSON" ]] || { echo "core coverage report missing: $CORE_JSON" >&2; exit 1; }
[[ -d build/ci.xcresult ]] || { echo "build/ci.xcresult missing — run the app tests with coverage first" >&2; exit 1; }
xcrun xccov view --report --json build/ci.xcresult > build/coverage-app.json
python3 -I scripts/coverage-gate.py --core "$CORE_JSON" --app build/coverage-app.json --threshold "${COVERAGE_THRESHOLD:-80}"
