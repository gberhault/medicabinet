"""Rewrites fixtures/reference-sample.sqlite from the sample BDPM files (run after changing reference_sample.py or the
build script's output format; the build script's tests fail until the committed sample matches).

    python3 -I scripts/tests/write-reference-sample.py
"""
import importlib.util
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


build = load("build_reference_db", ROOT / "scripts" / "build-reference-db.py")
sample = load("reference_sample", HERE / "reference_sample.py")

with tempfile.TemporaryDirectory() as work:
    taxonomy = build.load_taxonomy(ROOT / "fixtures" / "categories.json")
    database = sample.build_sample(build, taxonomy, HERE / "reference-data" / "sample-rules.json", work)
    shutil.copyfile(database, ROOT / "fixtures" / "reference-sample.sqlite")
    shutil.copyfile(database.parent / build.MANIFEST_NAME, ROOT / "fixtures" / "reference-sample-manifest.json")
print("wrote fixtures/reference-sample.sqlite and fixtures/reference-sample-manifest.json")
