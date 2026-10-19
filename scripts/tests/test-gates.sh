#!/usr/bin/env bash
# The coverage gate and the version check must fail on bad input and pass on good input.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
C="$ROOT/scripts/tests/coverage"; V="$ROOT/scripts/tests/version"
fail=0
run() { # description, expected(pass|fail), command...
  local desc="$1" expect="$2"; shift 2
  local status=0
  "$@" >/dev/null 2>&1 || status=$?
  if [[ "$expect" == pass && $status -eq 0 ]] || [[ "$expect" == fail && $status -ne 0 ]]; then echo "ok   $desc"; else echo "FAIL $desc (exit $status)"; fail=1; fi
}
gate() { python3 -I "$ROOT/scripts/coverage-gate.py" "$@"; }
run "coverage: both above 80% passes"           pass gate --core "$C/core_ok.json"    --app "$C/app_ok.json"
run "coverage: core 79% fails"                  fail gate --core "$C/core_low.json"   --app "$C/app_ok.json"
run "coverage: app 79% fails"                   fail gate --core "$C/core_ok.json"    --app "$C/app_low.json"
run "coverage: empty core report fails"         fail gate --core "$C/core_empty.json" --app "$C/app_ok.json"
run "coverage: missing app target fails"        fail gate --core "$C/core_ok.json"    --app "$C/app_other_target.json"
run "coverage: threshold is configurable"       pass gate --core "$C/core_low.json"   --app "$C/app_low.json" --threshold 75
run "version: matching versions pass"           pass bash "$ROOT/scripts/check-version.sh" "$V/project-ok.yml"    "$V/changelog-ok.md"
run "version: mismatch fails"                   fail bash "$ROOT/scripts/check-version.sh" "$V/project-other.yml" "$V/changelog-ok.md"
run "version: changelog without entry fails"    fail bash "$ROOT/scripts/check-version.sh" "$V/project-ok.yml"    "$V/changelog-empty.md"
run "version: project without version fails"    fail bash "$ROOT/scripts/check-version.sh" "$V/project-none.yml"  "$V/changelog-ok.md"
exit $fail
