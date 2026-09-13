#!/usr/bin/env python3
"""Frames real app screenshots for the Play listing.

    python3 scripts/compose_store_screenshots.py <captures-dir> [--out store/screenshots]

Takes the raw captures produced by `.github/workflows/store-screenshots.yml` — which photographs
the app running on an emulator — and lays each one inside a phone frame on the app's own emerald
ground, with a headline above it.

The captures are the app. Nothing here redraws a screen, invents a control, or renders text that
the app does not itself display: the phone body is a rounded rectangle around a real photograph.
That distinction is the whole point. A listing may not show functionality the app does not have,
and mock-ups drift from the product the moment either one changes.

The headlines are checked against what ships, and the check is in the file rather than in someone's
memory — see HEADLINES below.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# res/drawable/ic_launcher_background.xml
EMERALD_TOP = (0x00, 0x89, 0x7B)
EMERALD_BOTTOM = (0x00, 0x59, 0x4E)
GOLD = (0xD8, 0xB5, 0x62)
WHITE = (0xFF, 0xFF, 0xFF)

CANVAS = (1080, 1920)

# Every claim below is one the shipped app makes good on, and is deliberately narrow where the app
# is narrow. What was rejected, and why, so the next person does not re-add it:
#   "بصوت وترجمة"            — there is no recitation audio and no translation; the adhkar are text.
#   "أصوات مؤذنين متعددة"     — one recording ships, documented by hash in docs/operations.
#   "معايرة تلقائية بالمستشعرات" — the compass detects poor accuracy and asks the user to move the
#                              phone in a figure eight. It does not calibrate itself.
HEADLINES = {
    "adhkar": (
        "أذكار الصباح والمساء",
        "حصن المسلم كاملًا، يعمل بلا إنترنت",
    ),
    "reading": (
        "نصّ مشكول يُقرأ مرتاحًا",
        "بحجم خط تختاره، وعدّاد لكل ذكر بلمسة",
    ),
    "prayer": (
        "مواقيت الصلاة والأذان",
        "الأذان عند دخول الوقت، وتنبيه قبله بما تختار",
    ),
    "qibla": (
        "اتجاه القبلة",
        "بوصلة بميزان استواء تقول أي جهة تدير الجهاز وكم درجة",
    ),
    "tasbih": (
        "مسبحة إلكترونية",
        "اضغط في أي مكان، والعدّ محفوظ ولو أغلقت التطبيق",
    ),
    "appearance": (
        "مظهر يتبع وقت الصلاة",
        "لون للفجر وآخر للظهر، ويُظلم التطبيق بعد المغرب",
    ),
}

# Six, not four. The listing had eight raw captures on it — an older, three-tab app, unframed, one
# of them with the keyboard covering half the screen — and replacing a type on Play replaces all of
# it. Four accurate screenshots beat eight stale ones, but there is no reason to hand back half the
# page while doing it.
ORDER = ["adhkar", "reading", "prayer", "qibla", "tasbih", "appearance"]


def load_font(size: int, bold: bool) -> ImageFont.FreeTypeFont:
    """A font with Arabic glyphs, or the run fails loudly rather than drawing tofu."""
    # DejaVu is not a candidate: it covers Arabic codepoints but not the joining forms, so it
    # draws the letters apart. Noto's Arabic faces are the ones that shape.
    candidates = [
        "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansArabic-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansArabic-SemiBold.ttf" if bold
        else "/usr/share/fonts/truetype/noto/NotoSansArabic-Medium.ttf",
    ]
    for path in candidates:
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    raise SystemExit(
        "no Arabic-capable font found; install fonts-noto-core or fonts-dejavu and re-run"
    )


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


def centred(draw: ImageDraw.ImageDraw, text: str, y: int, font, fill) -> int:
    """
    Draws Arabic through Pillow's Raqm layout, which is the only thing here that shapes text.

    Reshaping it by hand first — arabic_reshaper plus a bidi pass — is the usual recipe and is
    wrong when Raqm is present: it hands HarfBuzz pre-substituted presentation forms in visual
    order, and they come out as unjoined letters. `direction="rtl"` and the original string is all
    that is needed.
    """
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font, direction="rtl")
    draw.text(
        ((CANVAS[0] - (right - left)) / 2 - left, y),
        text, font=font, fill=fill, direction="rtl", language="ar",
    )
    return bottom - top


def compose(capture: Path, title: str, subtitle: str, out: Path) -> None:
    canvas = gradient(CANVAS)
    draw = ImageDraw.Draw(canvas)

    y = 110
    y += centred(draw, title, y, load_font(66, bold=True), WHITE) + 34
    centred(draw, subtitle, y, load_font(34, bold=False), (0xCF, 0xE6, 0xDF))

    # A gold rule under the heading, as in the reference layout.
    draw.rounded_rectangle([CANVAS[0] // 2 - 70, 300, CANVAS[0] // 2 + 70, 306], radius=3, fill=GOLD)

    # The phone: a rounded body with the real capture inset. Sized to leave the headline room and
    # to keep the screen's own aspect ratio, whatever the emulator produced.
    shot = Image.open(capture).convert("RGB")
    body_y = 390
    bezel = 28
    # Phone screens are far taller than they are wide, so width is never the binding constraint —
    # fit to the height that is left below the headline, then derive the width from the capture's
    # own ratio. Sizing by width instead ran the body 200px past the bottom of the canvas.
    max_body_h = CANVAS[1] - body_y - 90
    screen_h = max_body_h - 2 * bezel
    screen_w = round(screen_h * shot.width / shot.height)
    body_w = screen_w + 2 * bezel
    body_h = max_body_h
    body_x = (CANVAS[0] - body_w) // 2

    draw.rounded_rectangle(
        [body_x, body_y, body_x + body_w, body_y + body_h],
        radius=56,
        fill=(0x05, 0x2E, 0x27),
    )
    canvas.paste(
        shot.resize((screen_w, screen_h), Image.LANCZOS),
        (body_x + bezel, body_y + bezel),
        rounded_mask((screen_w, screen_h), 34),
    )

    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, format="PNG")
    print(f"wrote {out} ({CANVAS[0]}x{CANVAS[1]}, RGB)")


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("captures", type=Path, help="directory of raw emulator captures")
    parser.add_argument("--out", type=Path, default=Path("store/screenshots"))
    args = parser.parse_args()

    files = sorted(p for p in args.captures.iterdir() if p.suffix.lower() == ".png")
    if not files:
        raise SystemExit(f"no PNG captures in {args.captures}")

    for index, key in enumerate(ORDER):
        if index >= len(files):
            print(f"warning: no capture for '{key}'", file=sys.stderr)
            continue
        title, subtitle = HEADLINES[key]
        compose(files[index], title, subtitle, args.out / f"{index + 1}-{key}.png")


if __name__ == "__main__":
    main()
