#!/usr/bin/env bash
# Fails when line coverage is below 80% for :core or :app. Runs the tests if the reports are stale.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
./gradlew --console=plain -q :core:test :app:jacocoTestReport
python3 -I "$ROOT/scripts/android-coverage-gate.py" \
  --core core/build/reports/jacoco/test/jacocoTestReport.xml \
  --app app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml \
  --threshold "${COVERAGE_THRESHOLD:-80}"
