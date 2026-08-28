# أذكاري — iOS

<div dir="rtl">

نسخة iOS من التطبيق: الأذكار ومواقيت الصلاة والقبلة، بنفس محرك الحسابات ونفس المحتوى المستعملين في
نسخة أندرويد.

</div>

> **This code has never been compiled.** It was written on a Linux machine with no Swift toolchain,
> so every line here is unverified in a way the Android app is not. Expect to fix compile errors on
> the first build. Run the test suite before trusting a single prayer time — it carries the same
> published reference values as the Android suite, so it will tell you whether the port is faithful.

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

| | Android | iOS |
|---|---|---|
| Storage | Room + SQLCipher | `UserDefaults` |
| Alerts | `AlarmManager`, two-day window, re-armed on boot | `UNCalendarNotificationTrigger`, 10-day window, rebuilt on foreground |
| Compass | rotation vector + manual magnetic declination | `CLHeading.trueHeading` |

The storage difference is deliberate. The corpus is read-only bundled content and the only mutable
state is a set of favourite ids and a few scalars, so a database would buy nothing here — the
Android side keeps one because its schema also carries the sync outbox and CRDT metadata.

The notification difference is forced: iOS gives an app no reliable background moment to reschedule,
and prayer times move daily so no repeating trigger can express them. A long window rebuilt on every
foreground is the only shape that works, and iOS keeps just the 64 soonest pending notifications —
which is what caps the window at ten days.

## Not implemented

- **No adhan audio.** Alerts use the default notification sound; a custom sound needs an audio file
  under 30 seconds in the bundle.
- **No sync.** Same as Android: there is no server.
- **Portrait only**, matching the compass maths.
