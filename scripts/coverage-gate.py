"""Coverage gate: line coverage must be >= THRESHOLD for the core package and for the app.

    python3 -I scripts/coverage-gate.py --core <llvm-cov export json> --app <xccov report json> [--threshold 80]

Core: only files under Sources/MediCabinetCore/ count (not tests, not the test-support library).
App:  the `MediCabinet.app` target of the xcresult report. Stdlib only.
"""
import argparse
import json
import sys

DEFAULT_THRESHOLD = 80.0


def core_coverage(path):
    data = json.load(open(path, encoding="utf-8"))["data"][0]
    count = covered = 0
    for file in data["files"]:
        if "/Sources/MediCabinetCore/" in file["filename"]:
            count += file["summary"]["lines"]["count"]
            covered += file["summary"]["lines"]["covered"]
    return covered, count


def app_coverage(path):
    report = json.load(open(path, encoding="utf-8"))
    for target in report["targets"]:
        if target["name"] == "MediCabinet.app":
            return target["coveredLines"], target["executableLines"]
    raise SystemExit(f"{path}: no MediCabinet.app target in the coverage report")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", required=True)
    parser.add_argument("--app", required=True)
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    args = parser.parse_args()

    failed = False
    for name, (covered, count) in (("MediCabinetCore", core_coverage(args.core)), ("MediCabinet app", app_coverage(args.app))):
        if count == 0:
            print(f"✗ {name}: no executable lines found — the report is empty or the filter is wrong", file=sys.stderr)
            failed = True
            continue
        percent = 100.0 * covered / count
        ok = percent >= args.threshold
        print(f"{'✓' if ok else '✗'} {name}: {percent:.1f}% lines ({covered}/{count}), minimum {args.threshold:.0f}%")
        failed = failed or not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
