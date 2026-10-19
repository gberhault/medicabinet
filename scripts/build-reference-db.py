"""Builds the reference database both apps bundle from the BDPM files (epic #7, specs/reference-data/).

    python3 -I scripts/build-reference-db.py download <empty-dir>
    python3 -I scripts/build-reference-db.py build --bdpm <dir> --data-date YYYY-MM-DD [--previous <db>] [--out <dir>]
    python3 -I scripts/build-reference-db.py verify [--bundle <dir>]

Standard library only, one file: `python3 -I` does not put this directory on sys.path, so helper modules
could not be imported. The BDPM files are untrusted input: any unexpected shape stops the build and nothing
is written.
"""
import datetime
import hashlib
import json
import re
import shutil
import sqlite3
import sys
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

BDPM_HOST = "base-donnees-publique.medicaments.gouv.fr"
# file name -> (encoding, number of tab-separated columns). Encodings differ per file (docs/reference-data.md).
BDPM_FILES = {
    "CIS_bdpm.txt": ("cp1252", 12),
    "CIS_CIP_bdpm.txt": ("utf-8", 13),
    "CIS_COMPO_bdpm.txt": ("cp1252", 8),
    "CIS_MITM.txt": ("cp1252", 7),
}
MAX_NAME_LENGTH = 255  # the longest name BDPM publishes
MAX_REPORTED_PROBLEMS = 20

CIS_PATTERN = re.compile(r"[0-9]{8}")
CIP13_PATTERN = re.compile(r"3400[0-9]{9}")
ATC_PATTERN = re.compile(r"[A-Z][0-9]{2}([A-Z]{1,2}([0-9]{2})?)?")
FRENCH_DATE_PATTERN = re.compile(r"([0-9]{2})/([0-9]{2})/([0-9]{4})")
ISO_DATE_PATTERN = re.compile(r"([0-9]{4})-([0-9]{2})-([0-9]{2})")
CONTROL_CHARACTERS = re.compile(r"[\x00-\x1f\x7f]")


class BuildError(Exception):
    """The build must stop; `problems` are printed one per line and nothing is written."""

    def __init__(self, problems):
        self.problems = [problems] if isinstance(problems, str) else list(problems)
        super().__init__("; ".join(self.problems))


def parse_iso_date(text):
    """Strict YYYY-MM-DD naming a real calendar day, else None."""
    match = ISO_DATE_PATTERN.fullmatch(text)
    if not match:
        return None
    try:
        return datetime.date(int(match[1]), int(match[2]), int(match[3]))
    except ValueError:
        return None


def has_valid_gs1_check_digit(digits):
    """GS1 mod-10 check digit of a numeric code whose last digit is the check digit."""
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(digits[:-1])))
    return (10 - total % 10) % 10 == int(digits[-1])


def normalize(text):
    """Upper case without accents or repeated spaces, so `PARACÉTAMOL` and `paracetamol` compare equal."""
    decomposed = unicodedata.normalize("NFKD", text)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    return re.sub(r"\s+", " ", stripped).strip().upper()


@dataclass
class Product:
    cis: str
    name: str
    form: str
    routes: list
    procedure: str
    substances: set = field(default_factory=set)
    atc: str = ""


@dataclass
class Snapshot:
    products: dict  # cis -> Product
    presentations: dict  # cip13 -> cis
    skipped_orphans: list  # cip13 whose CIS is not in CIS_bdpm.txt
    source_sha256: dict  # file name -> hex digest


def read_table(directory, name, problems):
    """The rows of one BDPM file as (line number, stripped fields). Strict decoding; tab separated; no header."""
    encoding, columns = BDPM_FILES[name]
    path = Path(directory) / name
    if not path.is_file():
        problems.append(f"{name}: file is missing")
        return []
    try:
        text = path.read_bytes().decode(encoding)
    except UnicodeDecodeError as error:
        problems.append(f"{name}: not valid {encoding} at byte {error.start}")
        return []
    rows = []
    for number, line in enumerate(text.split("\n"), start=1):
        line = line[:-1] if line.endswith("\r") else line
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != columns:
            problems.append(f"{name} line {number}: expected {columns} columns, found {len(fields)}")
            continue
        rows.append((number, [f.strip() for f in fields]))
    return rows


def valid_name(value):
    return 1 <= len(value) <= MAX_NAME_LENGTH and not CONTROL_CHARACTERS.search(value)


def read_snapshot(directory):
    """Reads and validates the four BDPM files; raises BuildError listing every problem found."""
    problems = []
    tables = {name: read_table(directory, name, problems) for name in BDPM_FILES}

    products = {}
    for number, row in tables["CIS_bdpm.txt"]:
        cis, name, form, routes, procedure = row[0], row[1], row[2], row[3], row[5]
        if not CIS_PATTERN.fullmatch(cis):
            problems.append(f"CIS_bdpm.txt line {number}: invalid CIS")
        elif not valid_name(name):
            problems.append(f"CIS_bdpm.txt line {number}: name is empty, longer than {MAX_NAME_LENGTH} or has control characters")
        elif cis in products:
            problems.append(f"CIS_bdpm.txt line {number}: duplicated CIS")
        else:
            route_list = [r.strip() for r in routes.split(";") if r.strip()]
            products[cis] = Product(cis, name, form, route_list, procedure)

    presentations, orphans = {}, []
    for number, row in tables["CIS_CIP_bdpm.txt"]:
        cis, cip13 = row[0], row[6]
        if not CIP13_PATTERN.fullmatch(cip13) or not has_valid_gs1_check_digit(cip13):
            problems.append(f"CIS_CIP_bdpm.txt line {number}: invalid CIP13")
        elif cip13 in presentations or cip13 in orphans:
            problems.append(f"CIS_CIP_bdpm.txt line {number}: duplicated CIP13")
        elif cis not in products:
            orphans.append(cip13)
        else:
            presentations[cip13] = cis

    for number, row in tables["CIS_COMPO_bdpm.txt"]:
        cis, substance, nature = row[0], row[3], row[6]
        if not CIS_PATTERN.fullmatch(cis):
            problems.append(f"CIS_COMPO_bdpm.txt line {number}: invalid CIS")
        elif nature not in ("SA", "FT"):
            problems.append(f"CIS_COMPO_bdpm.txt line {number}: unknown component nature")
        elif nature == "SA" and cis in products and substance:
            products[cis].substances.add(substance)

    for number, row in tables["CIS_MITM.txt"]:
        cis, atc = row[0], row[1]
        if not CIS_PATTERN.fullmatch(cis) or not ATC_PATTERN.fullmatch(atc):
            problems.append(f"CIS_MITM.txt line {number}: invalid CIS or ATC code")
        elif cis in products:
            products[cis].atc = atc

    if problems:
        shown = problems[:MAX_REPORTED_PROBLEMS]
        if len(problems) > len(shown):
            shown.append(f"… and {len(problems) - len(shown)} more problems")
        raise BuildError(shown)

    digests = {name: hashlib.sha256((Path(directory) / name).read_bytes()).hexdigest() for name in BDPM_FILES}
    return Snapshot(products, presentations, orphans, digests)


UNCATEGORIZED = "uncategorized"
CONDITION_KEYS = (
    "routesAny", "routesOnly", "formAny", "substancesAny", "substancesOnly", "atcAny", "procedureAny",
)


@dataclass
class Taxonomy:
    ordered: list  # category ids, display order
    assignable: set  # the ones a rule may give (fixtures/categories.json: assignedByBuild)


def load_taxonomy(path):
    cases = json.loads(Path(path).read_text(encoding="utf-8"))["cases"]
    return Taxonomy([c["name"] for c in cases], {c["name"] for c in cases if c["assignedByBuild"]})


@dataclass
class Rule:
    name: str
    category: str
    conditions: dict  # condition key -> list of normalized values


@dataclass
class Rules:
    version: int
    rules: list


def parse_rules(doc, taxonomy):
    """Validates a rules document (reference-data/rules.json); raises BuildError listing every problem."""
    problems, rules, seen = [], [], set()
    version = doc.get("rulesVersion")
    if not isinstance(version, int) or isinstance(version, bool) or version < 1:
        problems.append("rules: rulesVersion must be a positive integer")
    entries = doc.get("rules")
    if not isinstance(entries, list) or not entries:
        problems.append("rules: 'rules' must be a non-empty list")
        entries = []
    for index, entry in enumerate(entries, start=1):
        name = entry.get("name") if isinstance(entry, dict) else None
        label = f"rule {index}" + (f" '{name}'" if isinstance(name, str) and name else "")
        if not isinstance(name, str) or not name.strip():
            problems.append(f"{label}: name is missing")
        elif name in seen:
            problems.append(f"{label}: duplicated rule name")
        seen.add(name)
        category = entry.get("category") if isinstance(entry, dict) else None
        if category not in taxonomy.ordered:
            problems.append(f"{label}: category '{category}' is not in the taxonomy")
        elif category not in taxonomy.assignable:
            problems.append(f"{label}: category '{category}' cannot be assigned by the build")
        when = entry.get("when") if isinstance(entry, dict) else None
        if not isinstance(when, dict) or not when:
            problems.append(f"{label}: 'when' must be a non-empty object")
            continue
        conditions = {}
        for key, values in when.items():
            if key not in CONDITION_KEYS:
                problems.append(f"{label}: unknown condition '{key}'")
            elif not isinstance(values, list) or not values or not all(isinstance(v, str) and v.strip() for v in values):
                problems.append(f"{label}: '{key}' must be a non-empty list of non-empty strings")
            else:
                conditions[key] = [normalize(v) for v in values]
        rules.append(Rule(name if isinstance(name, str) else "", category, conditions))
    if problems:
        raise BuildError(problems)
    return Rules(version, rules)


def load_rules(path, taxonomy):
    return parse_rules(json.loads(Path(path).read_text(encoding="utf-8")), taxonomy)


def starts_a_word(token, text):
    """True when `token` starts a word of `text` (so ASCORBI matches ACIDE ASCORBIQUE but CETIRIZINE does not match LEVOCETIRIZINE)."""
    return re.search(r"(?<![A-Z0-9])" + re.escape(token), text) is not None


def rule_matches(conditions, product):
    routes = [normalize(r) for r in product.routes]
    form = normalize(product.form)
    substances = [normalize(s) for s in product.substances]
    procedure = normalize(product.procedure)
    for key, values in conditions.items():
        if key == "routesAny":
            held = any(r in values for r in routes)
        elif key == "routesOnly":
            held = bool(routes) and all(r in values for r in routes)
        elif key == "formAny":
            held = any(v in form for v in values)
        elif key == "substancesAny":
            held = any(starts_a_word(v, s) for v in values for s in substances)
        elif key == "substancesOnly":
            held = bool(substances) and all(any(starts_a_word(v, s) for v in values) for s in substances)
        elif key == "atcAny":
            held = bool(product.atc) and any(product.atc.startswith(v) for v in values)
        else:  # procedureAny
            held = any(v in procedure for v in values)
        if not held:
            return False
    return True


def classify(product, rules):
    """The category of the first rule whose conditions all hold, else `uncategorized`."""
    for rule in rules.rules:
        if rule_matches(rule.conditions, product):
            return rule.category
    return UNCATEGORIZED


REFERENCE_SCHEMA_VERSION = 1
# ~4.5x the measured 1.78 MB of the 2026-10 snapshot: room for years of cumulative growth (design §3.2).
MAX_REFERENCE_DB_BYTES = 8_000_000
# A snapshot must still hold this share of the presentations that were marketed in the previous database.
MIN_KEPT_RATIO = 0.95
DATABASE_NAME = "reference.sqlite"
MANIFEST_NAME = "reference-manifest.json"

SCHEMA_SQL = """
CREATE TABLE meta (
  key   TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE presentation (
  cip13          TEXT PRIMARY KEY NOT NULL CHECK (length(cip13) = 13 AND cip13 GLOB '3400[0-9]*'),
  name           TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 255),
  category       TEXT NOT NULL,
  withdrawnSince TEXT CHECK (withdrawnSince IS NULL OR withdrawnSince GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
) WITHOUT ROWID;
"""


@dataclass
class Row:
    name: str
    category: str
    withdrawn_since: str = None  # data date of the first build whose snapshot lacked it; None = in the latest snapshot


@dataclass
class Report:
    counts: dict  # presentations, withdrawn
    by_category_before: dict
    by_category_after: dict
    added: list = field(default_factory=list)
    withdrawn: list = field(default_factory=list)
    reinstated: list = field(default_factory=list)
    renamed: list = field(default_factory=list)
    recategorized: list = field(default_factory=list)
    skipped: list = field(default_factory=list)


def connect_read_only(path):
    return sqlite3.connect(f"file:{Path(path).resolve()}?mode=ro", uri=True)


def read_database(path):
    """(meta dict, user_version, {cip13: Row}) of a reference database."""
    try:
        connection = connect_read_only(path)
        try:
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            meta = dict(connection.execute("SELECT key, value FROM meta"))
            rows = {c: Row(n, cat, w) for c, n, cat, w in connection.execute(
                "SELECT cip13, name, category, withdrawnSince FROM presentation ORDER BY cip13")}
        finally:
            connection.close()
    except sqlite3.Error as error:
        raise BuildError(f"{path}: cannot read the reference database ({error})")
    return meta, version, rows


def content_sha256(meta, version, rows):
    """Hash of the canonical content: identical rows give an identical hash on any machine (design §1.2)."""
    lines = [f"user_version\t{version}\n"]
    lines += [f"meta\t{key}\t{meta[key]}\n" for key in sorted(meta)]
    lines += [f"row\t{c}\t{r.name}\t{r.category}\t{r.withdrawn_since or ''}\n" for c, r in sorted(rows.items())]
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest()


def read_previous(path):
    """(data date, {cip13: Row}) of the previously committed database; (None, {}) on the first build."""
    if path is None or not Path(path).is_file():
        return None, {}
    meta, version, rows = read_database(path)
    if version != REFERENCE_SCHEMA_VERSION:
        raise BuildError(f"{path}: reference schema version {version}, this script builds version {REFERENCE_SCHEMA_VERSION}")
    if parse_iso_date(meta.get("dataDate", "")) is None:
        raise BuildError(f"{path}: meta.dataDate is missing or not a date")
    return meta["dataDate"], rows


def merge(snapshot_rows, previous_rows, data_date, accept_drop=False, previous_date=None):
    """The new database rows and what changed (requirements B7). `snapshot_rows`: {cip13: (name, category)}."""
    if previous_date is not None and data_date < previous_date:
        raise BuildError(f"data date {data_date} is older than the previous database ({previous_date})")
    active_previous = {c for c, r in previous_rows.items() if r.withdrawn_since is None}
    kept = len(active_previous & snapshot_rows.keys())
    if active_previous and kept < MIN_KEPT_RATIO * len(active_previous) and not accept_drop:
        raise BuildError(
            f"the snapshot holds only {kept} of the {len(active_previous)} presentations marketed in the previous "
            f"database (< {MIN_KEPT_RATIO:.0%}): truncated download? Pass --accept-drop if this is expected")

    rows = {c: Row(name, category) for c, (name, category) in snapshot_rows.items()}
    report = Report({}, {}, {})
    for cip13, (name, category) in sorted(snapshot_rows.items()):
        old = previous_rows.get(cip13)
        if old is None:
            report.added.append(cip13)
            continue
        if old.withdrawn_since is not None:
            report.reinstated.append(cip13)
        if old.name != name:
            report.renamed.append(cip13)
        if old.category != category:
            report.recategorized.append(cip13)
    for cip13, old in sorted(previous_rows.items()):
        if cip13 in snapshot_rows:
            continue
        rows[cip13] = Row(old.name, old.category, old.withdrawn_since or data_date)
        if old.withdrawn_since is None:
            report.withdrawn.append(cip13)
    return rows, report


def classify_snapshot(snapshot, rules):
    """{cip13: (name, category)}: every presentation takes the name and category of its product."""
    categories = {cis: classify(p, rules) for cis, p in snapshot.products.items()}
    return {c: (snapshot.products[cis].name, categories[cis]) for c, cis in snapshot.presentations.items()}


def count_by_category(rows, taxonomy):
    counts = {category: 0 for category in taxonomy.ordered}
    for row in rows.values():
        counts[row.category] = counts.get(row.category, 0) + 1
    return counts


def write_database(path, rows, data_date, rules_version):
    path = Path(path)
    if path.exists():
        path.unlink()
    connection = sqlite3.connect(path)
    try:
        connection.execute("PRAGMA page_size = 4096")
        connection.execute("PRAGMA journal_mode = DELETE")
        connection.executescript(SCHEMA_SQL)
        connection.execute(f"PRAGMA user_version = {REFERENCE_SCHEMA_VERSION}")
        connection.executemany("INSERT INTO meta VALUES (?, ?)", [("dataDate", data_date), ("rulesVersion", str(rules_version))])
        connection.executemany(
            "INSERT INTO presentation VALUES (?, ?, ?, ?)",
            [(c, r.name, r.category, r.withdrawn_since) for c, r in sorted(rows.items())])
        connection.commit()
        connection.execute("VACUUM")
    except sqlite3.Error as error:
        raise BuildError(f"cannot write the reference database ({error})")
    finally:
        connection.close()
    size = path.stat().st_size
    if size > MAX_REFERENCE_DB_BYTES:
        raise BuildError(f"the reference database is {size} bytes, over the budget of {MAX_REFERENCE_DB_BYTES}")


def manifest_text(manifest):
    return json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def build_manifest(snapshot, rows, data_date, rules_version, taxonomy, digest):
    return {
        "formatVersion": 1,
        "referenceSchemaVersion": REFERENCE_SCHEMA_VERSION,
        "dataDate": data_date,
        "rulesVersion": rules_version,
        "sources": {name: {"sha256": sha} for name, sha in sorted(snapshot.source_sha256.items())},
        "counts": {
            "presentations": len(rows),
            "withdrawn": sum(1 for r in rows.values() if r.withdrawn_since is not None),
            "byCategory": count_by_category(rows, taxonomy),
        },
        "contentSha256": digest,
    }


def build_bundle(bdpm_dir, data_date, out_dir, rules, taxonomy, previous_path=None, accept_drop=False, golden=None):
    """Builds `reference.sqlite` and its manifest into `out_dir`; nothing is written unless everything passed."""

    if parse_iso_date(data_date) is None:
        raise BuildError(f"--data-date '{data_date}' is not a calendar date (YYYY-MM-DD)")
    snapshot = read_snapshot(bdpm_dir)
    previous_date, previous_rows = read_previous(previous_path)
    rows, report = merge(classify_snapshot(snapshot, rules), previous_rows, data_date, accept_drop, previous_date)
    if golden:
        check_golden(rows, golden)
    report.skipped = list(snapshot.skipped_orphans)
    report.by_category_before = count_by_category(previous_rows, taxonomy)
    report.by_category_after = count_by_category(rows, taxonomy)
    report.counts = {"presentations": len(rows), "withdrawn": sum(1 for r in rows.values() if r.withdrawn_since is not None)}

    out_dir = Path(out_dir)
    out_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".reference-build-", dir=out_dir.parent))
    try:
        write_database(staging / DATABASE_NAME, rows, data_date, rules.version)
        meta, version, written = read_database(staging / DATABASE_NAME)
        digest = content_sha256(meta, version, written)
        manifest = build_manifest(snapshot, rows, data_date, rules.version, taxonomy, digest)
        (staging / MANIFEST_NAME).write_text(manifest_text(manifest), encoding="utf-8")
        out_dir.mkdir(parents=True, exist_ok=True)
        for name in (DATABASE_NAME, MANIFEST_NAME):
            (staging / name).replace(out_dir / name)
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    return report


def format_report(report, limit=20):
    lines = [f"presentations: {report.counts['presentations']} (withdrawn: {report.counts['withdrawn']})"]
    for label in ("added", "withdrawn", "reinstated", "renamed", "recategorized", "skipped"):
        items = getattr(report, label)
        shown = ", ".join(items[:limit]) + (f", … ({len(items) - limit} more)" if len(items) > limit else "")
        lines.append(f"{label}: {len(items)}" + (f" — {shown}" if items else ""))
    lines.append("by category (before → after):")
    for category in report.by_category_after:
        lines.append(f"  {category}: {report.by_category_before.get(category, 0)} → {report.by_category_after[category]}")
    return "\n".join(lines)


def check_golden(rows, cases):
    """Raises BuildError when a golden case (name, cip13, expect) does not classify as expected (requirement C6)."""
    problems = []
    for case in cases:
        row = rows.get(case["cip13"])
        if row is None:
            problems.append(f"golden '{case['name']}': CIP13 {case['cip13']} is not in the database")
        elif row.category != case["expect"]:
            problems.append(f"golden '{case['name']}': expected {case['expect']}, got {row.category}")
    if problems:
        raise BuildError(problems)


# --- END OF STAGE T4 ---
