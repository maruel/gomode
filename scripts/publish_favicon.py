#!/usr/bin/env python3
"""Publish the Go Mode launcher icon as a website's public/favicon.svg.

The Android launcher foreground (ic_launcher.xml) is the source. The favicon
keeps its brain and voice-wave strokes and their gradients on a light rounded
tile. It drops the disc's shadow and rim, crops to the drawing, and thickens
the strokes so the mark stays legible at 16 and 32 pixels. Sites use the same
file as their header logo.
"""

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "android/gomode/src/main/res/drawable/ic_launcher.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
# The 108-unit viewport square that holds the brain and wave with a margin.
VIEW_BOX = (13, 12, 82, 82)
TILE_RADIUS = 18
TILE_COLOR = "#f4f8fb"  # gomode.dev README Brand table: paper.
STROKE_SCALE = 1.4


def color(value: str) -> tuple[str, float]:
    """Converts Android #AARRGGBB or #RRGGBB to an SVG color and opacity."""
    digits = value.removeprefix("#")
    if len(digits) == 8:
        return f"#{digits[2:].lower()}", round(int(digits[:2], 16) / 255, 3)
    if len(digits) == 6:
        return f"#{digits.lower()}", 1.0
    raise ValueError(f"unsupported color {value}")


def gradient(element: ET.Element, gradient_id: str) -> str:
    stops = []
    for item in element.findall("item"):
        stop_color, opacity = color(item.get(ANDROID + "color", ""))
        alpha = "" if opacity == 1 else f' stop-opacity="{opacity}"'
        stops.append(f'<stop offset="{item.get(ANDROID + "offset")}" stop-color="{stop_color}"{alpha}/>')
    if element.get(ANDROID + "type") != "linear":
        raise ValueError(f"{gradient_id}: the favicon strokes must use linear gradients")
    points = " ".join(
        f'{name}="{element.get(ANDROID + attribute)}"'
        for name, attribute in (("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY"))
    )
    attributes = f'id="{gradient_id}" gradientUnits="userSpaceOnUse" {points}'
    return f"<linearGradient {attributes}>{''.join(stops)}</linearGradient>"


def stroke(path: ET.Element, gradient_id: str, definitions: list[str]) -> str:
    for attribute in path.findall(AAPT + "attr"):
        if attribute.get("name") == "android:strokeColor":
            gradient_element = attribute.find("gradient")
            if gradient_element is None:
                raise ValueError(f"{gradient_id}: strokeColor has no gradient")
            definitions.append(gradient(gradient_element, gradient_id))
            return f"url(#{gradient_id})"
    value = path.get(ANDROID + "strokeColor")
    if value is None:
        raise ValueError(f"{gradient_id}: the favicon keeps only stroked paths")
    return color(value)[0]


def favicon(source: pathlib.Path) -> str:
    # The clipped group holds the brain and wave; the paths before it draw the
    # disc's shadow, fill, and rim, which the tile replaces.
    groups = ET.parse(source).getroot().findall("group")
    if len(groups) != 1:
        raise ValueError(f"{source.name} must hold exactly one drawing group")
    definitions: list[str] = []
    paths = []
    for index, path in enumerate(groups[0].findall("path"), start=1):
        paint = stroke(path, f"s{index}", definitions)
        width = float(path.get(ANDROID + "strokeWidth", "1")) * STROKE_SCALE
        paths.append(
            f'<path d="{path.get(ANDROID + "pathData")}" fill="none" stroke="{paint}" stroke-width="{width:.3f}"'
            f' stroke-linecap="{path.get(ANDROID + "strokeLineCap", "butt")}"'
            f' stroke-linejoin="{path.get(ANDROID + "strokeLineJoin", "miter")}"/>'
        )
    x, y, size, _ = VIEW_BOX
    tile = f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{TILE_RADIUS}" fill="{TILE_COLOR}"/>'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{" ".join(map(str, VIEW_BOX))}">'
        f"<defs>{''.join(definitions)}</defs>{tile}{''.join(paths)}</svg>\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=pathlib.Path, help="website checkout, such as ../gomode.dev")
    site = parser.parse_args().site.resolve()
    destination = site / "public/favicon.svg"
    try:
        content = favicon(SOURCE)
        destination.write_text(content, encoding="utf-8")
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"publish_favicon: {error}", file=sys.stderr)
        return 1
    print(f"Published {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
