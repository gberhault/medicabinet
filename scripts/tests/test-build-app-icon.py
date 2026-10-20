"""Tests of scripts/build-app-icon.py. Run: python3 -I scripts/tests/test-build-app-icon.py"""
import ast
import importlib.util
import shutil
import sys
import tempfile
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


def svg_doc(body, defs=""):
    return parse(f'<svg xmlns="{SVG}" viewBox="0 0 108 108"><defs>{defs}</defs>{body}</svg>')


def vector(body, defs="", variant="light", part="p"):
    root = svg_doc(f'<g id="{part}">{body}</g>', defs)
    return icon.vector_drawable(root, part, icon.VARIANTS[variant])


class VectorMapping(unittest.TestCase):
    def test_rect_with_gradient_becomes_a_path_with_absolute_gradient_coordinates(self):
        xml = vector('<rect x="10" y="20" width="100" height="50" fill="url(#g)"/>',
                     '<linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0D9488"/>'
                     '<stop offset="1" stop-color="#115E59"/></linearGradient>')
        self.assertIn('android:pathData="M10,20h100v50h-100z"', xml)
        self.assertIn('xmlns:aapt="http://schemas.android.com/aapt"', xml)
        self.assertIn('android:startX="10" android:startY="20" android:endX="10" android:endY="70"', xml)
        self.assertIn('<item android:offset="0" android:color="#FF0D9488"/>', xml)
        self.assertIn('<item android:offset="1" android:color="#FF115E59"/>', xml)

    def test_stroke_attributes_are_inherited_from_the_group(self):
        xml = vector('<g fill="none" stroke="#FFFFFF" stroke-width="10" stroke-linecap="round" '
                     'stroke-linejoin="round"><path d="M35 76V36L54 55"/></g>')
        self.assertIn('android:pathData="M35 76V36L54 55"', xml)
        for expected in ('android:strokeColor="#FFFFFFFF"', 'android:strokeWidth="10"',
                         'android:strokeLineCap="round"', 'android:strokeLineJoin="round"'):
            self.assertIn(expected, xml)
        self.assertNotIn("fillColor", xml)

    def test_a_path_without_fill_is_black_like_in_svg(self):
        xml = vector('<path d="M0 0h1v1z"/>')
        self.assertIn('android:fillColor="#FF000000"', xml)
        self.assertNotIn("strokeColor", xml)

    def test_transforms_become_nested_groups_in_svg_order(self):
        xml = vector('<g transform="translate(54 54) scale(0.94) translate(-54 -54)"><path d="M0 0h1v1z"/></g>')
        order = [xml.index(k) for k in ('translateX="54"', 'scaleX="0.94"', 'translateX="-54"', "<path")]
        self.assertEqual(order, sorted(order))
        self.assertEqual(3, xml.count("<group"))
        self.assertEqual(3, xml.count("</group>"))

    def test_rotate_keeps_its_pivot(self):
        xml = vector('<g transform="rotate(-45 54 72)"><path d="M0 0h1v1z"/></g>')
        self.assertIn('android:rotation="-45" android:pivotX="54" android:pivotY="72"', xml)

    def test_monochrome_is_opaque_black_without_gradient(self):
        xml = vector('<rect x="0" y="0" width="108" height="108" fill="url(#g)"/>'
                     '<path d="M0 0h1v1z" fill="#FBBF24"/><path d="M0 0h1v1z" fill="#FFFFFF" stroke="#0D9488"/>',
                     '<linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0D9488"/>'
                     '<stop offset="1" stop-color="#115E59"/></linearGradient>', variant="monochrome")
        self.assertNotIn("gradient", xml)
        self.assertNotIn("aapt", xml)
        self.assertEqual(set(), set(__import__("re").findall(r'#[0-9A-F]{8}', xml)) - {"#FF000000"})

    def test_gradient_on_a_path_is_rejected(self):
        with self.assertRaises(icon.IconError):
            vector('<path d="M0 0h1v1z" fill="url(#g)"/>',
                   '<linearGradient id="g"><stop offset="0" stop-color="#000000"/></linearGradient>')

    def test_output_is_deterministic_and_marked_generated(self):
        a = vector('<path d="M0 0h1v1z"/>')
        self.assertEqual(a, vector('<path d="M0 0h1v1z"/>'))
        self.assertIn("Generated by scripts/build-app-icon.py", a)
        self.assertTrue(a.startswith('<?xml version="1.0" encoding="utf-8"?>\n'))
        self.assertTrue(a.endswith("</vector>\n"))

    def test_adaptive_icon_wires_the_three_layers(self):
        xml = icon.adaptive_icon_xml()
        for layer in ("background", "foreground", "monochrome"):
            self.assertIn(f'<{layer} android:drawable="@drawable/ic_launcher_{layer}"/>', xml)


class AndroidOutputs(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        (self.root / "design" / "app-icon").mkdir(parents=True)
        shutil.copy(ROOT / icon.SOURCE_RELATIVE, self.root / icon.SOURCE_RELATIVE)
        self.manifest = self.root / icon.MANIFEST_RELATIVE
        self.manifest.parent.mkdir(parents=True)
        shutil.copy(ROOT / icon.MANIFEST_RELATIVE, self.manifest)
        icon.command_build(self.root)

    def test_build_writes_every_android_file_and_check_passes(self):
        for relative in icon.android_outputs(icon.load_source(self.root / icon.SOURCE_RELATIVE)):
            self.assertTrue((self.root / relative).is_file(), relative)
        icon.command_check(self.root)

    def test_check_fails_on_an_edited_generated_xml(self):
        target = self.root / "android/app/src/main/res/drawable/ic_launcher_foreground.xml"
        target.write_text(target.read_text().replace("#FFFBBF24", "#FFFF0000"))
        with self.assertRaises(icon.IconError) as ctx:
            icon.command_check(self.root)
        self.assertIn("ic_launcher_foreground.xml", str(ctx.exception))

    def test_check_fails_on_a_missing_generated_file(self):
        (self.root / "android/app/src/main/res/mipmap-anydpi/ic_launcher_round.xml").unlink()
        with self.assertRaises(icon.IconError):
            icon.command_check(self.root)

    def test_check_fails_on_a_night_launcher_resource(self):
        night = self.root / "android/app/src/main/res/mipmap-night-anydpi"
        night.mkdir(parents=True)
        (night / "ic_launcher.xml").write_text("<adaptive-icon/>")
        with self.assertRaises(icon.IconError) as ctx:
            icon.command_check(self.root)
        self.assertIn("-night", str(ctx.exception))

    def assert_manifest_rejected(self, old, new, *needles):
        self.manifest.write_text(self.manifest.read_text().replace(old, new))
        with self.assertRaises(icon.IconError) as ctx:
            icon.command_check(self.root)
        for needle in needles:
            self.assertIn(needle, str(ctx.exception))

    def test_check_fails_when_the_manifest_goes_back_to_a_system_icon(self):
        self.assert_manifest_rejected('android:icon="@mipmap/ic_launcher"',
                                      'android:icon="@android:drawable/sym_def_app_icon"', "android:icon")

    def test_check_fails_when_the_manifest_has_no_round_icon(self):
        self.assert_manifest_rejected('android:roundIcon="@mipmap/ic_launcher_round"', "", "android:roundIcon")

    def test_check_fails_when_the_round_icon_is_a_system_icon(self):
        self.assert_manifest_rejected('android:roundIcon="@mipmap/ic_launcher_round"',
                                      'android:roundIcon="@android:drawable/sym_def_app_icon"', "android:roundIcon")

    def test_check_fails_when_the_source_changes_without_a_rebuild(self):
        source = self.root / icon.SOURCE_RELATIVE
        source.write_text(source.read_text().replace("#FBBF24", "#FF0000"))
        with self.assertRaises(icon.IconError):
            icon.command_check(self.root)


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
