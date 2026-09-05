# Athkar — Third-party assets

App: Athkar (Arabic dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: Release Engineering / Product
Status: Active

Every file the app ships that was not authored here, where it came from, what licence it carries,
and what still has to be checked before it goes to a store. `LICENSE` states the legal position;
this document is the operational record behind it.

Cross-references: [`LICENSE`](../../LICENSE), [`README.md`](../../README.md#the-adhan),
[`release-plan.md`](release-plan.md) (rollout gates), [`store-assets.md`](store-assets.md)
(Play/App Review policy gates).

---

## 1. The adhan recording

| | |
|---|---|
| Bundled at | `android/app/src/main/res/raw/adhan.ogg` |
| Source | <https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg> |
| Author | Wikimedia Commons user `Adam-synagda`, uploaded 2022-04-29 |
| Licence | CC0 1.0 Universal — public-domain dedication (`{{self|Cc-zero}}`) |
| Attribution required | No. Credit is given anyway, as a courtesy and as a record. |
| Retrieved | 2026-09-05 |
| Format | Ogg Vorbis, 154.0 s, 44,100 Hz, 2 ch (dual-mono), ~64 kbps average |
| Size | 1,229,032 bytes |
| SHA-1 | `a1fa4fd942401922c5c3301816384aae86956522` |
| SHA-256 | `35fe06b08fe80505c550c33fed8a783fa9901ddc81ac884958b4be048f5b2a79` |

### Why it is bundled unmodified

The file is byte-identical to the Commons upload. That is deliberate and worth protecting: it makes
the licence claim checkable rather than merely asserted. Anyone can verify it, including from a
shipped APK —

```bash
# From the working tree
sha1sum android/app/src/main/res/raw/adhan.ogg

# From a release APK, where resource shrinking renames the path but does not touch the bytes
unzip -p app-release.apk res/lE.ogg | sha256sum

# Against upstream
curl -s "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo\
&iiprop=sha1|size&titles=File:Beautiful%20adhan.ogg"
```

Re-encoding would destroy that property permanently and buy almost nothing. Measured: the channels
are bit-identical (L−R residual −91 dB), the content is already band-limited to ~9.5 kHz, and a mono
re-encode saves ~0.35 MB of a ~10 MB bundle at a measurable second generation of loss. The recording
integrates at −8.8 LUFS with a −2.2 dBFS true peak — roughly 8 LU **louder** than Android's own
`Alarm_Classic.ogg` — so it needs no normalisation, and applying make-up gain measurably clips it.

`.ogg` is on aapt2's no-compress list, so it is **stored** uncompressed in the APK. That is not
cosmetic: `Resources.openRawResourceFd`, which the player uses, returns null for a compressed
resource. Any future replacement must keep the `.ogg` extension for that reason — `.opus` is on
neither aapt2's nor bundletool's no-compress list and would break playback in release builds only.

### Never rename this file

`res/raw/adhan.ogg` is also the sound of the notification channel `prayer_times_adhan_v1`, used on
the fallback path. A channel's sound is frozen when Android creates it, so on every device where
that channel already exists the URI is fixed for the life of the install. Renaming the resource, or
changing the recording in a way that shifts its generated id, orphans that sound. Replacing the
**contents** of this exact path is safe; renaming the path is not.

---

## 2. Open gate — the recording has not been listened to

**Status: NOT CLEARED. This blocks the first release that ships the adhan.**

The recording was selected on licence, duration, loudness and structural grounds. Its structure is
consistent with a complete adhan: thirteen pauses dividing 154 seconds into roughly fourteen
phrases, which is the expected shape. **Nobody has listened to it, and structure is not text.**

What a fluent listener has to confirm, end to end:

1. It is a complete and correct adhan, not a partial recording, a rehearsal, or a different call.
2. The wording matches the tradition this app intends to serve. Sunni and Twelver Shia formulas
   differ, and shipping the wrong one in an app that presents itself as neutral is not a defect a
   changelog can repair.
3. It does **not** contain the Fajr *tathwīb* — «الصلاة خير من النوم». The app ships one recording
   and plays it for all five prayers, so a Fajr-specific line would be wrong four times a day. If
   the recording does contain it, the file is a Fajr recording and must be moved to
   `R.raw.adhan_fajr` with a non-Fajr recording sourced for `R.raw.adhan`.
4. Audio quality is acceptable as an alarm: no clipping, no truncated ending, no extraneous speech
   or background content at either end.

Record the outcome here, with the date and who checked, before the release rolls.

### If a different recording is wanted

One file, same path, same extension. Then update the hashes and provenance in this table, in
`LICENSE` item 3, and in the README, and re-read §1 on renaming. If the replacement is licensed
under anything other than CC0 or an equivalent public-domain dedication, the attribution obligations
it carries have to be surfaced **in the app**, not only in the repository.

### Adding a Fajr recording

`AdhanPlayerService.sourceFor` is the single place that changes: add `R.raw.adhan_fajr` and branch
on `Prayer.FAJR`. Reference it as a literal `R.raw.*` — a `getIdentifier` lookup fails lint under
`warningsAsErrors` and is stripped by resource shrinking, which fails only in release.

---

## 3. The adhkar text

Covered in [`LICENSE`](../../LICENSE) item 2 and [`README.md`](../../README.md#adhkar-content):
the text of *Hisn al-Muslim*, retrieved 2026-08-28 from `https://www.hisnmuslim.com/api/ar/husn_ar.json`,
bundled at `android/data/src/main/assets/athkar_seed.json`.
