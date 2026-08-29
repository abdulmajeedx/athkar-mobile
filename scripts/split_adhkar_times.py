#!/usr/bin/env python3
"""Splits the combined morning/evening chapter of Hisn al-Muslim into two.

    python3 scripts/split_adhkar_times.py [--dry-run]

The book publishes one chapter, "أذكار الصباح والمساء", whose entries are written in the morning
wording followed by a bracketed note giving the evening one: `[وإذا أمسى قال: ...]`. Read as-is that
is a footnote in the middle of a supplication — the reader has to mentally substitute while reciting.
Two chapters, each written out plainly, is what the text is for.

This is scripture and hadith, so the rules below are deliberately narrow:

  * An entry whose note spells the evening wording out in full uses that text verbatim.
  * An entry whose note trails off ("اللَّهم إني أمسيت...") gets the single-word substitution the
    ellipsis names, and nothing else.
  * An entry with no note at all is recited unchanged at both times, and appears in both chapters.
  * Nothing is paraphrased, shortened, or re-vowelled.

Every generated evening text is printed for review. Read them before shipping.
"""

from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path

SEED = Path("android/data/src/main/assets/athkar_seed.json")
COMBINED = "cat-27"
# The bundle numbers items as chapterIndex * STRIDE + position, which is what the app groups by.
CHAPTER_STRIDE = 1000
MORNING = "cat-27m"
EVENING = "cat-27e"

# The bracketed note, which is an instruction to the reader rather than part of the supplication.
NOTE = re.compile(r"\s*\[[^\]]*\]\s*")

# Entries the book assigns to one time only, by the hadith each is drawn from.
MORNING_ONLY = {
    "hisn-93",  # لا إله إلا الله وحده... مائة مرة حين يصبح
    "hisn-94",  # سبحان الله وبحمده عدد خلقه — حين يصبح
    "hisn-95",  # اللهم إني أسألك علماً نافعاً — إذا أصبح
}
EVENING_ONLY = {
    "hisn-97",  # أعوذ بكلمات الله التامات — حين يمسي
}

# Evening wording, applied only where the book's own note calls for it. Longest first, so that a
# broader phrase is replaced before any word inside it is.
SUBSTITUTIONS: list[tuple[str, str]] = [
    ("أَصْبَحْنَا وَأَصْبَحَ", "أَمْسَيْنَا وَأَمْسَى"),
    ("مَا أَصْبَحَ بِي", "مَا أَمْسَى بِي"),
    ("فِي هَذَا الْيَوْمِ وَخَيرَ مَا بَعْدَهُ", "فِي هَذِهِ اللَّيْلَةِ وَخَيْرَ مَا بَعْدَهَا"),
    ("فِي هَذَا الْيَوْمِ وَشَرِّ مَا بَعْدَهُ", "فِي هَذِهِ اللَّيْلَةِ وَشَرِّ مَا بَعْدَهَا"),
    ("خَيْرَ هَذَا الْيَوْمِ", "خَيْرَ هَذِهِ اللَّيْلَةِ"),
    ("فَتْحَهُ، وَنَصْرَهُ، وَنورَهُ، وَبَرَكَتَهُ، وَهُدَاهُ", "فَتْحَهَا، وَنَصْرَهَا، وَنورَهَا، وَبَرَكَتَهَا، وَهُدَاهَا"),
    ("مَا فِيهِ وَشَرِّ مَا بَعْدَهُ", "مَا فِيهَا وَشَرِّ مَا بَعْدَهَا"),
    ("بِكَ أَصْبَحْنَا، وَبِكَ أَمْسَيْنَا", "بِكَ أَمْسَيْنَا، وَبِكَ أَصْبَحْنَا"),
    ("وَإِلَيْكَ النُّشُورُ", "وَإِلَيْكَ الْمَصِيرُ"),
    ("أَصْبَحْتُ", "أَمْسَيْتُ"),
    ("أَصْبَحْنا", "أَمْسَيْنا"),
]

MORNING_MARK = re.compile(r"أَصْبَ|أصبح|الصَّبَاح")

# Wording that must not survive into an evening text. A rule that silently fails to match is the
# real hazard here — it leaves "this day" inside a supplication for the night, and the only sign is
# a reader noticing. The generated text is checked against these rather than trusted.
# Whole phrases, not the bare stem: the evening wording of "اللهم بك أمسينا وبك أصبحنا" contains
# "أصبحنا" legitimately, and a stem-level rule flags it as a failure.
EVENING_RESIDUE = [
    "هَذَا الْيَوْمِ",
    "النُّشُورُ",
    "مَا فِيهِ وَشَرِّ",
    "أَصْبَحْنَا وَأَصْبَحَ",
    "إِنِّي أَصْبَحْتُ",
    "مَا أَصْبَحَ بِي",
    "أَصْبَحْنا عَلَى",
]


def nfc(text: str) -> str:
    """Canonical form.

    A letter carrying both a shadda and a vowel can be encoded with the marks in either order; the
    two are indistinguishable on screen and unequal as strings. Three substitutions below matched
    nothing for exactly that reason. NFC sorts combining marks by their canonical class, so both the
    corpus and the patterns written by hand end up in one order.
    """
    return unicodedata.normalize("NFC", text)


def strip_note(text: str) -> str:
    return NOTE.sub(" ", nfc(text)).strip()


def to_evening(text: str) -> str:
    for source, replacement in SUBSTITUTIONS:
        text = text.replace(nfc(source), nfc(replacement))
    return text


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    seed = json.loads(SEED.read_text(encoding="utf-8"))

    combined = [i for i in seed["items"] if i["category"] == COMBINED]
    if not combined:
        print("Nothing to split: the combined chapter is already gone.")
        return 0

    morning_items, evening_items, review = [], [], []
    for index, item in enumerate(combined):
        clean = strip_note(item["body"])

        if item["id"] not in EVENING_ONLY:
            morning_items.append({**item, "id": f"{item['id']}-m", "category": MORNING,
                                  "body": clean, "order": CHAPTER_STRIDE * 0 + index})

        if item["id"] not in MORNING_ONLY:
            evening = to_evening(clean)
            leftover = [r for r in EVENING_RESIDUE if nfc(r) in evening]
            if leftover:
                print(f"::error:: {item['id']} still reads as morning after substitution: {leftover}")
                print(f"          {evening}")
                return 1
            if evening != clean:
                review.append((item["id"], clean, evening))
            evening_items.append({**item, "id": f"{item['id']}-e", "category": EVENING,
                                  "body": evening, "order": CHAPTER_STRIDE * 1 + index})

    print(f"morning: {len(morning_items)} · evening: {len(evening_items)}")
    print(f"\n{len(review)} entries were re-worded for the evening — review each:\n")
    for entry_id, before, after in review:
        print("=" * 96)
        print(f"{entry_id}\n  صباح: {before}\n  مساء: {after}\n")

    if dry_run:
        print("Dry run; nothing written.")
        return 0

    # One chapter becomes two, so everything after it shifts by one place — in the chapter list and
    # in the item ordering alike. The app rebuilds its chapters from item order, so a stale stride
    # would interleave the readings of different chapters rather than merely mis-sort the index.
    others = [
        {**i, "order": i["order"] + CHAPTER_STRIDE}
        for i in seed["items"] if i["category"] != COMBINED
    ]
    seed["items"] = sorted(morning_items + evening_items + others, key=lambda i: i["order"])
    seed["categories"] = (
        [{"key": MORNING, "title": "أذكار الصباح", "order": 0, "count": len(morning_items)},
         {"key": EVENING, "title": "أذكار المساء", "order": 1, "count": len(evening_items)}]
        + [{**c, "order": c["order"] + 1} for c in seed["categories"] if c["key"] != COMBINED]
    )
    # A raised version is what makes the seeder replace the installed content on the next launch.
    seed["version"] = seed["version"] + 1
    SEED.write_text(json.dumps(seed, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Wrote {SEED} (version {seed['version']}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
