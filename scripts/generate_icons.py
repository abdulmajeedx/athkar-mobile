#!/usr/bin/env python3
"""Draws the app icon for both platforms from one description.

    python3 scripts/generate_icons.py

Checked in as source rather than as lone artwork: an icon nobody can regenerate is an icon nobody
can change. One script for both platforms so the two cannot drift apart — editing the geometry below
moves the iOS raster and the Android vector together.

The motif is the rub' al-hizb, the two overlapping squares that mark sections of the Qur'an, in the
app's gold on its emerald gradient. Colours are Theme.swift's, so the icon and the app it opens are
the same colour.

iOS wants a 1024x1024 opaque PNG; Android wants adaptive-icon vector drawables on a 108dp canvas
whose outer ring the launcher mask may crop.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

# --- palette (Theme.swift) ---------------------------------------------------------------------

# SkyPhase.NIGHT from the design system. The icon is the app's own night sky, so the thing on the
# home screen and the thing that opens from it are the same object rather than two designs that
# happen to share a motif.
NIGHT_TOP = (16, 32, 58)
NIGHT_BOTTOM = (7, 12, 26)
GOLD = (229, 193, 88)
GOLD_DEEP = (201, 162, 39)

# --- geometry, as fractions of the design radius -------------------------------------------------
# The design is a circle: everything is placed relative to it, so the same numbers describe an
# iOS square canvas and an Android adaptive icon whose corners get masked away.

RING_RADIUS = 1.00
RING_STROKE = 0.045
MOTIF_RADIUS = 0.725
MOTIF_STROKE = 0.070
CENTRE_DOT = 0.113

IOS_PNG = Path("ios/Athkar/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")
ANDROID_RES = Path("android/app/src/main/res/drawable")

# A status-bar icon is drawn as a mask — only its alpha survives, and the system tints the rest —
# and it is rendered as small as 24dp. The launcher icon's stroked outlines alias into mush at that
# size, so the notification variant is the same motif as a solid silhouette: the outer boundary of
# the two overlapping squares, which is an eight-pointed star.
NOTIFICATION_VIEWPORT = 24.0
NOTIFICATION_RADIUS = 10.5

IOS_SIZE = 1024
IOS_SUPERSAMPLE = 4
# iOS fills the whole square; the design circle spans 80% of the half-width.
IOS_DESIGN_RADIUS = 0.40

# Android's adaptive canvas is 108dp, of which only the central ~66dp is guaranteed visible.
ANDROID_VIEWPORT = 108.0
ANDROID_DESIGN_RADIUS = 29.0


def square_points(centre: float, radius: float, rotation_degrees: float) -> list[tuple[float, float]]:
    """Corners of a square inscribed in a circle, rotated about its centre."""
    return [
        (
            centre + radius * math.cos(math.radians(rotation_degrees + angle)),
            centre + radius * math.sin(math.radians(rotation_degrees + angle)),
        )
        for angle in (45, 135, 225, 315)
    ]


# --- iOS -----------------------------------------------------------------------------------------

def draw_ios(size: int) -> Image.Image:
    image = Image.new("RGB", (size, size), NIGHT_TOP)
    draw = ImageDraw.Draw(image)

    for y in range(size):
        t = y / max(size - 1, 1)
        draw.line(
            [(0, y), (size, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(NIGHT_TOP, NIGHT_BOTTOM)),
        )

    centre = size / 2
    design = size * IOS_DESIGN_RADIUS

    ring = design * RING_RADIUS
    draw.ellipse(
        [centre - ring, centre - ring, centre + ring, centre + ring],
        outline=GOLD_DEEP,
        width=max(round(design * RING_STROKE), 1),
    )

    motif = design * MOTIF_RADIUS
    for rotation in (0, 45):
        draw.polygon(
            square_points(centre, motif, rotation),
            outline=GOLD,
            width=max(round(design * MOTIF_STROKE), 1),
        )

    dot = design * CENTRE_DOT
    draw.ellipse([centre - dot, centre - dot, centre + dot, centre + dot], fill=GOLD)
    return image


def write_ios() -> None:
    # Drawn at 4x and downsampled: PIL's polygon outlines are not anti-aliased, and the aliasing is
    # glaring at icon sizes.
    large = draw_ios(IOS_SIZE * IOS_SUPERSAMPLE)
    icon = large.resize((IOS_SIZE, IOS_SIZE), Image.LANCZOS)
    # App Store Connect rejects an icon with an alpha channel, so this stays RGB throughout.
    assert icon.mode == "RGB", icon.mode
    IOS_PNG.parent.mkdir(parents=True, exist_ok=True)
    icon.save(IOS_PNG, format="PNG")
    print(f"wrote {IOS_PNG} ({icon.size[0]}x{icon.size[1]}, {icon.mode})")


# --- Android ---------------------------------------------------------------------------------------

def hex_colour(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def polygon_path(points: list[tuple[float, float]]) -> str:
    head = f"M{points[0][0]:.2f},{points[0][1]:.2f}"
    rest = "".join(f" L{x:.2f},{y:.2f}" for x, y in points[1:])
    return f"{head}{rest} Z"


def circle_path(centre: float, radius: float) -> str:
    """A full circle as two arcs — SVG and Android have no circle command in path data."""
    return (
        f"M{centre:.2f},{centre - radius:.2f} "
        f"a{radius:.2f},{radius:.2f} 0 1,0 0,{2 * radius:.2f} "
        f"a{radius:.2f},{radius:.2f} 0 1,0 0,{-2 * radius:.2f} Z"
    )


def star_points(centre: float, radius: float) -> list[tuple[float, float]]:
    """Outline of two squares' union: eight points at `radius`, eight valleys between them.

    Where the axis-aligned square's edge (x = radius/sqrt(2)) meets the rotated one's (|x|+|y| =
    radius), the boundary turns inward — that crossing is the valley, at 0.7654 of the radius.
    """
    valley = radius * math.sqrt(2 - math.sqrt(2))
    points = []
    for step in range(16):
        angle = math.radians(step * 22.5)
        r = radius if step % 2 == 0 else valley
        points.append((centre + r * math.cos(angle), centre + r * math.sin(angle)))
    return points


def write_android_notification() -> None:
    centre = NOTIFICATION_VIEWPORT / 2
    path = polygon_path(star_points(centre, NOTIFICATION_RADIUS))

    icon = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate_icons.py — edit the script, not this file.
     The status bar draws this as a mask: only the alpha channel survives, so the fill is a flat
     white silhouette rather than the launcher icon's gold outlines, which disappear at 24dp. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:pathData="{path}"
        android:fillColor="#FFFFFF" />
</vector>
"""
    (ANDROID_RES / "ic_notification_prayer.xml").write_text(icon, encoding="utf-8")
    print(f"wrote {ANDROID_RES}/ic_notification_prayer.xml")


def write_android() -> None:
    centre = ANDROID_VIEWPORT / 2
    design = ANDROID_DESIGN_RADIUS

    background = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate_icons.py — edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108"
                android:startColor="{hex_colour(NIGHT_TOP)}"
                android:endColor="{hex_colour(NIGHT_BOTTOM)}" />
        </aapt:attr>
    </path>
</vector>
"""

    ring = design * RING_RADIUS
    motif = design * MOTIF_RADIUS
    dot = design * CENTRE_DOT
    squares = "\n".join(
        f"""    <path
        android:pathData="{polygon_path(square_points(centre, motif, rotation))}"
        android:strokeColor="{hex_colour(GOLD)}"
        android:strokeWidth="{design * MOTIF_STROKE:.2f}"
        android:strokeLineJoin="miter" />"""
        for rotation in (0, 45)
    )

    foreground = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate_icons.py — edit the script, not this file.
     Everything sits inside a {design:.0f}dp radius: an adaptive icon's corners are cropped by
     whatever mask the launcher applies, and only the centre is guaranteed to survive. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{circle_path(centre, ring)}"
        android:strokeColor="{hex_colour(GOLD_DEEP)}"
        android:strokeWidth="{design * RING_STROKE:.2f}" />
{squares}
    <path
        android:pathData="{circle_path(centre, dot)}"
        android:fillColor="{hex_colour(GOLD)}" />
</vector>
"""

    ANDROID_RES.mkdir(parents=True, exist_ok=True)
    (ANDROID_RES / "ic_launcher_background.xml").write_text(background, encoding="utf-8")
    (ANDROID_RES / "ic_launcher_foreground.xml").write_text(foreground, encoding="utf-8")
    print(f"wrote {ANDROID_RES}/ic_launcher_background.xml and ic_launcher_foreground.xml")


if __name__ == "__main__":
    write_ios()
    write_android()
    write_android_notification()
