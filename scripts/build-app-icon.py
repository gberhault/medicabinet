#!/usr/bin/env python3
"""Generate every MediCabinet app-icon asset from one source SVG (specs/app-icon/design.md).

  python3 -I scripts/build-app-icon.py check [--root DIR]    validate the source and the committed assets
  python3 -I scripts/build-app-icon.py build [--root DIR]    regenerate the assets (needs Google Chrome for PNGs)

The source (design/app-icon/medicabinet-icon.svg) may only use a small SVG subset, which keeps the artwork
free of fonts, embedded images and anything else we do not own (requirement R1). Standard library only.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SOURCE_RELATIVE = Path("design/app-icon/medicabinet-icon.svg")
SVG_NS = "http://www.w3.org/2000/svg"
REQUIRED_PARTS = {"background": "rect", "foreground": "g", "wordmark": "g"}

SHAPE_ATTRS = {"id", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin"}
ALLOWED = {  # tag -> attributes it may carry
    "svg": {"xmlns", "viewBox", "width", "height"},
    "defs": set(),
    "linearGradient": {"id", "x1", "y1", "x2", "y2"},
    "stop": {"offset", "stop-color"},
    "g": SHAPE_ATTRS | {"transform"},
    "rect": SHAPE_ATTRS | {"x", "y", "width", "height"},
    "path": SHAPE_ATTRS | {"d"},
}
NUMBER = r"-?\d+(?:\.\d+)?"
TRANSFORM_OP = re.compile(
    rf"(?:translate\({NUMBER} {NUMBER}\)|scale\({NUMBER}\)|rotate\({NUMBER} {NUMBER} {NUMBER}\))"
)
PATH_DATA = re.compile(r"^[MmLlHhVvAaZz0-9.,\- ]{1,400}$")
HEX_PAINT = re.compile(r"^#[0-9A-Fa-f]{6}$")
LINECAPS, LINEJOINS = {"butt", "round", "square"}, {"miter", "round", "bevel"}


class IconError(Exception):
    """The source or a generated asset breaks a rule; the message names the culprit."""


def local(tag):
    return tag.split("}", 1)[1] if tag.startswith("{") else tag


def load_source(path):
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise IconError(f"cannot read the source {path}: {error}") from error


def validate_transform(value, where):
    rest = value.strip()
    while rest:
        match = TRANSFORM_OP.match(rest)
        if not match:
            raise IconError(f"{where}: unsupported transform {value!r} (use translate(x y), scale(s), rotate(a cx cy))")
        rest = rest[match.end():].lstrip()


def validate(root):
    """Reject anything outside the allowed subset (R1); the message names the element and attribute."""
    if local(root.tag) != "svg" or root.get("viewBox") is None:
        raise IconError("the source root must be an <svg> with a viewBox")
    gradients = {e.get("id") for e in root.iter() if local(e.tag) == "linearGradient"}
    for element in root.iter():
        tag = local(element.tag)
        if tag not in ALLOWED:
            raise IconError(f"<{tag}> is not allowed in the icon source (allowed: {', '.join(sorted(ALLOWED))})")
        if element.text and element.text.strip():
            raise IconError(f"<{tag}> must not contain text")
        where = f"<{tag}{' id=' + element.get('id') if element.get('id') else ''}>"
        for name, value in element.attrib.items():
            if name.startswith("{") or name not in ALLOWED[tag]:
                raise IconError(f"{where}: attribute {name!r} is not allowed")
            if name in ("fill", "stroke", "stop-color"):
                validate_paint(value, gradients, where, name)
            elif name == "transform":
                validate_transform(value, where)
            elif name == "d" and not PATH_DATA.match(value):
                raise IconError(f"{where}: unsupported path data {value[:40]!r}")
            elif name == "stroke-linecap" and value not in LINECAPS:
                raise IconError(f"{where}: bad stroke-linecap {value!r}")
            elif name == "stroke-linejoin" and value not in LINEJOINS:
                raise IconError(f"{where}: bad stroke-linejoin {value!r}")
    for part, tag in REQUIRED_PARTS.items():
        found = [e for e in root.iter() if e.get("id") == part and local(e.tag) == tag]
        if len(found) != 1:
            raise IconError(f"the source must contain exactly one <{tag} id={part!r}>")


def validate_paint(value, gradients, where, name):
    if value == "none" or HEX_PAINT.match(value):
        return
    match = re.fullmatch(r"url\(#([A-Za-z0-9_-]+)\)", value)
    if match:
        if match.group(1) not in gradients:
            raise IconError(f"{where}: {name} refers to the unknown gradient {match.group(1)!r}")
        return
    raise IconError(f"{where}: unsupported paint {value!r} for {name} (use #RRGGBB, none or url(#gradient))")


def command_check(root_dir):
    validate(load_source(root_dir / SOURCE_RELATIVE))
    print("ok   source uses only the allowed SVG subset")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["check"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args(argv)
    try:
        {"check": command_check}[args.command](args.root)
    except IconError as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
