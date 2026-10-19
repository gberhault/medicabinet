"""Tests of scripts/build-reference-db.py. Run: python3 -I scripts/tests/test-build-reference-db.py"""
import importlib.util
import json
import shutil
import sqlite3
import sys
import tempfile
import unittest.mock
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # dataclasses look their module up here
    spec.loader.exec_module(module)
    return module


build = load("build_reference_db", ROOT / "scripts" / "build-reference-db.py")
sample = load("reference_sample", HERE / "reference_sample.py")


class TempDirCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)

    def snapshot_dir(self, number=1, name=None):
        directory = self.tmp / (name or f"snapshot{number}")
        sample.write_snapshot(directory, number)
        return directory


class ReaderTests(TempDirCase):
    def problems_of(self, directory):
        with self.assertRaises(build.BuildError) as raised:
            build.read_snapshot(directory)
        return raised.exception.problems

    def rewrite(self, directory, name, transform, encoding):
        path = directory / name
        path.write_bytes(transform(path.read_bytes().decode(encoding)).encode(encoding))

    def test_reads_the_sample_in_both_encodings(self):
        snapshot = build.read_snapshot(self.snapshot_dir())
        self.assertEqual(len(snapshot.products), 12)
        self.assertEqual(len(snapshot.presentations), 13)
        self.assertEqual(snapshot.products["60000005"].name, "ŒDÈME TEST, crème")  # cp1252 0x8c / 0xc8
        self.assertEqual(snapshot.products["60000001"].substances, {"PARACÉTAMOL"})  # FT row ignored
        self.assertEqual(snapshot.products["60000011"].atc, "B01AC06")
        self.assertEqual(snapshot.products["60000009"].procedure, "Enreg homéo (Proc. Nat.)")

    def test_accepts_crlf_and_lf_line_ends(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("\r\n", "\n"), "cp1252")
        self.rewrite(directory, "CIS_CIP_bdpm.txt", lambda t: t.replace("\n", "\r\n"), "utf-8")
        self.assertEqual(len(build.read_snapshot(directory).products), 12)

    def test_names_are_trimmed_but_otherwise_unaltered(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("\tTESTAMOL 500 mg, comprimé\t", "\t  TESTAMOL 500 mg, comprimé \t"), "cp1252")
        self.assertEqual(build.read_snapshot(directory).products["60000001"].name, "TESTAMOL 500 mg, comprimé")

    def test_orphan_presentation_is_skipped_and_counted(self):
        snapshot = build.read_snapshot(self.snapshot_dir())
        self.assertEqual(snapshot.skipped_orphans, [sample.cip13(sample.ORPHAN_SERIAL)])
        self.assertNotIn(sample.cip13(sample.ORPHAN_SERIAL), snapshot.presentations)

    def test_records_the_hash_of_each_input(self):
        snapshot = build.read_snapshot(self.snapshot_dir())
        self.assertEqual(sorted(snapshot.source_sha256), sorted(build.BDPM_FILES))
        self.assertTrue(all(len(d) == 64 for d in snapshot.source_sha256.values()))

    def test_a_missing_file_fails(self):
        directory = self.snapshot_dir()
        (directory / "CIS_COMPO_bdpm.txt").unlink()
        self.assertIn("CIS_COMPO_bdpm.txt: file is missing", self.problems_of(directory))

    def test_wrong_encoding_fails(self):
        directory = self.snapshot_dir()
        (directory / "CIS_CIP_bdpm.txt").write_bytes(b"60000001\t0000001\tcomprim\xe9\n")  # cp1252 byte in a UTF-8 file
        self.assertTrue(any("not valid utf-8" in p for p in self.problems_of(directory)))

    def test_cp1252_undefined_byte_fails(self):
        directory = self.snapshot_dir()
        (directory / "CIS_MITM.txt").write_bytes(b"60000011\tB01AC06\t\x81\n")
        self.assertTrue(any("not valid cp1252" in p for p in self.problems_of(directory)))

    def test_wrong_column_count_fails_with_the_line_number(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t + "60000099\tONLY TWO COLUMNS\r\n", "cp1252")
        self.assertIn("CIS_bdpm.txt line 13: expected 12 columns, found 2", self.problems_of(directory))

    def test_cip13_that_is_not_13_digits_with_prefix_3400_fails(self):
        for bad in ("340012345678", "34001234567890", "3401234567893", "34ab123456789"):
            with self.subTest(bad=bad):
                directory = self.snapshot_dir(name=f"bad-{bad}")
                good = sample.cip13(1)
                self.rewrite(directory, "CIS_CIP_bdpm.txt", lambda t: t.replace(good, bad), "utf-8")
                self.assertTrue(any("invalid CIP13" in p for p in self.problems_of(directory)))

    def test_cip13_with_a_wrong_check_digit_fails(self):
        directory = self.snapshot_dir()
        good = sample.cip13(1)
        wrong = good[:-1] + str((int(good[-1]) + 1) % 10)
        self.rewrite(directory, "CIS_CIP_bdpm.txt", lambda t: t.replace(good, wrong), "utf-8")
        self.assertTrue(any("invalid CIP13" in p for p in self.problems_of(directory)))

    def test_duplicated_cip13_fails(self):
        directory = self.snapshot_dir()
        path = directory / "CIS_CIP_bdpm.txt"
        first_line = path.read_text(encoding="utf-8").split("\n")[0]
        path.write_text(path.read_text(encoding="utf-8") + first_line + "\n", encoding="utf-8")
        self.assertTrue(any("duplicated CIP13" in p for p in self.problems_of(directory)))

    def test_name_longer_than_255_characters_fails(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("TESTAMOL 500 mg, comprimé", "N" * 256), "cp1252")
        self.assertTrue(any("longer than 255" in p for p in self.problems_of(directory)))

    def test_name_of_exactly_255_characters_is_accepted(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("TESTAMOL 500 mg, comprimé", "N" * 255), "cp1252")
        self.assertEqual(len(build.read_snapshot(directory).products["60000001"].name), 255)

    def test_control_character_in_a_name_fails(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("TESTAMOL 500 mg", "TESTAMOL\x07500 mg"), "cp1252")
        self.assertTrue(any("control characters" in p for p in self.problems_of(directory)))

    def test_invalid_cis_and_atc_fail(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_MITM.txt", lambda t: t.replace("B01AC06", "b01ac"), "cp1252")
        self.assertTrue(any("CIS_MITM.txt" in p and "ATC" in p for p in self.problems_of(directory)))
        directory = self.snapshot_dir(name="cis")
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: t.replace("60000001", "6000001"), "cp1252")
        self.assertTrue(any("invalid CIS" in p for p in self.problems_of(directory)))

    def test_unknown_component_nature_fails(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_COMPO_bdpm.txt", lambda t: t.replace("\tFT\t", "\tXX\t"), "cp1252")
        self.assertTrue(any("unknown component nature" in p for p in self.problems_of(directory)))

    def test_many_problems_are_capped_in_the_report(self):
        directory = self.snapshot_dir()
        self.rewrite(directory, "CIS_bdpm.txt", lambda t: "x\r\n" * 50 + t, "cp1252")
        problems = self.problems_of(directory)
        self.assertEqual(len(problems), build.MAX_REPORTED_PROBLEMS + 1)
        self.assertIn("more problems", problems[-1])

    def test_parse_iso_date(self):
        self.assertEqual(str(build.parse_iso_date("2026-10-18")), "2026-10-18")
        for bad in ("2026-02-30", "2026-13-01", "20261018", "2026-1-1", "", "2026-10-18 "):
            self.assertIsNone(build.parse_iso_date(bad), bad)

    def test_normalize(self):
        self.assertEqual(build.normalize("  Paracétamol   Œdème "), "PARACETAMOL ŒDEME")


TAXONOMY = build.load_taxonomy(ROOT / "fixtures" / "categories.json")


def product(routes="orale", form="comprimé", substances=(), atc="", procedure="Procédure nationale"):
    return build.Product("60000000", "TEST", form, routes.split(";"), procedure, set(substances), atc)


def rules_of(*rules):
    return build.parse_rules({"rulesVersion": 1, "rules": list(rules)}, TAXONOMY)


class ClassifierTests(unittest.TestCase):
    def classify(self, when, p, category="other"):
        rules = rules_of({"name": "r", "when": when, "category": category})
        return build.classify(p, rules)

    def test_taxonomy_comes_from_the_shared_fixture(self):
        self.assertEqual(len(TAXONOMY.ordered), 12)
        self.assertNotIn("first-aid", TAXONOMY.assignable)
        self.assertNotIn("uncategorized", TAXONOMY.assignable)

    def test_routes_any(self):
        self.assertEqual(self.classify({"routesAny": ["nasale"]}, product(routes="orale;nasale")), "other")
        self.assertEqual(self.classify({"routesAny": ["nasale"]}, product(routes="orale")), "uncategorized")

    def test_routes_only(self):
        self.assertEqual(self.classify({"routesOnly": ["orale", "nasale"]}, product(routes="orale;nasale")), "other")
        self.assertEqual(self.classify({"routesOnly": ["orale"]}, product(routes="orale;nasale")), "uncategorized")
        self.assertEqual(self.classify({"routesOnly": ["orale"]}, product(routes="")), "uncategorized")

    def test_form_any_is_a_substring_without_accents(self):
        self.assertEqual(self.classify({"formAny": ["comprimé à sucer"]}, product(form="COMPRIMÉ À SUCER édulcoré")), "other")
        self.assertEqual(self.classify({"formAny": ["sirop"]}, product(form="comprimé")), "uncategorized")

    def test_substances_any_matches_at_the_start_of_a_word(self):
        self.assertEqual(self.classify({"substancesAny": ["ascorbi"]}, product(substances=["ACIDE ASCORBIQUE"])), "other")
        self.assertEqual(self.classify({"substancesAny": ["alginate"]}, product(substances=["SODIUM (ALGINATE DE)"])), "other")
        self.assertEqual(self.classify({"substancesAny": ["cétirizine"]}, product(substances=["DICHLORHYDRATE DE CÉTIRIZINE"])), "other")
        # inside a word is not a match: levocetirizine is not cetirizine
        self.assertEqual(self.classify({"substancesAny": ["cétirizine"]}, product(substances=["LÉVOCÉTIRIZINE"])), "uncategorized")

    def test_substances_any_ignores_case_and_accents(self):
        self.assertEqual(self.classify({"substancesAny": ["PARACETAMOL"]}, product(substances=["paracétamol"])), "other")

    def test_substances_only(self):
        when = {"substancesOnly": ["fluorure"]}
        self.assertEqual(self.classify(when, product(substances=["FLUORURE DE SODIUM"])), "other")
        self.assertEqual(self.classify(when, product(substances=["FLUORURE DE SODIUM", "CHOLÉCALCIFÉROL"])), "uncategorized")
        self.assertEqual(self.classify(when, product(substances=[])), "uncategorized")

    def test_atc_any_is_a_prefix_and_needs_an_atc_code(self):
        self.assertEqual(self.classify({"atcAny": ["B01"]}, product(atc="B01AC06")), "other")
        self.assertEqual(self.classify({"atcAny": ["B01"]}, product(atc="B03BB01")), "uncategorized")
        self.assertEqual(self.classify({"atcAny": ["B01"]}, product(atc="")), "uncategorized")

    def test_procedure_any(self):
        self.assertEqual(self.classify({"procedureAny": ["homéo"]}, product(procedure="Enreg homéo (Proc. Nat.)")), "other")
        self.assertEqual(self.classify({"procedureAny": ["homéo"]}, product()), "uncategorized")

    def test_all_conditions_of_a_rule_must_hold(self):
        when = {"routesAny": ["cutanée"], "substancesAny": ["diclofénac"]}
        self.assertEqual(self.classify(when, product(routes="cutanée", substances=["DICLOFÉNAC"])), "other")
        self.assertEqual(self.classify(when, product(routes="orale", substances=["DICLOFÉNAC"])), "uncategorized")
        self.assertEqual(self.classify(when, product(routes="cutanée", substances=["TROLAMINE"])), "uncategorized")

    def test_first_matching_rule_wins(self):
        rules = rules_of(
            {"name": "a", "when": {"routesAny": ["orale"]}, "category": "digestion"},
            {"name": "b", "when": {"routesAny": ["orale"]}, "category": "allergy"},
        )
        self.assertEqual(build.classify(product(), rules), "digestion")

    def test_rule_documents_that_are_invalid_fail(self):
        good = {"name": "r", "when": {"routesAny": ["orale"]}, "category": "eyes"}
        bad_entries = {
            "unnamed": {**good, "name": ""},
            "unknown category": {**good, "category": "painful"},
            "first-aid refused": {**good, "category": "first-aid"},
            "uncategorized refused": {**good, "category": "uncategorized"},
            "unknown condition": {**good, "when": {"routesSome": ["orale"]}},
            "empty when": {**good, "when": {}},
            "empty list": {**good, "when": {"routesAny": []}},
            "non-string value": {**good, "when": {"routesAny": [3]}},
            "blank value": {**good, "when": {"routesAny": [" "]}},
        }
        for label, entry in bad_entries.items():
            with self.subTest(label), self.assertRaises(build.BuildError):
                rules_of(entry)
        with self.assertRaises(build.BuildError):
            rules_of(good, dict(good))  # duplicated name
        with self.assertRaises(build.BuildError):
            build.parse_rules({"rulesVersion": 1, "rules": []}, TAXONOMY)
        with self.assertRaises(build.BuildError):
            build.parse_rules({"rulesVersion": 0, "rules": [good]}, TAXONOMY)

    def test_the_committed_rules_file_is_valid(self):
        rules = build.load_rules(ROOT / "reference-data" / "rules.json", TAXONOMY)
        self.assertGreaterEqual(rules.version, 1)
        self.assertTrue(all(r.category in TAXONOMY.assignable for r in rules.rules))

    def test_priorities_of_the_committed_rules(self):
        rules = build.load_rules(ROOT / "reference-data" / "rules.json", TAXONOMY)
        cases = [
            ("eye route beats antihistamine", product(routes="ophtalmique", substances=["CHLORHYDRATE D'AZÉLASTINE"]), "eyes"),
            ("nose route beats antihistamine", product(routes="nasale", substances=["CHLORHYDRATE D'AZÉLASTINE"]), "cold-ent"),
            ("pseudoephedrine beats paracetamol", product(substances=["PARACÉTAMOL", "CHLORHYDRATE DE PSEUDOÉPHÉDRINE"]), "cold-ent"),
            ("antiplatelet ATC beats aspirin", product(substances=["ACÉTYLSALICYLATE DE DL-LYSINE"], atc="B01AC06"), "other"),
            ("topical NSAID beats skin", product(routes="cutanée", substances=["DICLOFÉNAC DE DIÉTHYLAMINE"]), "muscles-joints"),
            ("oral NSAID is a painkiller", product(substances=["DICLOFÉNAC SODIQUE"]), "pain-fever"),
            ("lozenge route beats vitamin C", product(routes="voie buccale autre", form="pastille", substances=["ACIDE ASCORBIQUE"]), "cold-ent"),
            ("mouthwash", product(routes="voie buccale autre", form="bain de bouche", substances=["HEXÉTIDINE"]), "mouth-teeth"),
            ("vitamin D with fluoride", product(substances=["CHOLÉCALCIFÉROL", "FLUORURE DE SODIUM"]), "vitamins-minerals"),
            ("fluoride alone", product(substances=["FLUORURE DE SODIUM"]), "mouth-teeth"),
            ("homeopathic gel", product(routes="cutanée", procedure="Enreg homéo (Proc. Nat.)", substances=["ARNICA MONTANA POUR PRÉPARATIONS HOMÉOPATHIQUES"]), "other"),
            ("injectable only", product(routes="intramusculaire;intraveineuse", substances=["MALÉATE DE DEXCHLORPHÉNIRAMINE"]), "other"),
            ("nothing known", product(substances=["SUBSTANCE INCONNUE"]), "uncategorized"),
        ]
        for label, p, expected in cases:
            with self.subTest(label):
                self.assertEqual(build.classify(p, rules), expected)


SAMPLE_RULES = HERE / "reference-data" / "sample-rules.json"


class BuildTests(TempDirCase):
    def build(self, number=1, out="out", previous=None, data_date="2026-10-18", **kwargs):
        # snapshot 2 drops one of 13 presentations: more than the 5 % the guard allows on so small a sample
        kwargs.setdefault("accept_drop", previous is not None)
        rules = build.load_rules(SAMPLE_RULES, TAXONOMY)
        source = self.snapshot_dir(number, name=f"snapshot{number}-{out}")
        report = build.build_bundle(source, data_date, self.tmp / out, rules, TAXONOMY, previous_path=previous, **kwargs)
        return report, self.tmp / out

    def rows(self, out):
        return build.read_database(out / build.DATABASE_NAME)[2]

    def test_first_build_writes_the_database_and_manifest(self):
        report, out = self.build()
        self.assertEqual(sorted(p.name for p in out.iterdir()), [build.MANIFEST_NAME, build.DATABASE_NAME])
        meta, version, rows = build.read_database(out / build.DATABASE_NAME)
        self.assertEqual(version, build.REFERENCE_SCHEMA_VERSION)
        self.assertEqual(meta, {"dataDate": "2026-10-18", "rulesVersion": "1"})
        self.assertEqual(len(rows), 13)
        self.assertEqual(rows[sample.cip13(1)], build.Row("TESTAMOL 500 mg, comprimé", "pain-fever"))
        self.assertEqual(rows[sample.cip13(5)].name, "ŒDÈME TEST, crème")
        self.assertEqual(report.skipped, [sample.cip13(sample.ORPHAN_SERIAL)])
        self.assertEqual(len(report.added), 13)

    def test_every_presentation_of_a_product_takes_its_category(self):
        _, out = self.build()
        rows = self.rows(out)
        self.assertEqual({rows[sample.cip13(12)].category, rows[sample.cip13(13)].category}, {"pain-fever"})

    def test_the_sample_covers_the_categories_it_is_meant_to(self):
        _, out = self.build()
        categories = {r.category for r in self.rows(out).values()}
        self.assertEqual(categories, {"pain-fever", "cold-ent", "eyes", "muscles-joints", "skin-wound", "digestion", "uncategorized", "other"})

    def test_database_is_indexed_by_cip13_and_stores_only_what_the_apps_need(self):
        _, out = self.build()
        connection = build.connect_read_only(out / build.DATABASE_NAME)
        self.addCleanup(connection.close)
        columns = [r[1] for r in connection.execute("PRAGMA table_info(presentation)")]
        self.assertEqual(columns, ["cip13", "name", "category", "withdrawnSince"])
        self.assertEqual(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        plan = " ".join(str(r) for r in connection.execute("EXPLAIN QUERY PLAN SELECT name FROM presentation WHERE cip13 = ?", ("3400900000013",)))
        self.assertIn("PRIMARY KEY", plan)
        self.assertEqual(connection.execute("PRAGMA journal_mode").fetchone()[0], "delete")

    def test_the_schema_rejects_malformed_rows(self):
        _, out = self.build()
        connection = sqlite3.connect(out / build.DATABASE_NAME)
        self.addCleanup(connection.close)
        for cip13, name, withdrawn in (("340090000001", "x", None), ("3401900000013", "x", None), ("3400900000013", "", None),
                                       ("3400900000099", "x", "18/10/2026")):
            with self.subTest(cip13=cip13, name=name, withdrawn=withdrawn), self.assertRaises(sqlite3.IntegrityError):
                connection.execute("INSERT INTO presentation VALUES (?, ?, 'other', ?)", (cip13, name, withdrawn))

    def test_manifest_content(self):
        report, out = self.build()
        manifest = json.loads((out / build.MANIFEST_NAME).read_text(encoding="utf-8"))
        meta, version, rows = build.read_database(out / build.DATABASE_NAME)
        self.assertEqual(manifest["formatVersion"], 1)
        self.assertEqual(manifest["referenceSchemaVersion"], 1)
        self.assertEqual(manifest["dataDate"], "2026-10-18")
        self.assertEqual(manifest["rulesVersion"], 1)
        self.assertEqual(sorted(manifest["sources"]), sorted(build.BDPM_FILES))
        self.assertEqual(manifest["counts"]["presentations"], 13)
        self.assertEqual(manifest["counts"]["withdrawn"], 0)
        self.assertEqual(sum(manifest["counts"]["byCategory"].values()), 13)
        self.assertEqual(list(manifest["counts"]["byCategory"]), sorted(manifest["counts"]["byCategory"]))
        self.assertEqual(manifest["contentSha256"], build.content_sha256(meta, version, rows))

    def test_second_snapshot_adds_renames_recategorizes_and_withdraws(self):
        _, first = self.build(1, out="first")
        report, second = self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-11-18")
        rows = self.rows(second)
        self.assertEqual(report.added, [sample.cip13(14)])
        self.assertEqual(report.withdrawn, [sample.cip13(10)])
        self.assertEqual(report.renamed, [sample.cip13(1)])
        self.assertEqual(report.recategorized, [sample.cip13(2)])
        self.assertEqual(report.reinstated, [])
        self.assertEqual(rows[sample.cip13(1)].name, "TESTAMOL 500 mg, comprimé pelliculé")
        self.assertEqual(rows[sample.cip13(2)].category, "pain-fever")  # lost its decongestant
        # withdrawn products are kept with their last name and category, dated by the first build that lacked them
        self.assertEqual(rows[sample.cip13(10)], build.Row("TESTRETIRE 100 mg, comprimé", "pain-fever", "2026-11-18"))
        self.assertIsNone(rows[sample.cip13(14)].withdrawn_since)
        self.assertEqual(report.counts, {"presentations": 14, "withdrawn": 1})

    def test_withdrawn_date_is_kept_on_later_builds_and_not_reported_again(self):
        _, first = self.build(1, out="first")
        _, second = self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-11-18")
        report, third = self.build(2, out="third", previous=second / build.DATABASE_NAME, data_date="2026-12-18")
        self.assertEqual(self.rows(third)[sample.cip13(10)].withdrawn_since, "2026-11-18")
        self.assertEqual(report.withdrawn, [])

    def test_a_withdrawn_product_that_returns_is_reinstated(self):
        _, first = self.build(1, out="first")
        _, second = self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-11-18")
        report, third = self.build(1, out="third", previous=second / build.DATABASE_NAME, data_date="2026-12-18")
        self.assertEqual(report.reinstated, [sample.cip13(10)])
        self.assertIsNone(self.rows(third)[sample.cip13(10)].withdrawn_since)

    def test_an_older_data_date_fails_and_writes_nothing(self):
        _, first = self.build(1, out="first")
        with self.assertRaisesRegex(build.BuildError, "older than the previous"):
            self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-09-01")
        self.assertFalse((self.tmp / "second").exists())

    def test_the_same_data_date_is_accepted(self):
        _, first = self.build(1, out="first")
        self.build(1, out="second", previous=first / build.DATABASE_NAME, data_date="2026-10-18")

    def test_a_truncated_snapshot_fails_unless_accepted(self):
        _, first = self.build(1, out="first")
        previous = first / build.DATABASE_NAME
        rules = build.load_rules(SAMPLE_RULES, TAXONOMY)
        truncated = self.snapshot_dir(1, name="truncated")
        for name in ("CIS_CIP_bdpm.txt", "CIS_bdpm.txt", "CIS_COMPO_bdpm.txt"):
            path = truncated / name
            lines = path.read_bytes().split(b"\n")
            path.write_bytes(b"\n".join(lines[: len(lines) // 2]))
        with self.assertRaisesRegex(build.BuildError, "truncated download"):
            build.build_bundle(truncated, "2026-11-18", self.tmp / "t1", rules, TAXONOMY, previous_path=previous)
        self.assertFalse((self.tmp / "t1").exists())
        build.build_bundle(truncated, "2026-11-18", self.tmp / "t2", rules, TAXONOMY, previous_path=previous, accept_drop=True)
        self.assertTrue((self.tmp / "t2" / build.DATABASE_NAME).exists())

    def test_builds_are_deterministic(self):
        _, a = self.build(1, out="a")
        _, b = self.build(1, out="b")
        self.assertEqual((a / build.MANIFEST_NAME).read_bytes(), (b / build.MANIFEST_NAME).read_bytes())
        # chained builds too
        _, a2 = self.build(2, out="a2", previous=a / build.DATABASE_NAME, data_date="2026-11-18")
        _, b2 = self.build(2, out="b2", previous=b / build.DATABASE_NAME, data_date="2026-11-18")
        self.assertEqual((a2 / build.MANIFEST_NAME).read_bytes(), (b2 / build.MANIFEST_NAME).read_bytes())

    def test_a_failed_build_leaves_an_existing_output_untouched(self):
        _, out = self.build(1, out="out")
        before = (out / build.DATABASE_NAME).read_bytes()
        broken = self.snapshot_dir(2, name="broken")
        (broken / "CIS_MITM.txt").unlink()
        rules = build.load_rules(SAMPLE_RULES, TAXONOMY)
        with self.assertRaises(build.BuildError):
            build.build_bundle(broken, "2026-11-18", out, rules, TAXONOMY, previous_path=out / build.DATABASE_NAME)
        self.assertEqual((out / build.DATABASE_NAME).read_bytes(), before)
        self.assertEqual(sorted(p.name for p in out.parent.iterdir() if p.name.startswith(".reference-build-")), [])

    def test_a_database_over_the_size_budget_fails_and_writes_nothing(self):
        with unittest.mock.patch.object(build, "MAX_REFERENCE_DB_BYTES", 1000), self.assertRaisesRegex(build.BuildError, "over the budget"):
            self.build()
        self.assertFalse((self.tmp / "out").exists())

    def test_a_bad_data_date_fails(self):
        for bad in ("2026-02-30", "18/10/2026", ""):
            with self.subTest(bad), self.assertRaisesRegex(build.BuildError, "calendar date"):
                self.build(data_date=bad)

    def test_a_previous_database_of_another_schema_version_fails(self):
        _, first = self.build(1, out="first")
        connection = sqlite3.connect(first / build.DATABASE_NAME)
        connection.execute("PRAGMA user_version = 2")
        connection.close()
        with self.assertRaisesRegex(build.BuildError, "schema version 2"):
            self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-11-18")

    def test_a_missing_previous_database_means_a_first_build(self):
        self.build(1, out="out", previous=self.tmp / "does-not-exist.sqlite")

    def test_report_lists_each_kind_of_change_and_category_totals(self):
        _, first = self.build(1, out="first")
        report, _ = self.build(2, out="second", previous=first / build.DATABASE_NAME, data_date="2026-11-18")
        text = build.format_report(report)
        for label in ("added: 1", "withdrawn: 1", "reinstated: 0", "renamed: 1", "recategorized: 1", "skipped: 1"):
            self.assertIn(label, text)
        self.assertIn("pain-fever: 4 → 5", text)
        self.assertIn("cold-ent: 2 → 1", text)

    def test_golden_cases_are_checked_at_build_time(self):
        golden = [{"name": "ok", "cip13": sample.cip13(1), "expect": "pain-fever"}]
        self.build(golden=golden)
        wrong = [{"name": "wrong", "cip13": sample.cip13(1), "expect": "eyes"},
                 {"name": "absent", "cip13": "3400900009999", "expect": "eyes"}]
        with self.assertRaises(build.BuildError) as raised:
            self.build(out="other", golden=wrong)
        self.assertEqual(len(raised.exception.problems), 2)
        self.assertFalse((self.tmp / "other").exists())


if __name__ == "__main__":
    unittest.main(verbosity=1)
