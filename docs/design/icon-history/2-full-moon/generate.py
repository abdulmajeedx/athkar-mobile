#!/usr/bin/env python3
# ---------------------------------------------------------------------------------------------
# Historical copy, kept for reference: the generator as it stood at commit b6a2d14, when the icon
# was the full-moon concept. Do not run it from here — it resolves the repository root from its
# own location, and from this folder that root is wrong. To rebuild this icon, copy the file back
# to scripts/icon/generate.py on a scratch branch and run it there.
# ---------------------------------------------------------------------------------------------
"""
Draws the app icon — the crescent completed by dhikr — and writes every copy of it.

The crescent is the app's own. The gold tasbih runs along the arc the crescent is missing, so the
beads finish the full moon it belongs to; the larger bead at the head of the string is the imam
bead a real tasbih carries. Behind it, a faint rub el hizb replaces the plain ring.

Every copy is written from the same numbers, so the launcher, the store listing and iOS cannot
drift apart:

  android/app/src/main/res/drawable/ic_launcher_{foreground,background}.xml
  store/play-icon-512.png                                   (Play wants 512², alpha allowed)
  ios/Athkar/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
                                                            (App Store rejects an alpha channel)

    pip install cairosvg pillow
    python3 scripts/icon/generate.py            # writes the files above
    python3 scripts/icon/generate.py --preview out.png   # also a sheet of the icon under masks
"""

import argparse
import io
import math
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]

# --- Geometry, in the 108-unit adaptive-icon grid. The safe zone is a 33-unit radius around the
# centre; the foreground stays within about 24 so no launcher mask crops it.
CX, CY = 54.0, 54.0
R = 24.0                        # the full moon the crescent belongs to
THETA = math.radians(-38)       # the direction the crescent opens toward (y points down)
D, r = 7.2, 21.4                # the circle cut out of the moon: its offset and radius
KX, KY = CX + D * math.cos(THETA), CY + D * math.sin(THETA)
BEAD_PATH = R - 2.2             # the beads ride just inside the moon's outline
BEAD, IMAM = 1.55, 2.45         # an ordinary bead, and the marker bead at the head
GAP = 2.6                       # clearance between the string and the crescent's tips
N = 9

WHITE, GOLD = "#FFFFFF", "#D8B562"
GRADIENT = ("#00897B", "#00594E")
STAR = 40.0                     # half-diagonal of each square in the rub el hizb


def fmt(v):
    return f"{v:.2f}"


def circle_path(x, y, rad):
    return (f"M{fmt(x - rad)},{fmt(y)} a{fmt(rad)},{fmt(rad)} 0 1,0 {fmt(2 * rad)},0 "
            f"a{fmt(rad)},{fmt(rad)} 0 1,0 {fmt(-2 * rad)},0 Z")


def crescent_tips():
    """Where the moon's circle and the cut circle cross: the crescent's two tips."""
    dx, dy = KX - CX, KY - CY
    dd = math.hypot(dx, dy)
    a = (R * R - r * r + dd * dd) / (2 * dd)
    h = math.sqrt(R * R - a * a)
    px, py = CX + a * dx / dd, CY + a * dy / dd
    return (px + h * dy / dd, py - h * dx / dd), (px - h * dy / dd, py + h * dx / dd)


def crescent_path():
    t1, t2 = crescent_tips()
    return (f"M{fmt(t1[0])},{fmt(t1[1])} A{fmt(R)},{fmt(R)} 0 1,0 {fmt(t2[0])},{fmt(t2[1])} "
            f"A{fmt(r)},{fmt(r)} 0 0,1 {fmt(t1[0])},{fmt(t1[1])} Z")


def bead_span():
    """The stretch of the moon's missing arc where a bead clears the crescent."""
    def clear(phi):
        bx, by = CX + BEAD_PATH * math.cos(phi), CY + BEAD_PATH * math.sin(phi)
        return math.hypot(bx - KX, by - KY) + BEAD + GAP <= r

    span = [THETA - math.pi + i * (2 * math.pi / 3600) for i in range(3601)]
    span = [phi for phi in span if clear(phi)]
    return min(span), max(span)


def beads():
    """Centres and radii along the span, spaced so every gap between beads is the same."""
    lo, hi = bead_span()
    sizes = [IMAM if i == N // 2 else BEAD for i in range(N)]
    gap = (BEAD_PATH * (hi - lo) - sum(sizes[i] + sizes[i + 1] for i in range(N - 1))) / (N - 1)
    out, arc = [], 0.0
    for i, size in enumerate(sizes):
        if i:
            arc += sizes[i - 1] + gap + size
        phi = lo + arc / BEAD_PATH
        out.append((CX + BEAD_PATH * math.cos(phi), CY + BEAD_PATH * math.sin(phi), size))
    return out


def thread_path():
    lo, hi = bead_span()
    x0, y0 = CX + BEAD_PATH * math.cos(lo), CY + BEAD_PATH * math.sin(lo)
    x1, y1 = CX + BEAD_PATH * math.cos(hi), CY + BEAD_PATH * math.sin(hi)
    return f"M{fmt(x0)},{fmt(y0)} A{fmt(BEAD_PATH)},{fmt(BEAD_PATH)} 0 0,1 {fmt(x1)},{fmt(y1)}"


def star_path():
    def square(rot):
        pts = [(CX + STAR * math.cos(rot + k * math.pi / 2), CY + STAR * math.sin(rot + k * math.pi / 2))
               for k in range(4)]
        return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + " Z"
    return square(math.pi / 4) + " " + square(0)


def svg(size):
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">',
        f'<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{GRADIENT[0]}"/>'
        f'<stop offset="1" stop-color="{GRADIENT[1]}"/></linearGradient></defs>',
        '<rect width="108" height="108" fill="url(#g)"/>',
        f'<path d="{star_path()}" fill="none" stroke="#FFFFFF" stroke-opacity="0.07" stroke-width="1.05" '
        'stroke-linejoin="round"/>',
        f'<path d="{crescent_path()}" fill="{WHITE}"/>',
        f'<path d="{thread_path()}" fill="none" stroke="{GOLD}" stroke-opacity="0.55" stroke-width="0.7" '
        'stroke-linecap="round"/>',
    ]
    parts += [f'<path d="{circle_path(x, y, s)}" fill="{GOLD}"/>' for x, y, s in beads()]
    parts.append("</svg>")
    return "\n".join(parts)


def render(size):
    png = cairosvg.svg2png(bytestring=svg(size).encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png))


FOREGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher foreground: the crescent completed by dhikr. The gold tasbih runs along the arc the
     crescent is missing, finishing the full moon it belongs to; the larger bead is the imam bead.
     Generated by scripts/icon/generate.py — edit the numbers there, not the paths here. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="{crescent}" />
    <!-- The thread the beads hang on, fainter than the beads so they read as a string. -->
    <path
        android:pathData="{thread}"
        android:strokeColor="#8CD8B562"
        android:strokeWidth="0.7"
        android:strokeLineCap="round" />
{beads}
</vector>
"""

BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher background: the emerald gradient, with a faint rub el hizb where a plain ring used to
     be — seven percent white, a texture behind the crescent and never a second subject. Its points
     run past the safe zone on purpose; the background is meant to be cropped by the launcher mask.
     Generated by scripts/icon/generate.py. -->
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
                android:startColor="{top}"
                android:endColor="{bottom}" />
        </aapt:attr>
    </path>
    <path
        android:pathData="{star}"
        android:strokeColor="#12FFFFFF"
        android:strokeWidth="1.05"
        android:strokeLineJoin="round" />
</vector>
"""


def write_all():
    drawable = ROOT / "android/app/src/main/res/drawable"
    bead_lines = "\n".join(
        f'    <path android:fillColor="{GOLD}" android:pathData="{circle_path(x, y, s)}" />' for x, y, s in beads()
    )
    (drawable / "ic_launcher_foreground.xml").write_text(
        FOREGROUND.format(crescent=crescent_path(), thread=thread_path(), beads=bead_lines))
    (drawable / "ic_launcher_background.xml").write_text(
        BACKGROUND.format(top=GRADIENT[0], bottom=GRADIENT[1], star=star_path()))
    render(512).convert("RGBA").save(ROOT / "store/play-icon-512.png", optimize=True)
    render(1024).convert("RGB").save(
        ROOT / "ios/Athkar/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png", optimize=True)


def preview(path):
    """The icon as a store square, under a circle and a rounded-square mask, and at launcher size."""
    img = render(512).convert("RGBA")
    sheet = Image.new("RGBA", (512 * 3 + 80 + 200, 512), (245, 245, 245, 255))
    sheet.paste(img, (0, 0))
    circle = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, 511, 511), fill=255)
    sheet.paste(img, (552, 0), circle)
    squircle = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(squircle).rounded_rectangle((0, 0, 511, 511), radius=150, fill=255)
    sheet.paste(img, (1104, 0), squircle)
    sheet.paste(img.resize((96, 96), Image.LANCZOS), (1680, 208), circle.resize((96, 96)))
    sheet.save(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--preview", metavar="PNG", help="also write a preview sheet here")
    args = parser.parse_args()
    write_all()
    if args.preview:
        preview(args.preview)
