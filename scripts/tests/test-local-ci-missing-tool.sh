#!/usr/bin/env bash
# local-ci must fail with an install hint when a required tool is missing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(PATH=/usr/bin:/bin /bin/bash "$ROOT/scripts/local-ci.sh" 2>&1)" && { echo "FAIL: expected non-zero exit"; exit 1; }
[[ "$out" == *"brew install"* ]] || { echo "FAIL: no install hint"; echo "$out"; exit 1; }
[[ "$out" == *"FAILED at step: prerequisites"* ]] || { echo "FAIL: step not named"; echo "$out"; exit 1; }
echo "ok   missing tool is reported with an install hint"
