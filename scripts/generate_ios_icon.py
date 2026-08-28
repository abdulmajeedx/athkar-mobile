#!/usr/bin/env python3
"""Draws the iOS app icon.

Checked in as source rather than as a lone PNG: an icon nobody can regenerate is an icon nobody can
change. Run it after editing, and commit the PNG it writes.

    python3 scripts/generate_ios_icon.py

The motif is the rub' al-hizb — the two overlapping squares that mark sections of the Qur'an — in
the app's gold on its emerald gradient. It is drawn at 4x and downsampled, because PIL's polygon
outlines are not anti-aliased and the aliasing is glaring at icon sizes.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 1024
SUPERSAMPLE = 4

# The palette is Theme.swift's, so the icon and the app it opens are the same colour.
EMERALD_TOP = (11, 93, 74)
EMERALD_BOTTOM = (8, 59, 49)
GOLD = (229, 193, 88)
GOLD_DEEP = (201, 162, 39)

OUTPUT = Path("ios/Athkar/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")


def square_points(centre: float, radius: float, rotation_degrees: float) -> list[tuple[float, float]]:
    """Corners of a square inscribed in a circle, rotated about its centre."""
    return [
        (
            centre + radius * math.cos(math.radians(rotation_degrees + angle)),
            centre + radius * math.sin(math.radians(rotation_degrees + angle)),
        )
        for angle in (45, 135, 225, 315)
    ]


def draw_icon(size: int) -> Image.Image:
    image = Image.new("RGB", (size, size), EMERALD_TOP)
    draw = ImageDraw.Draw(image)

    # Vertical gradient, one scanline at a time.
    for y in range(size):
        t = y / max(size - 1, 1)
        colour = tuple(
            round(top + (bottom - top) * t)
            for top, bottom in zip(EMERALD_TOP, EMERALD_BOTTOM)
        )
        draw.line([(0, y), (size, y)], fill=colour)

    centre = size / 2
    stroke = max(round(size * 0.028), 1)

    # A thin containing ring gives the motif an edge to sit against at small sizes.
    ring_radius = size * 0.40
    draw.ellipse(
        [centre - ring_radius, centre - ring_radius, centre + ring_radius, centre + ring_radius],
        outline=GOLD_DEEP,
        width=max(round(size * 0.012), 1),
    )

    motif_radius = size * 0.29
    for rotation in (0, 45):
        draw.polygon(square_points(centre, motif_radius, rotation), outline=GOLD, width=stroke)

    dot = size * 0.045
    draw.ellipse([centre - dot, centre - dot, centre + dot, centre + dot], fill=GOLD)

    return image


def main() -> None:
    large = draw_icon(SIZE * SUPERSAMPLE)
    icon = large.resize((SIZE, SIZE), Image.LANCZOS)
    # App Store Connect rejects an icon with an alpha channel, so this stays RGB throughout.
    assert icon.mode == "RGB", icon.mode
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    icon.save(OUTPUT, format="PNG")
    print(f"wrote {OUTPUT} ({icon.size[0]}x{icon.size[1]}, {icon.mode})")


if __name__ == "__main__":
    main()
