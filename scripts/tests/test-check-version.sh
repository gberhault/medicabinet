#!/usr/bin/env bash
# check-version.sh must pass matching versions and fail every kind of mismatch, per product.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
V="$ROOT/scripts/tests/version"
fail=0
run() { # description, expected(pass|fail), command...
  local desc="$1" expect="$2"; shift 2
  local status=0
  "$@" >/dev/null 2>&1 || status=$?
  if [[ "$expect" == pass && $status -eq 0 ]] || [[ "$expect" == fail && $status -ne 0 ]]; then echo "ok   $desc"; else echo "FAIL $desc (exit $status)"; fail=1; fi
}
check() { bash "$ROOT/scripts/check-version.sh" "$@"; }
run "ios: matching versions pass"             pass check ios "$V/ios-project-ok.yml"    "$V/changelog-ok.md"
run "ios: mismatch fails"                     fail check ios "$V/ios-project-other.yml" "$V/changelog-ok.md"
run "ios: changelog without entry fails"      fail check ios "$V/ios-project-ok.yml"    "$V/changelog-empty.md"
run "ios: project without version fails"     fail check ios "$V/ios-project-none.yml"  "$V/changelog-ok.md"
run "android: matching versions pass"         pass check android "$V/android-build-ok.gradle.kts"    "$V/changelog-ok.md"
run "android: mismatch fails"                 fail check android "$V/android-build-other.gradle.kts" "$V/changelog-ok.md"
run "android: changelog without entry fails"  fail check android "$V/android-build-ok.gradle.kts"    "$V/changelog-empty.md"
run "android: build file without version fails" fail check android "$V/android-build-none.gradle.kts" "$V/changelog-ok.md"
run "android: missing file fails"             fail check android "$V/does-not-exist.kts" "$V/changelog-ok.md"
run "unknown product fails"                   fail check windows
run "no product fails"                        fail check
exit $fail
