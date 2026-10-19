#!/usr/bin/env bash
# Measures cold-start launch time (adb "TotalTime") over 5 runs on one emulator or device. Not part of the gate:
# emulator timing is noisy, so the number goes to reports/android-foundation/README.md, not to a threshold.
#   bash scripts/android-measure-launch.sh [adb-serial]      (default: emulator-5554)
# The serial is always passed explicitly so a phone that happens to be plugged in is never touched.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-emulator-5554}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE=com.medicabinet.app

[[ -f "$APK" ]] || { echo "build the debug APK first: (cd android && ./gradlew :app:assembleDebug)" >&2; exit 1; }
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "device $SERIAL not found (adb devices)" >&2; exit 1; }

"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
times=()
for run in 1 2 3 4 5; do
  "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
  total="$("$ADB" -s "$SERIAL" shell am start -W -n "$PACKAGE/.MainActivity" | tr -d '\r' | sed -n 's/^TotalTime: //p')"
  times+=("$total")
  echo "run $run: ${total} ms"
done
printf '%s\n' "${times[@]}" | awk '{ s += $1 } END { printf "average: %.0f ms over %d runs\n", s / NR, NR }'
