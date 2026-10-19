"""Localization gate: every key in every String Catalog must be translated in every shipped
language, with the same format specifiers in all of them.

    python3 -I scripts/check-strings.py [catalog.xcstrings ...]

Without arguments it checks every *.xcstrings under MediCabinet/. Stdlib only.
"""
import json
import pathlib
import re
import sys

LANGUAGES = ("fr", "en")
SPECIFIER = re.compile(r"%(?:\d+\$)?[@dDuUxXoOfeEgGcCsSp]|%(?:\d+\$)?l+[dDuUxX]")


def units(localization):
    """Every stringUnit inside a localization (plain value or plural/device variations)."""
    found = []
    if "stringUnit" in localization:
        found.append(localization["stringUnit"])
    for variation in localization.get("variations", {}).values():
        for variant in variation.values():
            found.extend(units(variant))
    return found


def signature(unit_list):
    return sorted(SPECIFIER.findall(" ".join(u.get("value", "") for u in unit_list)))


def check(path):
    problems = []
    catalog = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    for key, entry in sorted(catalog.get("strings", {}).items()):
        if entry.get("shouldTranslate") is False:
            continue
        signatures = {}
        for lang in LANGUAGES:
            localization = entry.get("localizations", {}).get(lang)
            unit_list = units(localization) if localization else []
            if not unit_list:
                problems.append(f"{path}: '{key}' has no {lang} translation")
                continue
            for unit in unit_list:
                if unit.get("state") != "translated" or not unit.get("value", "").strip():
                    problems.append(f"{path}: '{key}' [{lang}] is not translated (state={unit.get('state')})")
            signatures[lang] = signature(unit_list)
        if len({tuple(s) for s in signatures.values()}) > 1:
            problems.append(f"{path}: '{key}' has different format specifiers across languages: {signatures}")
    return problems


def main(argv):
    paths = argv or sorted(str(p) for p in pathlib.Path("MediCabinet").rglob("*.xcstrings"))
    if not paths:
        print("no String Catalog found", file=sys.stderr)
        return 1
    problems = [problem for path in paths for problem in check(path)]
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print(f"Localization OK ({len(paths)} catalog(s), languages: {', '.join(LANGUAGES)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
