#!/usr/bin/env python3
"""Draws the Play Store listing assets.

    python3 scripts/generate_store_assets.py

Play wants a 512x512 icon and a 1024x500 feature graphic, neither of which the app itself contains:
the launcher icon is a vector and the feature graphic has no in-app equivalent at all.

Both are drawn from the launcher's own marks and palette — the white crescent, the gold tasbih, the
emerald ground — so the icon on the store page is the icon that appears on the phone afterwards.
They used to be a gold girih star on navy, which is what the launcher was before it was redrawn;
a listing that advertises one mark and installs another is two apps as far as anyone looking can
tell.

Colours come from res/drawable/ic_launcher_background.xml and ic_launcher_foreground.xml. If those
change, change these, and re-run.
"""

from __future__ import annotations

from pathlib import Path

import math

from PIL import Image, ImageDraw

OUT = Path("store")

# res/drawable/ic_launcher_background.xml
EMERALD_TOP = (0x00, 0x89, 0x7B)
EMERALD_BOTTOM = (0x00, 0x59, 0x4E)
# res/drawable/ic_launcher_foreground.xml
CRESCENT = (0xFF, 0xFF, 0xFF)
GOLD = (0xD8, 0xB5, 0x62)

# Drawn large and shrunk, because PIL has no antialiasing of its own and a crescent is all curve.
SUPERSAMPLE = 4


def gradient(size: tuple[int, int]) -> Image.Image:
    width, height = size
    image = Image.new("RGB", size, EMERALD_TOP)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(height - 1, 1)
        draw.line(
            [(0, y), (width, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(EMERALD_TOP, EMERALD_BOTTOM)),
        )
    return image


def lattice(image: Image.Image, spacing: float, alpha: int) -> None:
    """
    The girih tessellation the app's own surfaces carry, laid faintly over the ground.

    Drawn on its own layer and composited, because PIL cannot stroke translucently: painted
    directly it would be a grid of hard gold lines rather than a texture under the light.
    """
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    width, height = image.size
    radius = spacing * 0.30
    stroke = max(round(height * 0.0035), 1)
    row = 0
    y = -spacing
    while y < height + spacing:
        offset = 0.0 if row % 2 == 0 else spacing / 2
        x = -spacing + offset
        while x < width + spacing:
            for rotation in (0, 45):
                points = [
                    (
                        x + radius * math.cos(math.radians(rotation + step * 90)),
                        y + radius * math.sin(math.radians(rotation + step * 90)),
                    )
                    for step in range(4)
                ]
                draw.polygon(points, outline=GOLD + (alpha,), width=stroke)
            x += spacing
        y += spacing
        row += 1
    image.paste(Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB"), (0, 0))


def crescent(draw: ImageDraw.ImageDraw, scale, ground) -> None:
    """A disc with a smaller disc bitten out of it, matching the launcher's two arcs."""
    cx, cy, r = scale(54.0, 47.0, 15.82)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=CRESCENT)
    bx, by, br = scale(58.6, 44.6, 13.29)
    draw.ellipse([bx - br, by - br, bx + br, by + br], fill=ground)


def tasbih(draw: ImageDraw.ImageDraw, scale) -> None:
    """
    The prayer beads under the crescent.

    Coordinates lifted from ic_launcher_foreground.xml rather than eyeballed: one bead at the
    clasp, two pairs falling away from it, one hanging at the bottom, and the cord between. Drawn
    by eye it reads as a scatter of dots, which is what it was.
    """
    for x, y, r in (
        (54.00, 67.50, 2.11),
        (49.25, 69.93, 1.79),
        (58.75, 69.93, 1.79),
        (45.04, 73.51, 1.48),
        (62.96, 73.51, 1.48),
        (54.00, 76.25, 1.37),
    ):
        px, py, pr = scale(x, y, r)
        draw.ellipse([px - pr, py - pr, px + pr, py + pr], fill=GOLD)

    x0, y0, _ = scale(53.16, 69.61, 0)
    x1, y1, _ = scale(54.85, 75.31, 0)
    draw.rounded_rectangle([x0, y0, x1, y1], radius=(x1 - x0) / 2, fill=GOLD)


# The drawn marks span y 31.18 (top of the crescent) to 77.62 (bottom of the last bead) in the
# vector's 108-unit viewport. Scaling by the viewport instead leaves a third of every canvas empty,
# because two thirds of the viewport is the launcher's safe margin.
MARK_TOP = 31.18
MARK_BOTTOM = 77.62
MARK_HEIGHT = MARK_BOTTOM - MARK_TOP
MARK_MID_Y = (MARK_TOP + MARK_BOTTOM) / 2


def mark(draw: ImageDraw.ImageDraw, cx: float, cy: float, height: float, ground) -> None:
    """Draws the launcher mark centred on (cx, cy), `height` tall from crescent to last bead."""
    unit = height / MARK_HEIGHT

    def scale(x: float, y: float, r: float):
        return (cx + (x - 54.0) * unit, cy + (y - MARK_MID_Y) * unit, r * unit)

    crescent(draw, scale, ground)
    tasbih(draw, scale)


def write_icon() -> None:
    side = 512 * SUPERSAMPLE
    image = gradient((side, side))
    draw = ImageDraw.Draw(image)
    mark(draw, side / 2, side / 2, side * 0.66, EMERALD_BOTTOM)
    image.resize((512, 512), Image.LANCZOS).save(OUT / "play-icon-512.png", format="PNG")
    print(f"wrote {OUT}/play-icon-512.png (512x512, RGB)")


def write_feature_graphic() -> None:
    width, height = 1024 * SUPERSAMPLE, 500 * SUPERSAMPLE
    image = gradient((width, height))
    lattice(image, spacing=height * 0.42, alpha=34)
    draw = ImageDraw.Draw(image)

    # Off to one side, leaving the other clear: Play renders the app name and icon over this
    # graphic on several surfaces, and whatever sits under them is lost.
    mark(draw, width * 0.78, height / 2, height * 0.62, EMERALD_BOTTOM)

    image.resize((1024, 500), Image.LANCZOS).save(OUT / "play-feature-graphic.png", format="PNG")
    print(f"wrote {OUT}/play-feature-graphic.png (1024x500, RGB)")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    write_icon()
    write_feature_graphic()
