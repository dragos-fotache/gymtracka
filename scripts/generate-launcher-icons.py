#!/usr/bin/env python3
"""Generate Android launcher assets from app/artwork/gt-icon.svg.

Requires rsvg-convert (librsvg) and Pillow. Run from any working directory.
"""

from io import BytesIO
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/artwork/gt-icon.svg"
RES = ROOT / "app/src/main/res"
SVG = {"svg": "http://www.w3.org/2000/svg"}
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(size):
    result = subprocess.run(
        ["rsvg-convert", "--width", str(size), "--height", str(size), str(SOURCE)],
        check=True,
        capture_output=True,
    )
    return Image.open(BytesIO(result.stdout)).convert("RGBA")


def main():
    svg = ET.parse(SOURCE).getroot()
    x, y, width, height = map(float, svg.attrib["viewBox"].split())
    if x != 0 or y != 0 or width != height:
        raise ValueError("The launcher source must have a square viewBox starting at 0,0")

    styles = dict(re.findall(
        r"\.([\w-]+)\s*\{\s*fill:\s*(#[\da-fA-F]{6})\s*;?\s*\}",
        svg.find("svg:defs/svg:style", SVG).text,
    ))
    background = svg.find("svg:g[@id='Layer_2']/svg:path", SVG)
    background_color = styles[background.attrib["class"]]
    paths = svg.findall("svg:g[@id='Layer_1']/svg:path", SVG)
    # Ignore the source's empty, move-only paths.
    paths = [path for path in paths if re.search(r"[LlHhVvCcSsQqTtAaZz]", path.attrib["d"])]

    header = '<?xml version="1.0" encoding="utf-8"?>\n'
    vector = (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
    )
    origin = '<!-- Generated from app/artwork/gt-icon.svg by scripts/generate-launcher-icons.py. -->\n'
    (RES / "drawable/ic_launcher_background.xml").write_text(
        header + origin + vector
        + '    <path\n'
        + f'        android:fillColor="{background_color}"\n'
        + '        android:pathData="M0,0h108v108h-108z" />\n'
        + '</vector>\n',
        encoding="utf-8",
    )

    # Fit the original square into 70dp within the central 72dp viewport of a
    # 108dp adaptive layer. The complete mark fits inside the 66dp safe circle.
    scale = f"{70 / width:.10f}"
    foreground = header + origin + vector
    foreground += (
        '    <group\n'
        f'        android:scaleX="{scale}"\n'
        f'        android:scaleY="{scale}"\n'
        '        android:translateX="19"\n'
        '        android:translateY="19">\n'
    )
    for path in paths:
        foreground += (
            '        <path\n'
            f'            android:fillColor="{styles[path.attrib["class"]]}"\n'
            f'            android:pathData="{path.attrib["d"]}" />\n'
        )
    foreground += '    </group>\n</vector>\n'
    (RES / "drawable/ic_launcher_foreground.xml").write_text(foreground, encoding="utf-8")

    adaptive = (
        header
        + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        + '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        + '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        + '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
        + '</adaptive-icon>\n'
    )
    for name in ("ic_launcher", "ic_launcher_round"):
        (RES / f"mipmap-anydpi-v26/{name}.xml").write_text(adaptive, encoding="utf-8")

    for density, size in DENSITIES.items():
        # Supersample the SVG and circular alpha mask for clean small-icon edges.
        large_size = size * 4
        square = render(large_size)
        circle = square.copy()
        mask = Image.new("L", square.size)
        ImageDraw.Draw(mask).ellipse((0, 0, large_size - 1, large_size - 1), fill=255)
        circle.putalpha(mask)
        for name, icon in (("ic_launcher", square), ("ic_launcher_round", circle)):
            icon.resize((size, size), Image.Resampling.LANCZOS).save(
                RES / f"mipmap-{density}/{name}.webp",
                lossless=True,
                exact=True,
                method=6,
            )

    render(512).convert("RGB").save(ROOT / "app/artwork/ic_launcher-playstore.png")
    print("Generated adaptive/themed icons, 10 legacy WebP icons, and the 512px Play Store PNG.")


if __name__ == "__main__":
    main()
