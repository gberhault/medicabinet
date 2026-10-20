"""Tests of scripts/build-app-icon.py. Run: python3 -I scripts/tests/test-build-app-icon.py"""
import ast
import importlib.util
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


icon = load("build_app_icon", ROOT / "scripts" / "build-app-icon.py")

SVG = "http://www.w3.org/2000/svg"


def source_with(extra=""):
    """The committed source with `extra` appended inside the foreground group."""
    text = (ROOT / "design" / "app-icon" / "medicabinet-icon.svg").read_text()
    marker = '<g id="foreground" transform="translate(54 54) scale(0.94) translate(-54 -54)">'
    assert marker in text
    return text.replace(marker, marker + extra)


def parse(text):
    return ET.fromstring(text)


class SourceValidation(unittest.TestCase):
    def test_committed_source_is_accepted(self):
        icon.validate(icon.load_source(ROOT / "design" / "app-icon" / "medicabinet-icon.svg"))

    def assert_rejected(self, text, *needles):
        with self.assertRaises(icon.IconError) as ctx:
            icon.validate(parse(text))
        for needle in needles:
            self.assertIn(needle, str(ctx.exception))

    def test_rejects_text(self):
        self.assert_rejected(source_with('<text x="1" y="1">M</text>'), "<text>")

    def test_rejects_image(self):
        self.assert_rejected(source_with('<image x="0" y="0" width="1" height="1"/>'), "<image>")

    def test_rejects_use(self):
        self.assert_rejected(source_with('<use x="0" y="0"/>'), "<use>")

    def test_rejects_style_element(self):
        self.assert_rejected(source_with("<style>path{fill:red}</style>"), "<style>")

    def test_rejects_foreign_object(self):
        self.assert_rejected(source_with("<foreignObject/>"), "<foreignObject>")

    def test_rejects_filter(self):
        self.assert_rejected(source_with('<filter id="f"/>'), "<filter>")

    def test_rejects_external_href(self):
        self.assert_rejected(source_with('<path d="M0 0L1 1" href="http://example.com/x.svg"/>'), "href")

    def test_rejects_style_attribute(self):
        self.assert_rejected(source_with('<path d="M0 0L1 1" style="fill:red"/>'), "style")

    def test_rejects_unsupported_transform(self):
        text = (ROOT / "design" / "app-icon" / "medicabinet-icon.svg").read_text()
        good = 'transform="translate(54 54) scale(0.94) translate(-54 -54)"'
        for bad in ("matrix(1 0 0 1 0 0)", "skewX(10)", "scale(1 2)", "translate(1)"):
            with self.subTest(bad):
                self.assert_rejected(text.replace(good, f'transform="{bad}"'), "transform")

    def test_rejects_unknown_paint(self):
        self.assert_rejected(source_with('<path d="M0 0L1 1" fill="red"/>'), "paint")
        self.assert_rejected(source_with('<path d="M0 0L1 1" fill="url(#missing)"/>'), "gradient")

    def test_rejects_bad_path_data(self):
        self.assert_rejected(source_with('<path d="M0 0 javascript(1)"/>'), "path data")

    def test_requires_the_three_parts(self):
        text = (ROOT / "design" / "app-icon" / "medicabinet-icon.svg").read_text()
        for part in ("background", "foreground", "wordmark"):
            with self.subTest(part):
                self.assert_rejected(text.replace(f'id="{part}"', 'id="gone"'), part)


class Hygiene(unittest.TestCase):
    def test_script_imports_only_the_standard_library(self):
        tree = ast.parse((ROOT / "scripts" / "build-app-icon.py").read_text())
        modules = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules.update(a.name.split(".")[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                modules.add(node.module.split(".")[0])
        self.assertTrue(modules)
        self.assertEqual(set(), modules - set(sys.stdlib_module_names))


if __name__ == "__main__":
    unittest.main()
