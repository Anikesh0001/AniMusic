#!/usr/bin/env python3
"""Regenerate desktopApp/packaging/icons/animusic/ from the AniMusic master art.

    python3 desktopApp/packaging/make-animusic-icons.py

Needs cairosvg and Pillow (`pip install cairosvg pillow`). Writes AppIcon.png
(Linux, 512 px), AppIcon.ico (Windows, 16–256 px) and AppIcon.icns (macOS,
16–1024 px) from app/src/animusic/art/animusic-icon.svg, the same art the
Android launcher icon is drawn from.
"""
import io
import os

import cairosvg
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SVG = os.path.join(ROOT, "app", "src", "animusic", "art", "animusic-icon.svg")
OUT = os.path.join(ROOT, "desktopApp", "packaging", "icons", "animusic")


def render(px: int) -> Image.Image:
    png = cairosvg.svg2png(url=SVG, output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


os.makedirs(OUT, exist_ok=True)
big = render(1024)
render(512).save(os.path.join(OUT, "AppIcon.png"))
big.save(os.path.join(OUT, "AppIcon.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
big.save(os.path.join(OUT, "AppIcon.icns"))
for name in sorted(os.listdir(OUT)):
    print(name, os.path.getsize(os.path.join(OUT, name)))
