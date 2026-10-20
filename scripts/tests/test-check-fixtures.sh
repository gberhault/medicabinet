#!/usr/bin/env bash
# check-fixtures must pass good fixture files and fail every kind of broken one.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
F="$ROOT/scripts/tests/fixtures-check"
fail=0
run() { # description, expected(pass|fail), file
  local status=0
  python3 -I "$ROOT/scripts/check-fixtures.py" "$3" >/dev/null 2>&1 || status=$?
  if [[ "$2" == pass && $status -eq 0 ]] || [[ "$2" == fail && $status -ne 0 ]]; then echo "ok   $1"; else echo "FAIL $1 (exit $status)"; fail=1; fi
}
run "valid case list passes"            pass "$F/ok.json"
run "file without a cases list passes"  pass "$F/no_cases_key_ok.json"
run "duplicate case names fail"         fail "$F/duplicate_names.json"
run "missing formatVersion fails"       fail "$F/no_format_version.json"
run "missing description fails"         fail "$F/no_description.json"
run "empty case list fails"             fail "$F/empty_cases.json"
run "unnamed case fails"                fail "$F/unnamed_case.json"
run "invalid JSON fails"                fail "$F/invalid.json"
run "valid golden archive passes"       pass "$F/archive/v1/ok.medicabinet"
run "archive with a wrong marker fails" fail "$F/archive/v1/wrong_marker.medicabinet"
run "archive outside its version folder fails" fail "$F/archive/v2/wrong_folder.medicabinet"
run "archive that is not JSON fails"    fail "$F/archive/v1/invalid.medicabinet"
run "the real fixtures pass"            pass "$ROOT/fixtures/expiry-dates.json"
run "the real golden archive passes"    pass "$ROOT/fixtures/archive/v1/golden.medicabinet"
exit $fail
