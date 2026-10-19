#!/usr/bin/env bash
# android-local-ci must fail at the prerequisites step with an install hint when the JDK or SDK is missing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fail=0
check() { # description, hint fragment, env assignment
  local out status=0
  out="$(env "$3" /bin/bash "$ROOT/scripts/android-local-ci.sh" 2>&1)" || status=$?
  if [[ $status -ne 0 && "$out" == *"$2"* && "$out" == *"FAILED at step: prerequisites"* ]]; then echo "ok   $1"; else echo "FAIL $1"; echo "$out"; fail=1; fi
}
check "a JAVA_HOME without a JDK is reported with an install hint" "brew install --cask temurin" "JAVA_HOME=/nonexistent"
check "an ANDROID_HOME that is not an SDK is reported" "ANDROID_HOME" "ANDROID_HOME=/nonexistent"
exit $fail
