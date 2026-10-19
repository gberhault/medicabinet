#!/usr/bin/env bash
# ktlint must fail on badly formatted code and detekt on a smell, proving the gates are live.
# The fixture is dropped into :core temporarily and always removed again.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIX="$ROOT/scripts/tests/android-lint"
TARGET="$ROOT/android/core/src/main/kotlin/com/medicabinet/core/ZzFixture.kt"
trap 'rm -f "$TARGET"' EXIT
fail=0

expect_failure() { # description, fixture, gradle task
  cp "$FIX/$2" "$TARGET"
  if (cd "$ROOT/android" && ./gradlew --console=plain -q ":core:$3" >/dev/null 2>&1); then
    echo "FAIL $1: $3 passed on a bad fixture"; fail=1
  else
    echo "ok   $1"
  fi
  rm -f "$TARGET"
}

expect_failure "ktlint rejects bad formatting" BadFormat.kt ktlintMainSourceSetCheck
expect_failure "detekt rejects an empty catch block" BadSmell.kt detekt
exit $fail
