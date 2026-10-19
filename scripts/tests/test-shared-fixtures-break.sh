#!/usr/bin/env bash
# A deliberately wrong shared fixture must make each platform's suite FAIL, proving the suites
# really read fixtures/ and a disagreement cannot go unnoticed.
#   bash scripts/tests/test-shared-fixtures-break.sh [ios|android|all]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BROKEN="$ROOT/scripts/tests/fixtures-broken"
WHICH="${1:-all}"
fail=0

expect_failure() { # description, command...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "FAIL $desc: suite passed on a broken fixture"; fail=1; else echo "ok   $desc"; fi
}

if [[ "$WHICH" == ios || "$WHICH" == all ]]; then
  expect_failure "ios: Swift suite fails on a broken expiry fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ExpiryDateFixtureTests
  expect_failure "ios: Swift suite fails on a broken taxonomy fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter TaxonomyFixtureTests
  expect_failure "ios: Swift suite fails on a broken reference-lookup fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter CIP13FixtureTests
  expect_failure "ios: Swift suite fails on a broken GS1 fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter GS1ParserFixtureTests
  for suite in ScanDebouncerFixtureTests IntakePolicyFixtureTests ProductResolverFixtureTests; do
    expect_failure "ios: Swift suite fails on a broken fixture ($suite)" \
      env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter "$suite"
  done
  for suite in TextNormalizerFixtureTests LocationNameFixtureTests CabinetRowsFixtureTests ScanRemovalFixtureTests; do
    expect_failure "ios: Swift suite fails on a broken fixture ($suite)" \
      env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter "$suite"
  done
  expect_failure "ios: Swift suite fails on a broken expiry-status fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ExpiryRuleFixtureTests
  expect_failure "ios: Swift suite fails on a broken expiring-overview fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ExpiringOverviewFixtureTests
  expect_failure "ios: Swift suite fails on a broken expiry-digest fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ExpiryDigestFixtureTests
  expect_failure "ios: Swift suite fails on a broken code-point fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter CodePointsFixtureTests
  expect_failure "ios: Swift suite fails on broken archive fixtures" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter ArchiveFixtureTests
  expect_failure "ios: Swift suite fails on a broken archive store contract list" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" swift test --package-path "$ROOT/ios/Packages/MediCabinetCore" --filter InMemoryArchiveStoreTests
fi
if [[ ("$WHICH" == android || "$WHICH" == all) && -f "$ROOT/android/gradlew" ]]; then
  expect_failure "android: Kotlin suite fails on a broken expiry fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ExpiryDateFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken taxonomy fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*TaxonomyFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken reference-lookup fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*Cip13FixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken GS1 fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*GS1ParserFixtureTest*'"
  for suite in ScanDebouncerFixtureTest IntakePolicyFixtureTest ProductResolverFixtureTest; do
    expect_failure "android: Kotlin suite fails on a broken fixture ($suite)" \
      env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*$suite*'"
  done
  for suite in TextNormalizerFixtureTest LocationNameFixtureTest CabinetRowsFixtureTest ScanRemovalFixtureTest; do
    expect_failure "android: Kotlin suite fails on a broken fixture ($suite)" \
      env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*$suite*'"
  done
  expect_failure "android: Kotlin suite fails on a broken expiry-status fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ExpiryRuleFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken expiring-overview fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ExpiringOverviewFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken expiry-digest fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ExpiryDigestFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken code-point fixture" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*CodePointsFixtureTest*'"
  expect_failure "android: Kotlin suite fails on broken archive fixtures" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*ArchiveFixtureTest*'"
  expect_failure "android: Kotlin suite fails on a broken archive store contract list" \
    env MEDICABINET_FIXTURES_DIR="$BROKEN" bash -c "cd '$ROOT/android' && ./gradlew --console=plain -q :core:test --tests '*InMemoryArchiveStoreTest*'"
fi
exit $fail
