"""Validates the shared fixtures: valid JSON, `formatVersion` (int), `description` (str), and for
files with a `cases` list: non-empty, every case named, names unique. Golden archives
(`fixtures/archive/v<N>/*.medicabinet`) must be JSON with "archive": "medicabinet" and "formatVersion": N.

    python3 -I scripts/check-fixtures.py [file ...]    # default: fixtures/*.json and the golden archives
"""
import json
import pathlib
import sys

# Build manifests (reference-sample-manifest.json) are generated output in the build script's own format, not case lists.
MANIFEST_SUFFIX = "-manifest.json"


def check_archive(path):
    """A golden archive: the marker, and a formatVersion equal to the v<N> folder it lives in."""
    try:
        doc = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"{path}: cannot read as JSON ({error})"]
    if not isinstance(doc, dict) or doc.get("archive") != "medicabinet":
        return [f'{path}: not a MediCabinet archive ("archive": "medicabinet" expected)']
    folder = pathlib.Path(path).parent.name
    if not isinstance(doc.get("formatVersion"), int) or folder != f"v{doc['formatVersion']}":
        return [f"{path}: formatVersion must be an integer equal to its folder name ({folder})"]
    return []


def check(path):
    if str(path).endswith(".medicabinet"):
        return check_archive(path)
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
    fixtures = pathlib.Path("fixtures")
    json_files = [p for p in fixtures.glob("*.json") if not p.name.endswith(MANIFEST_SUFFIX)]
    paths = argv or sorted(str(p) for p in [*json_files, *fixtures.glob("archive/v*/*.medicabinet")])
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
