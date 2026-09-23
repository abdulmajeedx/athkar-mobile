#!/usr/bin/env python3
"""
Draws the app icon — the crescent that is a tasbih — and writes every copy of it.

The crescent is not drawn and then decorated: it is made of the beads. Each bead sits on the
crescent's midline and is sized to the crescent's own thickness at that point, so the string
swells through the belly of the moon and thins toward its horns, the way the crescent does. From
the lower horn hangs the imam bead, in gold, with the fanned tassel a real tasbih ends in.

Every copy is written from the same numbers, so the launcher, the store listing and iOS cannot
drift apart:

  android/app/src/main/res/drawable/ic_launcher_{foreground,background}.xml
  store/play-icon-512.png                                   (Play wants 512², alpha allowed)
  ios/Athkar/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
                                                            (App Store rejects an alpha channel)

    pip install cairosvg pillow
    python3 scripts/icon/generate.py                     # writes the files above
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
# centre, and everything below stays inside it so no launcher mask crops the icon.
CX, CY = 54.0, 54.0
R = 24.0                        # the moon the crescent is cut from
THETA = math.radians(-40)       # the direction the crescent opens toward (y points down)
D, r = 7.6, 19.4                # the circle cut out of the moon: its offset and radius
BEAD_SCALE = 0.43               # a bead's radius as a share of the crescent's local thickness
BEAD_MAX = 3.9
BEAD_MIN = 1.35                 # below this a bead reads as a speck; the horns stop there
BEAD_GAP = 0.8
DROP_AT_TAIL = 2                # beads left off the lower horn, to make room for the imam bead
IMAM = 2.3

WHITE, GOLD = "#FFFFFF", "#D8B562"
GRADIENT = ("#00897B", "#00594E")


def fmt(v):
    return f"{v:.2f}"


def circle_path(x, y, rad):
    return (f"M{fmt(x - rad)},{fmt(y)} a{fmt(rad)},{fmt(rad)} 0 1,0 {fmt(2 * rad)},0 "
            f"a{fmt(rad)},{fmt(rad)} 0 1,0 {fmt(-2 * rad)},0 Z")


def crescent_beads():
    """Beads along the crescent's midline, each sized to the crescent's thickness where it sits."""
    kx, ky = CX + D * math.cos(THETA), CY + D * math.sin(THETA)

    def cut_edge(phi):
        # Distance from the moon's centre, along phi, to the far edge of the cut circle.
        ux, uy = math.cos(phi), math.sin(phi)
        ox, oy = CX - kx, CY - ky
        b = ox * ux + oy * uy
        disc = b * b - (ox * ox + oy * oy - r * r)
        return None if disc < 0 else -b + math.sqrt(disc)

    # Walk the crescent from its upper horn, round its belly, to its lower horn.
    start = THETA + math.pi
    samples = []
    for i in range(2000):
        phi = start - math.pi + 2 * math.pi * i / 2000
        edge = cut_edge(phi)
        if edge is not None and edge < R and R - edge > 1.2:
            samples.append((phi, (R + edge) / 2, R - edge))

    beads, last = [], None
    for phi, mid, thick in samples:
        size = min(BEAD_SCALE * thick, BEAD_MAX)
        if size < BEAD_MIN:
            continue
        x, y = CX + mid * math.cos(phi), CY + mid * math.sin(phi)
        if last is None or math.hypot(x - last[0], y - last[1]) >= last[2] + size + BEAD_GAP:
            beads.append((x, y, size))
            last = (x, y, size)
    if beads[-1][1] < beads[0][1]:
        beads.reverse()                 # end at the lower horn, where the string hangs from
    return beads[:-DROP_AT_TAIL]


def layout():
    """The whole figure, centred on the icon: beads, cord, imam bead and tassel."""
    beads = crescent_beads()
    tail = beads[-1]
    ix, iy = tail[0] + 1.2, tail[1] + tail[2] + 3.2
    tassel_bottom = iy + IMAM + 9.4

    xs = [x - s for x, _, s in beads] + [x + s for x, _, s in beads] + [ix - IMAM, ix + IMAM]
    ys = [y - s for _, y, s in beads] + [y + s for _, y, s in beads] + [tassel_bottom]
    ox, oy = CX - (min(xs) + max(xs)) / 2, CY - (min(ys) + max(ys)) / 2
    beads = [(x + ox, y + oy, s) for x, y, s in beads]
    ix, iy = ix + ox, iy + oy
    tail = beads[-1]

    cord = f"M{fmt(tail[0])},{fmt(tail[1])} L{fmt(ix)},{fmt(iy)}"
    ty = iy + IMAM
    tassel_cord = f"M{fmt(ix)},{fmt(ty)} L{fmt(ix)},{fmt(ty + 3.4)}"
    tassel = (f"M{fmt(ix)},{fmt(ty + 2.8)} L{fmt(ix - 2.1)},{fmt(ty + 8.4)} "
              f"Q{fmt(ix)},{fmt(ty + 9.4)} {fmt(ix + 2.1)},{fmt(ty + 8.4)} Z")
    return beads, (ix, iy), cord, tassel_cord, tassel


def svg(size):
    beads, (ix, iy), cord, tassel_cord, tassel = layout()
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">',
        f'<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{GRADIENT[0]}"/>'
        f'<stop offset="1" stop-color="{GRADIENT[1]}"/></linearGradient></defs>',
        '<rect width="108" height="108" fill="url(#g)"/>',
    ]
    # The cord first, so the bead it leaves covers its end rather than being crossed by it.
    parts.append(f'<path d="{cord}" stroke="{GOLD}" stroke-width="0.8" fill="none"/>')
    parts += [f'<path d="{circle_path(x, y, s)}" fill="{WHITE}"/>' for x, y, s in beads]
    parts += [
        f'<path d="{circle_path(ix, iy, IMAM)}" fill="{GOLD}"/>',
        f'<path d="{tassel_cord}" stroke="{GOLD}" stroke-width="1.0" stroke-linecap="round" fill="none"/>',
        f'<path d="{tassel}" fill="{GOLD}"/>',
        "</svg>",
    ]
    return "\n".join(parts)


def render(size):
    png = cairosvg.svg2png(bytestring=svg(size).encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png))


FOREGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher foreground: the crescent that is a tasbih. The white beads are the crescent, each sized
     to its thickness where it sits; from the lower horn hang the gold imam bead and tassel.
     Generated by scripts/icon/generate.py — edit the numbers there, not the paths here. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- The cord before the beads, so the last bead covers its end. -->
    <path
        android:pathData="{cord}"
        android:strokeColor="{gold}"
        android:strokeWidth="0.8" />
{beads}
    <path android:fillColor="{gold}" android:pathData="{imam}" />
    <path
        android:pathData="{tassel_cord}"
        android:strokeColor="{gold}"
        android:strokeWidth="1.0"
        android:strokeLineCap="round" />
    <path android:fillColor="{gold}" android:pathData="{tassel}" />
</vector>
"""

BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher background: the emerald gradient, and nothing else — the beads are the whole icon.
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
</vector>
"""


def write_all():
    beads, (ix, iy), cord, tassel_cord, tassel = layout()
    drawable = ROOT / "android/app/src/main/res/drawable"
    bead_lines = "\n".join(
        f'    <path android:fillColor="{WHITE}" android:pathData="{circle_path(x, y, s)}" />' for x, y, s in beads
    )
    (drawable / "ic_launcher_foreground.xml").write_text(FOREGROUND.format(
        beads=bead_lines, cord=cord, gold=GOLD, imam=circle_path(ix, iy, IMAM),
        tassel_cord=tassel_cord, tassel=tassel))
    (drawable / "ic_launcher_background.xml").write_text(
        BACKGROUND.format(top=GRADIENT[0], bottom=GRADIENT[1]))
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


def extent():
    """How far the figure reaches from the centre — must stay under the 33-unit safe radius."""
    beads, (ix, iy), *_ = layout()
    reach = [math.hypot(x - CX, y - CY) + s for x, y, s in beads]
    reach.append(math.hypot(ix - CX, iy + IMAM + 9.4 - CY))
    return max(reach)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--preview", metavar="PNG", help="also write a preview sheet here")
    args = parser.parse_args()
    assert extent() < 33, f"the icon reaches {extent():.1f} from the centre, past the 33-unit safe zone"
    write_all()
    if args.preview:
        preview(args.preview)
