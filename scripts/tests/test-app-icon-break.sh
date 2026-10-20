#!/usr/bin/env bash
# `build-app-icon.py check` must pass the committed state and fail every way the generated icon can drift from its
# source or break a rule: an edited source or generated file, a replaced or alpha-carrying PNG, a <text> element in
# the artwork, the manifest going back to a system icon, a Play asset bundled in an app (requirements R1, R3, R5, A2,
# I1, P4).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0

SOURCE=design/app-icon/medicabinet-icon.svg
IOS_SET=ios/MediCabinet/Resources/Assets.xcassets/AppIcon.appiconset
ANDROID_RES=android/app/src/main/res
MANIFEST=android/app/src/main/AndroidManifest.xml

fresh() { # name -> a copy of just the files `check` reads, in $WORK/name
  local dir="$WORK/$1"
  mkdir -p "$dir/design" "$dir/$IOS_SET" "$dir/$ANDROID_RES" "$dir/android/app/src/main" "$dir/ios/MediCabinet"
  cp -R "$ROOT/design/app-icon" "$dir/design/"
  cp "$ROOT/$IOS_SET"/* "$dir/$IOS_SET/"
  cp -R "$ROOT/$ANDROID_RES/drawable" "$ROOT/$ANDROID_RES/mipmap-anydpi" "$dir/$ANDROID_RES/"
  cp "$ROOT/$MANIFEST" "$dir/$MANIFEST"
  echo "$dir"
}

check() { python3 -I "$ROOT/scripts/build-app-icon.py" check --root "$1" 2>&1; }

expect() { # description, pass|fail, root, [output fragment the failure must contain]
  local desc="$1" expect="$2" dir="$3" fragment="${4:-}" out status=0
  out="$(check "$dir")" || status=$?
  if [[ "$expect" == pass && $status -eq 0 ]]; then echo "ok   $desc"
  elif [[ "$expect" == fail && $status -ne 0 && "$out" == *"$fragment"* ]]; then echo "ok   $desc"
  else echo "FAIL $desc (exit $status)"; echo "$out" | tail -3; fail=1; fi
}

d="$(fresh committed)"
expect "the committed state passes" pass "$d"

d="$(fresh source)"
sed -i.bak 's/#FBBF24/#FF0000/' "$d/$SOURCE" && rm "$d/$SOURCE.bak"
expect "an edited source fails" fail "$d" "does not match the source"

d="$(fresh xml)"
sed -i.bak 's/#FFFBBF24/#FFFF0000/' "$d/$ANDROID_RES/drawable/ic_launcher_foreground.xml" && rm "$d/$ANDROID_RES/drawable/ic_launcher_foreground.xml.bak"
expect "an edited generated XML fails" fail "$d" "ic_launcher_foreground.xml"

d="$(fresh png)"
cp "$d/$IOS_SET/AppIcon-tinted.png" "$d/$IOS_SET/AppIcon-light.png"
expect "a replaced PNG fails" fail "$d" "AppIcon-light.png"

d="$(fresh alpha)"
python3 -I - "$ROOT/scripts/build-app-icon.py" "$d/$IOS_SET/AppIcon-light.png" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("icon", sys.argv[1])
icon = importlib.util.module_from_spec(spec); spec.loader.exec_module(icon)
open(sys.argv[2], "wb").write(icon.encode_png(1024, 1024, 4, bytes([13, 148, 136, 255]) * (1024 * 1024)))
PY
expect "an iOS PNG with an alpha channel fails" fail "$d" "alpha"

d="$(fresh text)"
sed -i.bak 's|<g id="foreground"|<text x="1" y="1">M</text><g id="foreground"|' "$d/$SOURCE" && rm "$d/$SOURCE.bak"
expect "a <text> element in the source fails" fail "$d" "<text>"

d="$(fresh system-icon)"
sed -i.bak 's|@mipmap/ic_launcher"|@android:drawable/sym_def_app_icon"|' "$d/$MANIFEST" && rm "$d/$MANIFEST.bak"
expect "the manifest pointing back at a system icon fails" fail "$d" "android:icon"

d="$(fresh night)"
mkdir -p "$d/$ANDROID_RES/mipmap-night-anydpi" && cp "$d/$ANDROID_RES/mipmap-anydpi/ic_launcher.xml" "$d/$ANDROID_RES/mipmap-night-anydpi/"
expect "a -night launcher resource fails" fail "$d" "-night"

d="$(fresh play-in-app)"
mkdir -p "$d/android/app/src/main/res/drawable" && cp "$d/design/app-icon/play/icon-512.png" "$d/android/app/src/main/res/drawable/"
expect "a Play asset bundled in the Android app fails" fail "$d" "icon-512.png"

exit $fail
