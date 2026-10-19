"""Validates the shared fixtures: valid JSON, `formatVersion` (int), `description` (str), and for
files with a `cases` list: non-empty, every case named, names unique.

    python3 -I scripts/check-fixtures.py [file.json ...]    # default: fixtures/*.json
"""
import json
import pathlib
import sys

# Build manifests (reference-sample-manifest.json) are generated output in the build script's own format, not case lists.
MANIFEST_SUFFIX = "-manifest.json"


def check(path):
    problems = []
    try:
        doc = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"{path}: cannot read as JSON ({error})"]
    if not isinstance(doc.get("formatVersion"), int):
        problems.append(f"{path}: missing integer formatVersion")
    if not isinstance(doc.get("description"), str) or not doc["description"].strip():
        problems.append(f"{path}: missing description")
    if "cases" in doc:
        cases = doc["cases"]
        if not isinstance(cases, list) or not cases:
            problems.append(f"{path}: cases must be a non-empty list")
        else:
            names = [c.get("name") if isinstance(c, dict) else None for c in cases]
            if any(not n for n in names):
                problems.append(f"{path}: every case needs a name")
            seen = set()
            for n in names:
                if n in seen:
                    problems.append(f"{path}: duplicate case name '{n}'")
                seen.add(n)
    return problems


def main(argv):
    paths = argv or sorted(str(p) for p in pathlib.Path("fixtures").glob("*.json") if not p.name.endswith(MANIFEST_SUFFIX))
    if not paths:
        print("no fixtures found", file=sys.stderr)
        return 1
    problems = [p for path in paths for p in check(path)]
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print(f"Fixtures OK ({len(paths)} file(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
