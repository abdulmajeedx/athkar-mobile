#!/usr/bin/env python3
"""Draws the Play Store listing assets.

    python3 scripts/generate_store_assets.py

Play wants a 512x512 icon and a 1024x500 feature graphic, neither of which the app itself contains:
the launcher icon is a vector and the feature graphic has no in-app equivalent at all. Both are
built from the same palette and motif as everything else, so the store page and the app it leads to
are recognisably one thing.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from generate_icons import (  # noqa: E402
    GOLD, GOLD_DEEP, NIGHT_BOTTOM, NIGHT_TOP, draw_ios, square_points_at,
)

OUT = Path("store")
SUPERSAMPLE = 3


def gradient(size: tuple[int, int]) -> Image.Image:
    width, height = size
    image = Image.new("RGB", size, NIGHT_TOP)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(height - 1, 1)
        draw.line(
            [(0, y), (width, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(NIGHT_TOP, NIGHT_BOTTOM)),
        )
    return image


def star(draw: ImageDraw.ImageDraw, cx: float, cy: float, radius: float, width: int, colour) -> None:
    for rotation in (0, 45):
        draw.polygon(square_points_at(cx, cy, radius, rotation), outline=colour, width=width)


def write_icon() -> None:
    # The launcher icon at the size Play asks for, drawn rather than upscaled.
    icon = draw_ios(512 * SUPERSAMPLE).resize((512, 512), Image.LANCZOS)
    icon.save(OUT / "play-icon-512.png", format="PNG")
    print(f"wrote {OUT}/play-icon-512.png (512x512, RGB)")


def write_feature_graphic() -> None:
    width, height = 1024 * SUPERSAMPLE, 500 * SUPERSAMPLE
    image = gradient((width, height))
    draw = ImageDraw.Draw(image)

    # The lattice, dim, across the whole banner: the same geometry the app is patterned with.
    spacing = height * 0.42
    radius = spacing * 0.30
    stroke = max(round(height * 0.004), 1)
    row = 0
    y = -spacing
    while y < height + spacing:
        offset = 0 if row % 2 == 0 else spacing / 2
        x = -spacing + offset
        while x < width + spacing:
            star(draw, x, y, radius, stroke, GOLD_DEEP)
            x += spacing
        y += spacing
        row += 1

    # The mark, off to one side, leaving the other for the title Play renders over the graphic.
    mark_x = width * 0.80
    mark_y = height / 2
    mark_radius = height * 0.30
    draw.ellipse(
        [mark_x - mark_radius, mark_y - mark_radius, mark_x + mark_radius, mark_y + mark_radius],
        fill=NIGHT_BOTTOM,
    )
    draw.ellipse(
        [mark_x - mark_radius, mark_y - mark_radius, mark_x + mark_radius, mark_y + mark_radius],
        outline=GOLD_DEEP,
        width=max(round(height * 0.010), 1),
    )
    star(draw, mark_x, mark_y, mark_radius * 0.72, max(round(height * 0.020), 1), GOLD)
    dot = mark_radius * 0.115
    draw.ellipse([mark_x - dot, mark_y - dot, mark_x + dot, mark_y + dot], fill=GOLD)

    image.resize((1024, 500), Image.LANCZOS).save(OUT / "play-feature-graphic.png", format="PNG")
    print(f"wrote {OUT}/play-feature-graphic.png (1024x500, RGB)")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    write_icon()
    write_feature_graphic()
