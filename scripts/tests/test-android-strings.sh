#!/usr/bin/env bash
# android-check-strings and check-string-parity must pass good resources and fail every kind of bad one.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="$ROOT/scripts/tests/android-strings"; P="$ROOT/scripts/tests/string-parity"
fail=0
expect() { # description, pass|fail, message fragment ("" for none), command...
  local desc="$1" want="$2" fragment="$3"; shift 3
  local out status=0
  out="$("$@" 2>&1)" || status=$?
  if [[ "$want" == pass && $status -eq 0 ]]; then echo "ok   $desc"
  elif [[ "$want" == fail && $status -ne 0 && "$out" == *"$fragment"* ]]; then echo "ok   $desc"
  else echo "FAIL $desc (exit $status)"; echo "$out"; fail=1; fi
}
strings() { python3 -I "$ROOT/scripts/android-check-strings.py" --res "$A/$1"; }
parity() { python3 -I "$ROOT/scripts/check-string-parity.py" --ios "$P/ios.xcstrings" --android-res "$P/$1"; }

expect "strings: matching fr/en pass"                    pass ""                 strings good
expect "strings: key missing in English fails"           fail "missing in values-en" strings missing_en
expect "strings: key missing in French fails"            fail "(French)"         strings missing_fr
expect "strings: empty value fails"                      fail "is empty"         strings empty_value
expect "strings: different placeholders fail"            fail "different placeholders" strings placeholder_mismatch
expect "strings: untranslatable strings are ignored"     pass ""                 strings untranslatable_only_in_one
expect "parity: same wording passes (placeholders and apostrophes normalised)" pass "" parity ok
expect "parity: different wording fails"                 fail "differs"          parity different_wording
expect "parity: iOS key missing on Android fails"        fail "has no Android string" parity missing_android_key
expect "parity: Android-only key fails"                  fail "has no iOS key"   parity android_extra_key
expect "parity: different placeholders fail"             fail "differs"          parity different_placeholder
exit $fail
