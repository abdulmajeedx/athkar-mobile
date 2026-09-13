# أذكاري — iOS

<div dir="rtl">

نسخة iOS من التطبيق: الأذكار ومواقيت الصلاة والقبلة، بنفس محرك الحسابات ونفس المحتوى المستعملين في
نسخة أندرويد.

</div>

This app was written on a Linux machine with no Swift toolchain, so it is compiled and tested on a
GitHub macOS runner instead — see [`ios-ci.yml`](../.github/workflows/ios-ci.yml), which runs on
every push that touches `ios/`. The suite carries the same published reference values as the Android
one, so a divergence between the two ports fails a test rather than leaving two apps quietly
disagreeing about when Fajr is.

**Last run: 39 tests, 0 failures.** What is *not* verified is everything a test cannot see: no one
has looked at these screens on a device, and nobody has heard the adhan come out of one.

## Build

```bash
brew install xcodegen        # once
cd ios
xcodegen generate            # writes Athkar.xcodeproj from project.yml
open Athkar.xcodeproj
```

Then in Xcode: select your device, set **Signing & Capabilities → Team** to your Apple ID, and run.

No XcodeGen? Create a new iOS App project named `Athkar` in Xcode, drag the `Athkar/` folder in
(choosing *Create groups*), add `athkar_seed.json` to the app target's *Copy Bundle Resources*, and
set the Info.plist keys listed in [`project.yml`](project.yml).

### Running on your iPhone without a paid account

A free Apple ID works, with two limits Apple imposes and this project cannot lift: the certificate
expires after **7 days** (re-run from Xcode to renew), and you may install on at most 3 apps at a
time. A paid Apple Developer account removes both and unlocks TestFlight.

## Test

```bash
xcodebuild test -scheme Athkar -destination 'platform=iOS Simulator,name=iPhone 15'
```

`PrayerTimesTests` checks the published timings for Raleigh, madhab ordering, the Umm al-Qura
interval, strict ordering across 366 consecutive days, high-latitude clamping and the polar-day
error. `QiblaTests` checks published great-circle bearings for eleven cities across both
hemispheres. `AdhkarLibraryTests` checks that all 132 chapters and 267 readings actually reach the
app bundle.

## Layout

```
ios/
├── project.yml                     XcodeGen manifest — the .xcodeproj is generated, not committed
├── Athkar/
│   ├── App/AthkarApp.swift         entry point, three tabs, RTL pinned, notification refresh
│   ├── Domain/                     pure Swift, no UIKit — port of android/core/prayer
│   │   ├── Angles.swift            degree/radian helpers and angle normalisation
│   │   ├── Astronomical.swift      Meeus solar position with nutation and 3-day interpolation
│   │   ├── CalculationParameters.swift  11 methods, both madhabs, high-latitude rules
│   │   ├── Coordinates.swift       validated lat/long
│   │   ├── PrayerTimes.swift       the six times, next/current prayer, sunnah night portions
│   │   └── Qibla.swift             great-circle bearing and distance to the Kaaba
│   ├── Data/
│   │   ├── AdhkarLibrary.swift     reads the bundled corpus once
│   │   ├── Cities.swift            offline city list and nearest-city naming
│   │   ├── PreferencesStore.swift  UserDefaults-backed settings and favourites
│   │   └── LocationProvider.swift  CoreLocation fix + true-north heading
│   ├── Notifications/              rolling 10-day notification window
│   ├── Presentation/               theme, Arabic formatting, three screens
│   └── Resources/athkar_seed.json  the corpus, identical to the Android asset
└── AthkarTests/                    the Android suite's reference values, in XCTest
```

## Differences from the Android app

Feature for feature the two are level: the same corpus, the same timings, the tasbih, the theme that
follows the prayer, the pre-adhan warning, the iqama, the compass with its level and its solar
correction. What differs is what the platforms decide, not what was left out.

| | Android | iOS |
|---|---|---|
| Storage | Room + SQLCipher | `UserDefaults` |
| Alerts | `AlarmManager`, two-day window, re-armed on boot | `UNCalendarNotificationTrigger`, 7-day window, rebuilt on foreground |
| Adhan | the full 2m34s through a foreground service | 30s as the notification's own sound |
| Compass | rotation vector + manual magnetic declination | `CLHeading.trueHeading` |
| Level | rotation vector, remapped for display rotation | CoreMotion device attitude |

**The adhan is the one real loss.** iOS caps a notification sound at thirty seconds and silently
substitutes its own tone for anything longer — and an app that is not running cannot play audio at
all, so there is no second mechanism to fall back on the way `AdhanPlayerService` is on Android. The
bundled `adhan.caf` is the first twenty-nine seconds with a two-second fade, IMA4-compressed to
680 KB, cut by ffmpeg from the same recording Android ships. `ios-ci.yml` fails the build if it ever
reaches the limit, because the failure mode on a device is not an error — it is the wrong sound.

Twenty-nine rather than thirty because the check caught the first cut: asking ffmpeg for exactly
thirty seconds produced 30.000181s, which is thirty seconds to any reader and over the line to iOS.

The storage difference is deliberate. The corpus is read-only bundled content and the only mutable
state is a set of favourite ids and a few scalars, so a database would buy nothing here — the
Android side keeps one because its schema also carries the sync outbox and CRDT metadata.

The notification difference is forced: iOS gives an app no reliable background moment to reschedule,
and prayer times move daily so no repeating trigger can express them. A long window rebuilt on every
foreground is the only shape that works, and iOS keeps just the 64 soonest pending notifications —
which is what caps the window at ten days.

## Shipping to TestFlight

[`ios-testflight.yml`](../.github/workflows/ios-testflight.yml) archives, exports and uploads on a
macOS runner when you push an `ios-vX.Y.Z` tag. Signing assets are created by Xcode itself through
`-allowProvisioningUpdates`, authenticated by an App Store Connect API key, so no `.p12` or
`.mobileprovision` ever has to be base64'd into a secret.

**This workflow has never run.** It needs a paid Apple Developer account, which this project does
not have, so unlike the Android release pipeline it is unproven. Everything that *can* be settled
without an account has been: the bundle id, the icon, the version, the usage strings, and the two
Info.plist keys below that otherwise stop a build between the upload and a tester.

Setup is four things, and only the third takes any thought:

1. Join the Apple Developer Program.
2. Register the bundle id `com.athkar.app` and create the app record in App Store Connect.
3. Create an App Store Connect API key with the **App Manager** role, then set four repository
   secrets:

   | Secret | Where it comes from |
   |---|---|
   | `APPSTORE_ISSUER_ID` | App Store Connect → Users and Access → Integrations |
   | `APPSTORE_KEY_ID` | the key's ID on the same page |
   | `APPSTORE_PRIVATE_KEY` | the whole contents of the downloaded `AuthKey_*.p8` |
   | `APPLE_TEAM_ID` | Apple Developer → Membership |

4. Nothing else. The icon is in `Athkar/Resources/Assets.xcassets`, drawn together with the Android
   one by [`scripts/generate_icons.py`](../scripts/generate_icons.py) — edit the script and re-run
   it rather than replacing the PNG by hand, so the source and the artwork stay in step.

Then, per release:

```bash
git tag ios-v1.6.0
git push origin ios-v1.6.0
```

### Two keys that are already set, and why they matter

`project.yml` carries both, and each one is a build that would otherwise reach App Store Connect and
stop there:

- `ITSAppUsesNonExemptEncryption: false` — without it every upload lands in TestFlight marked
  **Missing Compliance** and reaches no tester until somebody answers the export question by hand in
  the web console. The answer is no and it cannot change: the app makes no network requests at all.
- `NSMotionUsageDescription` — the compass reads device attitude for its level. No prompt is shown
  for it, but App Review looks for the string in any binary that links CoreMotion.

The build number is the repository's commit count, which only ever grows — TestFlight rejects a
build number it has already seen for a version, and a run number resets if the workflow is recreated.

## Not implemented

- **No sync.** Same as Android: there is no server.
- **Portrait only**, matching the compass maths.
- **No "qibla by the sun" card.** Android lists the two moments each day when the sun stands in the
  qibla direction. The solar *correction* — the stronger feature, which uses the sun at any hour
  rather than two — is here; the scan that finds those two instants is not ported yet.
