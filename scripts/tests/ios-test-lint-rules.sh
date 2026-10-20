#!/usr/bin/env bash
# Each fixture must trigger its custom rule when placed at a path the rule covers,
# and the clean fixture must pass. Run from anywhere.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIX="$ROOT/scripts/tests/ios-lint"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

check() { # fixture rule target-path expect(fail|pass)
  local fixture="$1" rule="$2" target="$TMP/$3" expect="$4"
  mkdir -p "$(dirname "$target")"
  cp "$FIX/$fixture" "$target"
  local out status=0
  out="$(swiftlint lint --strict --no-cache --config "$ROOT/ios/.swiftlint.yml" "$target" 2>&1)" || status=$?
  if [[ "$expect" == "fail" ]]; then
    if [[ $status -ne 0 && "$out" == *"$rule"* ]]; then echo "ok   $rule triggers"; else echo "FAIL $rule did not trigger"; echo "$out"; fail=1; fi
  else
    if [[ $status -eq 0 ]]; then echo "ok   $fixture passes"; else echo "FAIL $fixture should pass"; echo "$out"; fail=1; fi
  fi
}

check core_forbidden_imports.swift core_forbidden_imports Packages/MediCabinetCore/Sources/MediCabinetCore/Bad.swift fail
check core_no_wall_clock.swift core_no_wall_clock Packages/MediCabinetCore/Sources/MediCabinetCore/Bad.swift fail
check grdb_confined_to_persistence.swift grdb_confined_to_persistence MediCabinet/Sources/Features/Bad.swift fail
check grdb_confined_to_persistence.swift grdb_confined_to_persistence MediCabinet/Sources/Persistence/Allowed.swift pass
check camera_confined.swift camera_confined_to_scan_camera MediCabinet/Sources/Features/Bad.swift fail
check camera_confined.swift camera_confined_to_scan_camera MediCabinet/Sources/Features/Scan/Camera/Allowed.swift pass
check no_network.swift no_network MediCabinet/Sources/App/Bad.swift fail
check log_privacy_public.swift no_public_log_privacy MediCabinet/Sources/App/Bad.swift fail
check clean.swift none Packages/MediCabinetCore/Sources/MediCabinetCore/Fine.swift pass
exit $fail
