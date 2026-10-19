"""Android coverage gate: line coverage must be >= THRESHOLD for :core and for :app.

    python3 -I scripts/android-coverage-gate.py --core <jacoco xml> --app <jacoco xml> [--threshold 80]

Reads the report-level LINE counter of each JaCoCo XML report. Stdlib only.
"""
import argparse
import sys
import xml.etree.ElementTree as ET

DEFAULT_THRESHOLD = 80.0


def line_counts(path):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise SystemExit(f"{path}: cannot read the JaCoCo report ({error})")
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            return covered, missed + covered
    raise SystemExit(f"{path}: no LINE counter in the report")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", required=True)
    parser.add_argument("--app", required=True)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    args = parser.parse_args()

    failed = False
    for name, path in (("MediCabinetCore (:core)", args.core), ("MediCabinet app (:app)", args.app)):
        covered, total = line_counts(path)
        if total == 0:
            print(f"✗ {name}: no executable lines — the report is empty or the filter is wrong", file=sys.stderr)
            failed = True
            continue
        percent = 100.0 * covered / total
        ok = percent >= args.threshold
        print(f"{'✓' if ok else '✗'} {name}: {percent:.1f}% lines ({covered}/{total}), minimum {args.threshold:.0f}%")
        failed = failed or not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
