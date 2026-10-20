"""Tests of scripts/build-app-icon.py. Run: python3 -I scripts/tests/test-build-app-icon.py"""
import ast
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
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


def fake_render(svg, width, height):
    """Stands in for Chrome in tests that do not need real pixels: an opaque black image."""
    return icon.encode_png(width, height, 4, bytes([0, 0, 0, 255]) * (width * height))


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else (b if pb <= pc else c)


def png_with_filters(width, rows, channels, filter_types):
    """A PNG whose scanlines use the given filter types (an independent encoder, to test decoding)."""
    raw, previous = b"", bytes(len(rows[0]))
    for row, filter_type in zip(rows, filter_types):
        out = bytearray()
        for i, value in enumerate(row):
            a = row[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            prediction = (0, a, b, (a + b) // 2, paeth(a, b, c))[filter_type]
            out.append((value - prediction) & 255)
        raw += bytes([filter_type]) + bytes(out)
        previous = row

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    color_type = {3: 2, 4: 6}[channels]
    header = struct.pack(">IIBBBBB", width, len(rows), 8, color_type, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


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
        icon.command_build(self.root, render=fake_render)

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


class PngCodec(unittest.TestCase):
    def pixels(self, width, height, channels):
        return bytes((x * 7 + y * 13 + c * 31) % 256 for y in range(height) for x in range(width) for c in range(channels))

    def test_round_trip_rgb_and_rgba(self):
        for channels in (3, 4):
            with self.subTest(channels):
                data = self.pixels(9, 5, channels)
                self.assertEqual((9, 5, channels, data), icon.decode_png(icon.encode_png(9, 5, channels, data)))

    def test_encoding_is_deterministic(self):
        data = self.pixels(16, 16, 3)
        self.assertEqual(icon.encode_png(16, 16, 3, data), icon.encode_png(16, 16, 3, data))

    def test_decodes_every_filter_type(self):
        for channels in (3, 4):
            rows = [bytes((x * 37 + y * 11 + 5) % 256 for x in range(6 * channels)) for y in range(5)]
            for filter_type in range(5):
                with self.subTest(channels=channels, filter=filter_type):
                    data = png_with_filters(6, rows, channels, [filter_type] * 5)
                    self.assertEqual((6, 5, channels, b"".join(rows)), icon.decode_png(data))

    def test_decodes_a_mix_of_filters_in_one_image(self):
        rows = [bytes((x * 53 + y * 17) % 256 for x in range(12)) for y in range(5)]
        data = png_with_filters(4, rows, 3, [0, 1, 2, 3, 4])
        self.assertEqual((4, 5, 3, b"".join(rows)), icon.decode_png(data))

    def test_rejects_what_it_does_not_understand(self):
        with self.assertRaises(icon.IconError):
            icon.decode_png(b"not a png")
        interlaced = bytearray(icon.encode_png(2, 2, 3, bytes(12)))
        interlaced[28] = 1  # IHDR interlace byte
        interlaced[29:33] = struct.pack(">I", zlib.crc32(bytes(interlaced[12:29])))
        with self.assertRaises(icon.IconError):
            icon.decode_png(bytes(interlaced))

    def test_header_reader_reports_size_and_colour_type(self):
        self.assertEqual((7, 3, 2), icon.png_header(icon.encode_png(7, 3, 3, bytes(63))))
        self.assertEqual((7, 3, 6), icon.png_header(icon.encode_png(7, 3, 4, bytes(84))))
        with self.assertRaises(icon.IconError):
            icon.png_header(b"GIF89a")

    def test_dropping_alpha_needs_an_opaque_image(self):
        opaque = icon.encode_png(2, 1, 4, bytes([1, 2, 3, 255, 4, 5, 6, 255]))
        self.assertEqual((2, 1, 3, bytes([1, 2, 3, 4, 5, 6])), icon.decode_png(icon.to_rgb(opaque)))
        with self.assertRaises(icon.IconError):
            icon.to_rgb(icon.encode_png(1, 1, 4, bytes([1, 2, 3, 200])))

    def test_alpha_can_be_added_back(self):
        rgb = icon.encode_png(2, 1, 3, bytes([1, 2, 3, 4, 5, 6]))
        self.assertEqual((2, 1, 4, bytes([1, 2, 3, 255, 4, 5, 6, 255])), icon.decode_png(icon.to_rgba(rgb)))


class SafeZone(unittest.TestCase):
    def image(self, size, white=(), dim=()):
        pixels = bytearray(size * size * 3)
        for x, y in white:
            pixels[(y * size + x) * 3:(y * size + x) * 3 + 3] = b"\xff\xff\xff"
        for x, y in dim:
            pixels[(y * size + x) * 3:(y * size + x) * 3 + 3] = bytes([20, 20, 20])
        return icon.encode_png(size, size, 3, bytes(pixels))

    def test_content_inside_the_circle_passes(self):
        icon.check_safe_zone_pixels(self.image(40, white=[(20, 20), (25, 20)]), radius=10)

    def test_one_bright_pixel_outside_fails(self):
        with self.assertRaises(icon.IconError) as ctx:
            icon.check_safe_zone_pixels(self.image(40, white=[(20, 20), (39, 0)]), radius=10)
        self.assertIn("safe zone", str(ctx.exception))

    def test_faint_antialiasing_outside_is_tolerated(self):
        icon.check_safe_zone_pixels(self.image(40, white=[(20, 20)], dim=[(32, 20)]), radius=10)

    @unittest.skipUnless(os.path.exists(icon.DEFAULT_CHROME) or os.environ.get("CHROME"), "Google Chrome not installed")
    def test_the_committed_foreground_fits_and_an_oversized_one_does_not(self):
        render = icon.chrome_renderer(icon.find_chrome())
        text = (ROOT / icon.SOURCE_RELATIVE).read_text()
        icon.check_safe_zone(parse(text), render)
        with self.assertRaises(icon.IconError):
            icon.check_safe_zone(parse(text.replace("scale(0.94)", "scale(1.2)")), render)


class Manifest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        (self.root / "design" / "app-icon").mkdir(parents=True)
        shutil.copy(ROOT / icon.SOURCE_RELATIVE, self.root / icon.SOURCE_RELATIVE)
        self.asset = Path("design/app-icon/asset.png")
        (self.root / self.asset).write_bytes(b"first")
        icon.write_manifest(self.root, {self.asset: b"first"})

    def test_a_fresh_manifest_verifies(self):
        icon.verify_manifest(self.root, [self.asset])

    def test_the_manifest_is_stable_text(self):
        text = (self.root / icon.MANIFEST_JSON_RELATIVE).read_text()
        self.assertEqual(json.dumps(json.loads(text), indent=2, sort_keys=True) + "\n", text)

    def test_a_changed_source_fails(self):
        source = self.root / icon.SOURCE_RELATIVE
        source.write_text(source.read_text().replace("#FBBF24", "#FF0000"))
        with self.assertRaises(icon.IconError) as ctx:
            icon.verify_manifest(self.root, [self.asset])
        self.assertIn("source", str(ctx.exception))

    def test_a_changed_script_fails(self):
        original = icon.script_digest
        icon.script_digest = lambda: "0" * 64
        self.addCleanup(setattr, icon, "script_digest", original)
        with self.assertRaises(icon.IconError) as ctx:
            icon.verify_manifest(self.root, [self.asset])
        self.assertIn("script", str(ctx.exception))

    def test_a_hand_edited_asset_fails(self):
        (self.root / self.asset).write_bytes(b"edited")
        with self.assertRaises(icon.IconError) as ctx:
            icon.verify_manifest(self.root, [self.asset])
        self.assertIn("asset.png", str(ctx.exception))

    def test_a_missing_or_unlisted_asset_fails(self):
        (self.root / self.asset).unlink()
        with self.assertRaises(icon.IconError):
            icon.verify_manifest(self.root, [self.asset])
        with self.assertRaises(icon.IconError):
            icon.verify_manifest(self.root, [self.asset, Path("design/app-icon/other.png")])

    def test_a_missing_manifest_fails(self):
        (self.root / icon.MANIFEST_JSON_RELATIVE).unlink()
        with self.assertRaises(icon.IconError):
            icon.verify_manifest(self.root, [self.asset])


class Chrome(unittest.TestCase):
    def test_a_missing_chrome_stops_the_build_with_a_clear_message(self):
        result = subprocess.run([sys.executable, "-I", str(ROOT / "scripts" / "build-app-icon.py"), "build"],
                                env={**os.environ, "CHROME": "/nonexistent/chrome"}, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Chrome", result.stderr)
        self.assertIn("CHROME", result.stderr)

    @unittest.skipUnless(os.path.exists(icon.DEFAULT_CHROME) or os.environ.get("CHROME"), "Google Chrome not installed")
    def test_chrome_renders_exact_pixels(self):
        render = icon.chrome_renderer(icon.find_chrome())
        svg = icon.svg_document(64, 32, "0 0 2 1", '<rect width="1" height="1" fill="#FF0000"/>'
                                '<rect x="1" width="1" height="1" fill="#0000FF"/>')
        width, height, channels, pixels = icon.decode_png(render(svg, 64, 32))
        self.assertEqual((64, 32), (width, height))
        self.assertEqual(bytes([255, 0, 0]), pixels[:3])
        self.assertEqual(bytes([0, 0, 255]), pixels[(63) * channels:(63) * channels + 3])


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
