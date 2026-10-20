#!/usr/bin/env bash
# Each fixture must trigger its rule when placed at a path the rule covers; clean files must pass.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIX="$ROOT/scripts/tests/android-architecture"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

check() { # fixture rule target-path(relative to the fake repo) expect(fail|pass)
  local fixture="$1" rule="$2" target="$TMP/$3" expect="$4"
  rm -rf "$TMP/android"
  mkdir -p "$TMP/android/core/src" "$TMP/android/app/src" "$(dirname "$target")"
  cp "$FIX/$fixture" "$target"
  local out status=0
  out="$(python3 -I "$ROOT/scripts/android-check-architecture.py" --root "$TMP" 2>&1)" || status=$?
  if [[ "$expect" == fail ]]; then
    if [[ $status -ne 0 && "$out" == *"[$rule]"* ]]; then echo "ok   $rule triggers ($fixture)"; else echo "FAIL $rule did not trigger ($fixture)"; echo "$out"; fail=1; fi
  else
    if [[ $status -eq 0 ]]; then echo "ok   $fixture passes at ${3#android/}"; else echo "FAIL $fixture should pass at $3"; echo "$out"; fail=1; fi
  fi
}

check core_forbidden_imports.kt   core_forbidden_imports      android/core/src/main/kotlin/Bad.kt fail
check core_forbidden_imports.kt   core_forbidden_imports      android/core/src/test/kotlin/Bad.kt fail
check core_no_wall_clock.kt       core_no_wall_clock          android/core/src/main/kotlin/Bad.kt fail
check core_no_wall_clock.kt       core_no_wall_clock          android/core/src/main/kotlin/SystemClock.kt pass
check core_no_wall_clock.kt       core_no_wall_clock          android/core/src/test/kotlin/Fine.kt pass
check sql_confined_to_persistence.kt sql_confined_to_persistence android/app/src/main/kotlin/features/Bad.kt fail
check sql_confined_to_persistence.kt sql_confined_to_persistence android/app/src/main/kotlin/persistence/Ok.kt pass
check no_network.kt               no_network                  android/app/src/main/kotlin/Bad.kt fail
check no_internet_permission.xml  no_network                  android/app/src/main/AndroidManifest.xml fail
check camera_confined.kt          camera_confined_to_scan_camera android/app/src/main/kotlin/features/Bad.kt fail
check camera_confined.kt          camera_confined_to_scan_camera android/app/src/main/kotlin/features/scan/camera/Ok.kt pass
check reminders_confined.kt       alarms_and_notifications_confined_to_reminders android/app/src/main/kotlin/features/Bad.kt fail
check reminders_confined.kt       alarms_and_notifications_confined_to_reminders android/app/src/main/kotlin/app/Bad.kt fail
check reminders_confined.kt       alarms_and_notifications_confined_to_reminders android/app/src/main/kotlin/reminders/Ok.kt pass
check views_not_persistence.kt    views_not_persistence       android/app/src/main/kotlin/features/cabinet/Bad.kt fail
check views_not_persistence.kt    views_not_persistence       android/app/src/main/kotlin/features/settings/locations/Bad.kt fail
check views_not_persistence.kt    views_not_persistence       android/app/src/main/kotlin/features/scan/Ok.kt pass
check views_not_persistence.kt    views_not_persistence       android/app/src/main/kotlin/persistence/Ok.kt pass
check sharing_confined.kt         sharing_confined_to_transfer android/app/src/main/kotlin/features/settings/Bad.kt fail
check sharing_confined.kt         sharing_confined_to_transfer android/app/src/main/kotlin/persistence/Bad.kt fail
check sharing_confined.kt         sharing_confined_to_transfer android/app/src/main/kotlin/features/transfer/Ok.kt pass
check file_writes_confined.kt     file_writes_confined        android/app/src/main/kotlin/features/scan/Bad.kt fail
check file_writes_confined.kt     file_writes_confined        android/app/src/main/kotlin/app/Bad.kt fail
check file_writes_confined.kt     file_writes_confined        android/app/src/main/kotlin/features/transfer/Ok.kt pass
check file_writes_confined.kt     file_writes_confined        android/app/src/main/kotlin/persistence/Ok.kt pass
check file_writes_confined.kt     file_writes_confined        android/app/src/test/kotlin/Fine.kt pass
check log_through_applog.kt       log_through_applog          android/app/src/main/kotlin/Bad.kt fail
check log_through_applog.kt       log_through_applog          android/app/src/main/kotlin/AppLog.kt pass
check clean_core.kt               none                        android/core/src/main/kotlin/Fine.kt pass
check allowed_exception.kt        none                        android/core/src/main/kotlin/Reader.kt pass
exit $fail
