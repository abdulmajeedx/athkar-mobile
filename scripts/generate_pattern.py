#!/usr/bin/env python3
"""Draws the seamless geometric pattern the app layers over its coloured surfaces.

    python3 scripts/generate_pattern.py

A photograph would fight the text on top of it and date the app the moment tastes move. A girih
tessellation — eight-pointed stars on an offset grid, the pattern that covers half the tilework in
the Islamic world — carries the same weight, tiles forever without a seam, weighs a few kilobytes,
and takes whatever colour the screen it lands on needs.

The tile is white on transparency so it can be tinted at runtime; the app never draws it as-is.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

TILE = 512
SUPERSAMPLE = 4
OUTPUT = Path("android/designsystem/src/main/res/drawable-nodpi/pattern_girih.png")

STAR_RADIUS = 0.30      # of the tile
STROKE = 0.016          # of the tile
LATTICE = 0.5           # second star offset, in tiles — what makes the grid seamless


def star_points(cx: float, cy: float, radius: float) -> list[tuple[float, float]]:
    """An eight-pointed star: the union outline of two squares at 45 degrees to each other."""
    valley = radius * math.sqrt(2 - math.sqrt(2))
    return [
        (
            cx + (radius if step % 2 == 0 else valley) * math.cos(math.radians(step * 22.5)),
            cy + (radius if step % 2 == 0 else valley) * math.sin(math.radians(step * 22.5)),
        )
        for step in range(16)
    ]


def draw(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(image)
    radius = size * STAR_RADIUS
    stroke = max(round(size * STROKE), 1)
    offset = size * LATTICE

    # Stars at the corners and at the centre. Drawing the corner ones four times, once per corner,
    # is what makes the tile seamless: each contributes the quarter that falls inside.
    centres = [(0, 0), (size, 0), (0, size), (size, size), (offset, offset)]
    for cx, cy in centres:
        draw.polygon(star_points(cx, cy, radius), outline=(255, 255, 255, 255), width=stroke)

    # The lattice between them: the interlacing lines that turn separate stars into one pattern.
    for cx, cy in centres:
        for angle in (45, 135, 225, 315):
            radians = math.radians(angle)
            # From the star's own point outward. Starting at the valley radius instead puts the
            # line's first stretch inside the star, where it crosses the outline and leaves a stub.
            draw.line(
                [
                    (cx + radius * math.cos(radians), cy + radius * math.sin(radians)),
                    (cx + offset * 0.98 * math.cos(radians), cy + offset * 0.98 * math.sin(radians)),
                ],
                fill=(255, 255, 255, 255),
                width=stroke,
            )
    return image


def main() -> None:
    # Drawn large and downsampled: PIL does not anti-alias outlines, and the aliasing survives
    # tiling as a visible grid of hard edges.
    large = draw(TILE * SUPERSAMPLE)
    tile = large.resize((TILE, TILE), Image.LANCZOS)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    tile.save(OUTPUT, format="PNG", optimize=True)
    print(f"wrote {OUTPUT} ({tile.size[0]}x{tile.size[1]}, {OUTPUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
