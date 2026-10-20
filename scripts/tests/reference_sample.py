"""BDPM-shaped sample files for the build script's tests and for fixtures/reference-sample.sqlite.

Invented products and CIP13 codes (prefix 3400 with a valid GS1 check digit), written in the real encodings:
CIS_CIP in UTF-8, the other files in Windows-1252 with CRLF line ends. Snapshot 2 follows snapshot 1 one month
later: one product disappears (it becomes withdrawn), one is renamed, one changes substances (category), one is new.
"""
from pathlib import Path


def cip13(serial):
    """A 3400 9xxxxxxxx code with a valid GS1 check digit."""
    body = f"34009{serial:07d}"
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return body + str((10 - total % 10) % 10)


# cis, name, form, routes, procedure, substances, atc
PRODUCTS_1 = [
    ("60000001", "TESTAMOL 500 mg, comprimé", "comprimé", "orale", "Procédure nationale", ["PARACÉTAMOL"], ""),
    ("60000002", "TESTRHUME, comprimé", "comprimé", "orale", "Procédure nationale",
     ["PARACÉTAMOL", "CHLORHYDRATE DE PSEUDOÉPHÉDRINE"], ""),
    ("60000003", "TESTCOLLYRE 0,3 %, collyre en solution", "collyre en solution", "ophtalmique",
     "Procédure nationale", ["CHLORHYDRATE D'AZÉLASTINE"], ""),
    ("60000004", "TESTGEL 1 %, gel", "gel", "cutanée", "Procédure nationale", ["DICLOFÉNAC DE DIÉTHYLAMINE"], ""),
    ("60000005", "ŒDÈME TEST, crème", "crème", "cutanée", "Procédure nationale", ["TROLAMINE"], ""),
    ("60000006", "TESTDIGEST 3 g, poudre pour suspension buvable", "poudre pour suspension buvable", "orale",
     "Procédure nationale", ["DIOSMECTITE"], ""),
    ("60000007", "TESTSPRAY, solution pour pulvérisation nasale", "solution pour pulvérisation", "nasale",
     "Procédure nationale", ["CHLORHYDRATE DE XYLOMÉTAZOLINE"], ""),
    ("60000008", "TESTINCONNU, comprimé", "comprimé", "orale", "Procédure nationale", ["SUBSTANCE INCONNUE"], ""),
    ("60000009", "TESTHOMEO, granules", "granules", "orale", "Enreg homéo (Proc. Nat.)",
     ["ARNICA MONTANA POUR PRÉPARATIONS HOMÉOPATHIQUES"], ""),
    ("60000010", "TESTRETIRE 100 mg, comprimé", "comprimé", "orale", "Procédure nationale", ["IBUPROFÈNE"], ""),
    ("60000011", "TESTKARDEGIC 75 mg, poudre pour solution buvable", "poudre pour solution buvable", "orale",
     "Procédure nationale", ["ACÉTYLSALICYLATE DE DL-LYSINE"], "B01AC06"),
    ("60000012", "TESTAMOL 1000 mg, comprimé effervescent", "comprimé effervescent(e)", "orale",
     "Procédure nationale", ["PARACÉTAMOL"], ""),
]
# cis -> CIP13 serials (a product can have several boxes sizes)
PRESENTATIONS_1 = {
    "60000001": [1], "60000002": [2], "60000003": [3], "60000004": [4], "60000005": [5], "60000006": [6],
    "60000007": [7], "60000008": [8], "60000009": [9], "60000010": [10], "60000011": [11], "60000012": [12, 13],
}
ORPHAN_CIS = "69999999"
ORPHAN_SERIAL = 99


def snapshot_2():
    products = [p for p in PRODUCTS_1 if p[0] != "60000010"]
    products = [
        (p[0], "TESTAMOL 500 mg, comprimé pelliculé", *p[2:]) if p[0] == "60000001" else p for p in products
    ]
    products = [(p[0], p[1], p[2], p[3], p[4], ["PARACÉTAMOL"], p[6]) if p[0] == "60000002" else p for p in products]
    products.append(("60000013", "TESTNOUVEAU, gélule", "gélule", "orale", "Procédure nationale",
                     ["LOPÉRAMIDE (CHLORHYDRATE DE)"], ""))
    presentations = {k: v for k, v in PRESENTATIONS_1.items() if k != "60000010"}
    presentations["60000013"] = [14]
    return products, presentations


def write_snapshot(directory, number=1):
    """Writes the four BDPM files of sample snapshot `number` (1 or 2) into `directory`."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    products, presentations = (PRODUCTS_1, PRESENTATIONS_1) if number == 1 else snapshot_2()

    def tsv(rows):
        return "".join("\t".join(r) + "\r\n" for r in rows)

    cis_rows = [[cis, name, form, routes, "Autorisation active", proc, "Commercialisée", "01/01/2020", "", "", " LABO TEST", "Non"]
                for cis, name, form, routes, proc, _subs, _atc in products]
    compo_rows = [[cis, form, "00001", s, "1 mg", "un comprimé", "SA", "1"]
                  for cis, _n, form, _r, _p, subs, _a in products for s in subs]
    compo_rows.append([products[0][0], "comprimé", "00002", "EXCIPIENT TEST", "", "", "FT", "1"])
    mitm_rows = [[cis, atc, name, "LABO", "LABO", "01/10/2026", f"https://example.invalid/{cis}"]
                 for cis, name, _f, _r, _p, _s, atc in products if atc]
    cip_rows = []
    for cis, serials in presentations.items():
        for serial in serials:
            cip_rows.append([cis, f"{serial:07d}", "plaquette(s) de 10 comprimé(s)", "Présentation active",
                             "Déclaration de commercialisation", "16/03/2011", cip13(serial), "oui", "65%",
                             "2,50", "3,52", "1,02", ""])
    cip_rows.append([ORPHAN_CIS, f"{ORPHAN_SERIAL:07d}", "orpheline", "Présentation active",
                     "Déclaration de commercialisation", "16/03/2011", cip13(ORPHAN_SERIAL), "oui", "", "", "", "", ""])

    (directory / "CIS_bdpm.txt").write_bytes(tsv(cis_rows).encode("cp1252"))
    (directory / "CIS_COMPO_bdpm.txt").write_bytes(tsv(compo_rows).encode("cp1252"))
    (directory / "CIS_MITM.txt").write_bytes(tsv(mitm_rows).encode("cp1252"))
    # The presentations file really is UTF-8 with LF line ends.
    (directory / "CIS_CIP_bdpm.txt").write_bytes("".join("\t".join(r) + "\n" for r in cip_rows).encode("utf-8"))


def build_sample(build, taxonomy, rules_path, workdir):
    """Builds the committed sample database: snapshot 1, then snapshot 2 a month later (one product becomes withdrawn).
    Returns the path of the resulting reference.sqlite. `build` is the loaded scripts/build-reference-db.py module."""
    workdir = Path(workdir)
    rules = build.load_rules(rules_path, taxonomy)
    write_snapshot(workdir / "snapshot1", 1)
    write_snapshot(workdir / "snapshot2", 2)
    build.build_bundle(workdir / "snapshot1", "2026-10-18", workdir / "bundle1", rules, taxonomy)
    build.build_bundle(workdir / "snapshot2", "2026-11-18", workdir / "bundle2", rules, taxonomy,
                       previous_path=workdir / "bundle1" / build.DATABASE_NAME, accept_drop=True)
    return workdir / "bundle2" / build.DATABASE_NAME
