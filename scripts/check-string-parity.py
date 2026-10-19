"""iOS <-> Android wording parity: the same screen says the same thing on both platforms.

    python3 -I scripts/check-string-parity.py [--ios <xcstrings>] [--android-res <res dir>]

Every iOS String Catalog key `a.b` is expected on Android as `a_b` with identical French and English text
(placeholders normalised: iOS `%1$@` == Android `%1$s`, `%1$lld` == `%1$d`). A plural (iOS `variations.plural`,
Android `<plurals>`) is compared per quantity, and vice versa. A key that exists on one platform
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


def unescape(text):
    return text.replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\")


def android_strings(res, lang):
    """name -> text; a `<plurals>` entry is flattened to `name[quantity]` -> text."""
    folder = "values" if lang == "fr" else "values-en"
    strings = {}
    root = ET.parse(pathlib.Path(res) / folder / "strings.xml").getroot()
    for element in root.iter("string"):
        strings[element.get("name")] = unescape("".join(element.itertext()))
    for element in root.iter("plurals"):
        for item in element.iter("item"):
            strings[f"{element.get('name')}[{item.get('quantity')}]"] = unescape("".join(item.itertext()))
    return strings


def ios_strings(catalog, lang):
    """Android-style key -> (iOS key, text); a plural variation is flattened to `key[quantity]`."""
    found = {}
    for key, entry in catalog.items():
        localization = entry.get("localizations", {}).get(lang, {})
        android_key = key.replace(".", "_")
        if "stringUnit" in localization:
            found[android_key] = (key, localization["stringUnit"]["value"])
        for quantity, variant in localization.get("variations", {}).get("plural", {}).items():
            found[f"{android_key}[{quantity}]"] = (key, variant["stringUnit"]["value"])
    return found


def normalise(text):
    """iOS `%1$@` / `%1$lld` equal Android `%1$s` / `%1$d`."""
    return re.sub(r"%(\d+)\$(?:ll)?d", r"%\1$d", re.sub(r"%(\d+)\$[@s]", r"%\1$s", text))


def check(ios_path, res):
    problems = []
    catalog = json.loads(pathlib.Path(ios_path).read_text(encoding="utf-8"))["strings"]
    for lang in ("fr", "en"):
        android = android_strings(res, lang)
        ios = ios_strings(catalog, lang)
        for android_key, (ios_key, ios_text) in sorted(ios.items()):
            if android_key in IOS_ONLY:
                continue
            if android_key not in android:
                problems.append(f"iOS key '{ios_key}' has no Android string '{android_key}' [{lang}]")
                continue
            if normalise(android[android_key]) != normalise(ios_text):
                problems.append(
                    f"'{ios_key}' [{lang}] differs: iOS={normalise(ios_text)!r} Android={android[android_key]!r}"
                )
        for android_key in sorted(android.keys() - ios.keys() - ANDROID_ONLY.keys()):
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
