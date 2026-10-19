"""iOS <-> Android wording parity: the same screen says the same thing on both platforms.

    python3 -I scripts/check-string-parity.py [--ios <xcstrings>] [--android-res <res dir>]

Every iOS String Catalog key `a.b` is expected on Android as `a_b` with identical French and English text
(placeholders normalised: iOS `%1$@` == Android `%1$s`), and vice versa. A key that exists on one platform
only must be listed below with the reason. Stdlib only.
"""
import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

# Keys that legitimately exist on one platform only (name -> reason).
IOS_ONLY = {}
ANDROID_ONLY = {
    "app_name": "iOS takes the app name from InfoPlist.xcstrings (CFBundleDisplayName)",
    "about_back": "iOS shows the system back button; Android needs a content description for its own",
}


def android_strings(res, lang):
    folder = "values" if lang == "fr" else "values-en"
    strings = {}
    for element in ET.parse(pathlib.Path(res) / folder / "strings.xml").getroot().iter("string"):
        text = "".join(element.itertext())
        text = text.replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\")
        strings[element.get("name")] = text
    return strings


def normalise(text):
    return re.sub(r"%(\d+)\$[@s]", r"%\1$s", text)


def check(ios_path, res):
    problems = []
    ios = json.loads(pathlib.Path(ios_path).read_text(encoding="utf-8"))["strings"]
    ios_keys = {key.replace(".", "_"): key for key in ios}
    for lang in ("fr", "en"):
        android = android_strings(res, lang)
        for android_key, ios_key in sorted(ios_keys.items()):
            if android_key in IOS_ONLY:
                continue
            if android_key not in android:
                problems.append(f"iOS key '{ios_key}' has no Android string '{android_key}' [{lang}]")
                continue
            expected = normalise(ios[ios_key]["localizations"][lang]["stringUnit"]["value"])
            if normalise(android[android_key]) != expected:
                problems.append(f"'{ios_key}' [{lang}] differs: iOS={expected!r} Android={android[android_key]!r}")
        for android_key in sorted(android.keys() - ios_keys.keys() - ANDROID_ONLY.keys()):
            problems.append(f"Android string '{android_key}' has no iOS key (or add it to ANDROID_ONLY with a reason) [{lang}]")
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ios", default="ios/MediCabinet/Resources/Localizable.xcstrings")
    parser.add_argument("--android-res", default="android/app/src/main/res")
    args = parser.parse_args()
    problems = check(args.ios, args.android_res)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print("String parity OK (iOS and Android say the same thing)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
