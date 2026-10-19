"""Android architecture rules — the Kotlin counterpart of the iOS SwiftLint custom rules.

    python3 -I scripts/android-check-architecture.py [--root <repo root>]

Scans android/core/src and android/app/src (*.kt, AndroidManifest.xml). Regex based: comments are
stripped first, but a string literal naming a forbidden symbol can still trigger a rule. A justified
exception puts `arch-allow:<rule>` in a comment on the same line. Stdlib only.

Rules
  core_forbidden_imports        :core imports no android.* or androidx.*
  core_no_wall_clock            :core main code never reads the wall clock (use the injected Clock)
  sql_confined_to_persistence   androidx.sqlite / android.database only under app/.../persistence/
  no_network                    no network API imports and no INTERNET permission
  log_through_applog            android.util.Log only inside AppLog.kt
  camera_confined_to_scan_camera androidx.camera / zxingcpp only under app/.../features/scan/camera/
"""
import argparse
import pathlib
import re
import sys

IMPORT = r"^\s*import\s+"

RULES = [
    {
        "id": "core_forbidden_imports",
        "message": ":core is pure Kotlin — no android.* or androidx.* imports",
        "pattern": re.compile(IMPORT + r"(android\.|androidx\.)"),
        "applies": lambda p: "/android/core/src/" in p and p.endswith(".kt"),
    },
    {
        "id": "core_no_wall_clock",
        "message": "use the injected Clock instead of reading the wall clock in :core",
        "pattern": re.compile(r"\b(Instant\.now\(|LocalDate\.now\(|LocalDateTime\.now\(|ZonedDateTime\.now\(|"
                              r"System\.currentTimeMillis\(|Clock\.system)"),
        "applies": lambda p: "/android/core/src/main/" in p and p.endswith(".kt") and not p.endswith("/SystemClock.kt"),
    },
    {
        "id": "sql_confined_to_persistence",
        "message": "androidx.sqlite and android.database may only be used under .../persistence/",
        "pattern": re.compile(IMPORT + r"(androidx\.sqlite|android\.database)"),
        "applies": lambda p: "/android/app/src/main/" in p and p.endswith(".kt") and "/persistence/" not in p,
    },
    {
        "id": "no_network",
        "message": "the app makes no network request; a feature that needs one must amend this rule in its spec",
        "pattern": re.compile(IMPORT + r"(java\.net\.(URL|HttpURLConnection|Socket)|javax\.net\.|okhttp3|retrofit2|io\.ktor)"),
        "applies": lambda p: "/android/" in p and p.endswith(".kt"),
    },
    {
        "id": "no_network",
        "message": "the INTERNET permission is not allowed until a feature needs the network",
        "pattern": re.compile(r"android\.permission\.INTERNET"),
        "applies": lambda p: "/android/" in p and p.endswith("AndroidManifest.xml"),
    },
    {
        "id": "camera_confined_to_scan_camera",
        "message": "androidx.camera and zxingcpp may only be used under .../features/scan/camera/",
        "pattern": re.compile(IMPORT + r"(androidx\.camera|zxingcpp)"),
        "applies": lambda p: "/android/app/src/" in p and p.endswith(".kt") and "/features/scan/camera/" not in p,
    },
    {
        "id": "log_through_applog",
        "message": "log through AppLog (it never writes exception messages); android.util.Log is only allowed in AppLog.kt",
        "pattern": re.compile(r"\bandroid\.util\.Log\b"),
        "applies": lambda p: "/android/app/src/main/" in p and p.endswith(".kt") and not p.endswith("/AppLog.kt"),
    },
]


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return [re.sub(r"//.*$", "", line) for line in text.split("\n")]


def scan(root):
    findings = []
    base = pathlib.Path(root) / "android"
    files = [p for sub in ("core/src", "app/src") for p in (base / sub).rglob("*") if p.suffix in (".kt", ".xml")]
    for path in sorted(files):
        posix = path.as_posix()
        if "/build/" in posix:
            continue
        applicable = [r for r in RULES if r["applies"](posix)]
        if not applicable:
            continue
        raw = path.read_text(encoding="utf-8").split("\n")
        code = strip_comments(path.read_text(encoding="utf-8")) if path.suffix == ".kt" else raw
        for number, (line, original) in enumerate(zip(code, raw), start=1):
            for rule in applicable:
                if rule["pattern"].search(line) and f"arch-allow:{rule['id']}" not in original:
                    findings.append(f"{posix}:{number}: [{rule['id']}] {rule['message']}")
    return findings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(pathlib.Path(__file__).resolve().parent.parent))
    args = parser.parse_args()
    findings = scan(args.root)
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        return 1
    print("Android architecture OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
