#!/usr/bin/env python3
"""Regenerate desktopApp/packaging/icons/animusic/ from the AniMusic master art.

    python3 desktopApp/packaging/make-animusic-icons.py

Needs cairosvg and Pillow (`pip install cairosvg pillow`). Writes AppIcon.png
(Linux, 512 px), AppIcon.ico (Windows, 16–256 px) and AppIcon.icns (macOS,
16–1024 px) from app/src/animusic/art/animusic-icon.svg, the same art the
Android launcher icon is drawn from. It also writes the desktop window logo
(composeResources/drawable/animusic_logo.svg) and the website's icons.

Android shows only the middle 72 x 72 of the 108 x 108 adaptive-icon art (the
rest is room for the launcher's mask), so every icon here is cropped to that
same area: on a computer AniMusic looks exactly as it does on the phone.
"""
import io
import os

import cairosvg
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SVG = os.path.join(ROOT, "app", "src", "animusic", "art", "animusic-icon.svg")
OUT = os.path.join(ROOT, "desktopApp", "packaging", "icons", "animusic")


WINDOW_LOGO = os.path.join(ROOT, "desktopApp", "src", "main", "composeResources", "drawable", "animusic_logo.svg")
WEBSITE = os.path.join(ROOT, "website")


def launcher_svg() -> str:
    """The master art cropped to what an Android launcher shows."""
    with open(SVG, encoding="utf-8") as f:
        svg = f.read()
    for old, new in (
        ('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"'),
        ('<rect x="0" y="0" width="108" height="108" rx="24"/>',
         '<rect x="18" y="18" width="72" height="72" rx="16"/>'),
    ):
        assert old in svg, f"animusic-icon.svg changed shape: {old!r} not found"
        svg = svg.replace(old, new)
    # Drop the master's comments; this file is generated.
    return "\n".join(line for line in svg.splitlines() if "<!--" not in line) + "\n"


LAUNCHER = launcher_svg()


def render(px: int) -> Image.Image:
    png = cairosvg.svg2png(bytestring=LAUNCHER.encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


os.makedirs(OUT, exist_ok=True)
with open(WINDOW_LOGO, "w", encoding="utf-8") as f:
    f.write(LAUNCHER.replace('width="512" height="512"', 'width="1024" height="1024"'))
with open(os.path.join(WEBSITE, "icon.svg"), "w", encoding="utf-8") as f:
    f.write(LAUNCHER)
render(512).save(os.path.join(WEBSITE, "icon-512.png"))
big = render(1024)
render(512).save(os.path.join(OUT, "AppIcon.png"))
big.save(os.path.join(OUT, "AppIcon.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
big.save(os.path.join(OUT, "AppIcon.icns"))
for name in sorted(os.listdir(OUT)):
    print(name, os.path.getsize(os.path.join(OUT, name)))
