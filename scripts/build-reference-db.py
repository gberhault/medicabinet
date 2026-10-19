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
import re
import sys
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


# --- END OF STAGE T2 ---
