"""Android localization gate: values/strings.xml (French, the fallback) and values-en/strings.xml must
define the same keys, none empty, with the same format placeholders.

    python3 -I scripts/android-check-strings.py [--res <res dir>]

Strings marked translatable="false" are ignored. Stdlib only.
"""
import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]")


def load(path):
    strings = {}
    for element in ET.parse(path).getroot().iter("string"):
        if element.get("translatable") == "false":
            continue
        strings[element.get("name")] = "".join(element.itertext())
    return strings


def check(res):
    problems = []
    fr = load(pathlib.Path(res) / "values" / "strings.xml")
    en = load(pathlib.Path(res) / "values-en" / "strings.xml")
    for key in sorted(fr.keys() - en.keys()):
        problems.append(f"'{key}' is missing in values-en/strings.xml")
    for key in sorted(en.keys() - fr.keys()):
        problems.append(f"'{key}' is missing in values/strings.xml (French)")
    for lang, strings in (("fr", fr), ("en", en)):
        for key, value in sorted(strings.items()):
            if not value.strip():
                problems.append(f"'{key}' [{lang}] is empty")
    for key in sorted(fr.keys() & en.keys()):
        if sorted(PLACEHOLDER.findall(fr[key])) != sorted(PLACEHOLDER.findall(en[key])):
            problems.append(f"'{key}' has different placeholders in fr and en")
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--res", default="android/app/src/main/res")
    args = parser.parse_args()
    problems = check(args.res)
    for problem in problems:
        print(f"{args.res}: {problem}", file=sys.stderr)
    if problems:
        return 1
    print("Android localization OK (fr + en)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
