"""Tests of scripts/build-reference-db.py. Run: python3 -I scripts/tests/test-build-reference-db.py"""
import importlib.util
import shutil
import sys
import tempfile
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
