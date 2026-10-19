#!/usr/bin/env bash
# check-strings must pass good catalogs and fail every kind of incomplete one.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIX="$ROOT/scripts/tests/ios-strings"
fail=0
expect_pass() { if python3 -I "$ROOT/scripts/ios-check-strings.py" "$FIX/$1" >/dev/null 2>&1; then echo "ok   $1 passes"; else echo "FAIL $1 should pass"; fail=1; fi; }
expect_fail() { # fixture, expected message fragment
  local out
  if out="$(python3 -I "$ROOT/scripts/ios-check-strings.py" "$FIX/$1" 2>&1)"; then echo "FAIL $1 should fail"; fail=1
  elif [[ "$out" == *"$2"* ]]; then echo "ok   $1 fails ($2)"; else echo "FAIL $1 failed with the wrong message: $out"; fail=1; fi
}
expect_pass good.xcstrings
expect_pass untranslatable.xcstrings
expect_fail missing_en.xcstrings "no en translation"
expect_fail needs_review.xcstrings "not translated"
expect_fail empty_value.xcstrings "not translated"
expect_fail format_mismatch.xcstrings "format specifiers"
expect_fail plural.xcstrings "not translated"
exit $fail
