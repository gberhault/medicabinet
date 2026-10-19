#!/usr/bin/env bash
# Android local CI gate — run before opening an Android PR (the root scripts/local-ci.sh runs it too).
# Exits non-zero and names the failing step.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"

CURRENT_STEP="setup"
trap 'status=$?; [[ $status -eq 0 ]] || echo "✗ android-local-ci FAILED at step: $CURRENT_STEP" >&2' EXIT
step() { CURRENT_STEP="$1"; echo "▶ $1"; }
die() { CURRENT_STEP="prerequisites"; echo "$1" >&2; exit 1; }

# ---- prerequisites: a JDK >= 17 and the Android SDK -------------------------------------------
java_major() { "$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/'; }

if [[ -n "${JAVA_HOME:-}" ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || die "JAVA_HOME ($JAVA_HOME) does not contain a JDK — install one with: brew install --cask temurin@21"
else
  for version in 21 17; do
    if home="$(/usr/libexec/java_home -v "$version" 2>/dev/null)"; then export JAVA_HOME="$home"; break; fi
  done
  if [[ -z "${JAVA_HOME:-}" ]]; then
    command -v java >/dev/null 2>&1 || die "no JDK found — install one with: brew install --cask temurin@21"
    JAVA_HOME="$(cd "$(dirname "$(command -v java)")/.." && pwd)"; export JAVA_HOME
  fi
fi
major="$(java_major "$JAVA_HOME/bin/java")"
[[ "$major" =~ ^[0-9]+$ && "$major" -ge 17 ]] || die "JDK 17 or newer required (found: $major) — brew install --cask temurin@21"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  [[ -d "$ANDROID_HOME/platforms" ]] || die "ANDROID_HOME ($ANDROID_HOME) is not an Android SDK — install Android Studio or the command-line tools"
else
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -d "$candidate/platforms" ]]; then export ANDROID_HOME="$candidate"; break; fi
  done
  [[ -n "${ANDROID_HOME:-}" ]] || die "Android SDK not found — install Android Studio (or set ANDROID_HOME)"
fi
[[ -f local.properties ]] || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

GRADLE=(./gradlew --console=plain -q)

step "assemble debug app"
"${GRADLE[@]}" :app:assembleDebug

step "detekt + ktlint (against baselines)"
"${GRADLE[@]}" detekt ktlintCheck

step "localization (fr + en complete)"
python3 -I "$ROOT/scripts/android-check-strings.py" --res "$ROOT/android/app/src/main/res"
bash "$ROOT/scripts/tests/test-android-strings.sh"

step "architecture rules"
python3 -I "$ROOT/scripts/android-check-architecture.py" --root "$ROOT"
bash "$ROOT/scripts/tests/test-android-architecture.sh"

step "lint gate fixtures (a bad file fails ktlint and detekt)"
bash "$ROOT/scripts/tests/test-android-lint-gates.sh"

step "shared fixtures: a wrong fixture fails the Kotlin suite"
bash "$ROOT/scripts/tests/test-shared-fixtures-break.sh" android

step "unit tests (:core, :app)"
"${GRADLE[@]}" :core:test :app:testDebugUnitTest

step "coverage gate (>= 80% lines: core and app)"
bash "$ROOT/scripts/tests/test-android-coverage-gate.sh"
bash "$ROOT/scripts/android-coverage-gate.sh"

step "version matches CHANGELOG"
bash "$ROOT/scripts/check-version.sh" android

step "instrumented tests compile (build only)"
"${GRADLE[@]}" :app:compileDebugAndroidTestKotlin

CURRENT_STEP="done"
echo "✓ android-local-ci OK"
