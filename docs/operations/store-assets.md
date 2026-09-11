# Athkar — Store Assets & Submission

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: Release Engineering / Product
Status: Active (copy is boilerplate; placeholder URLs to be finalized at first submission)

This document contains the App Store and Play Store listing content, the signing/provisioning
setup, the policy-compliance notes that gate App Review / Play approval, and the CI/CD pipeline
that produces and uploads signed artifacts with gradual rollout.

Cross-references: `docs/operations/release-plan.md` (versioning, flags, rollout gates),
`docs/operations/observability.md` (gates metrics), `docs/security/secret-rotation-policy.md`
(signing keys), `docs/security/masvs-l2-checklist.md` (privacy/permissions posture).

---

## Table of contents

1. [App identity & naming](#1-app-identity--naming)
2. [App Store listing](#2-app-store-listing)
3. [Play Store listing](#3-play-store-listing)
4. [Signing & provisioning](#4-signing--provisioning)
5. [App & policy compliance notes](#5-app--policy-compliance-notes)
6. [CI/CD pipeline (GitHub Actions)](#6-cicd-pipeline-github-actions)

---

## 1. App identity & naming

| Field | App Store (iOS) | Play Store (Android) |
|---|---|---|
| **Package / bundle identifier** | `com.athkar.app` (register in Developer portal and use everywhere: project, entitlement, store) | `com.athkar.mobile` — frozen at first Play publish; the iOS bundle id is a separate identifier and deliberately differs |
| **App name (default)** | Athkar — Dhikr & Prayer (≤ 30 chars) | Athkar — Dhikr & Prayer |
| **App name (localized, Arabic)** | أذكار | أذكار |
| **Subtitle (iOS)** | Daily dhikr & prayer times, fully offline | — |

App-name ideas (all ≤ 30 chars, trademark-checked at submission):
- `Athkar`
- `أذكار`
- `Athkar — Dhikr & Prayer`
- `Athkar: Adhkar & Prayer Times`
- `My Athkar`
- `أذكاري`

Default pick: **Athkar — Dhikr & Prayer** with Arabic localization **أذكار**. Keep one name per
store and do not vary it across updates (helps ranking and review history).

---

## 2. App Store listing

### 2.1 Name / subtitle / promotional text

- Name: `Athkar — Dhikr & Prayer`
- Subtitle: `Daily dhikr and prayer times, fully offline`
- Promotional text (optional, capped 170 chars): `Morning and evening adhkar, tasbeeh counter and
  exact prayer times - all stored on your device, no account needed.`

### 2.2 Short description (≤ 80 characters)

Arabic (primary):
`أذكار الصباح والمساء والتسبيح ومواقيت الصلاة - يعمل بدون إنترنت.`

English (secondary):
`Morning/evening adhkar, tasbeeh counter and prayer times - works fully offline.`

### 2.3 Long description

**Arabic (primary):**

> تطبيق أذكار: رفيقك اليومي لأذكار الصباح والمساء، والتسبيح، ومواقيت الصلاة.
>
> يصمم هذا التطبيق ليعمل بالكامل دون اتصال بالإنترنت: تفتحه وتجد أذكارك ومواقيت
> الصلاة جاهزة في أي وقت وفي أي مكان - في السفر، وفي الطائرة، وحيث تكون الإشارة
> ضعيفة.
>
> - أذكار الصباح والمساء والأذكار بعد الصلاة، منظمة وقابلة للتخصيص، مع عدّاد
>   تسبيح سهل الاستخدام.
> - مواقيت الصلاة مع عدّاد تنازلي للصلاة القادمة، محسوبة حسب مدينتك وتوقيتك
>   المحلي (بدون إذن الموقع).
> - تذكيرات لا تحتاج إلى حساب: تُجرى على جهازك نفسه، فبياناتك لا تغادر هاتفك.
> - مزامنة اختيارية بين أجهزتك عبر حسابك إن أردت، مع بقاء كل شيء مشفّرا على
>   جهازك.
> - متوفر بالعربية والإنجليزية.
>
> الخصوصية أولوية: لا نجمع بياناتك الشخصية ولا نتتبعك، وكل بياناتك مشفّرة داخل
> هاتفك. يعمل التطبيق بدون إنترنت، فلا حاجة لأي أذونات غير ضرورية.
>
> ملاحظة: التطبيق أداة تذكير؛ تحقق دائما من تحديد مواقيت الصلاة وفق مصدر معتمد
> لديك لضبط دقتها حسب مدينتك.

**English (secondary):**

> Athkar is a simple, beautiful companion for your daily adhkar (morning and
> evening remembrances), tasbeeh counter, and prayer times.
>
> Designed to be fully offline-first: open it and your adhkar and prayer times
> are ready - on flights, in low-signal areas, anywhere.
>
> - Morning/evening and post-prayer adhkar, organized and customizable, with an
>   easy tasbeeh counter.
> - Prayer times with a countdown to the next prayer, calculated for your city
>   and local timezone (no location permission required).
> - Reminders run on your device, no account needed; your data never leaves your
>   phone unless you choose to sync across your own devices.
> - Optional sync across your devices behind your account; everything on-device
>   is encrypted.
> - Arabic and English.
>
> Privacy first: we do not collect personal data and do not track you. Your data
> is encrypted and stored on your device. Because the app works offline, it asks
> for no unnecessary permissions.
>
> Note: Athkar is a reminder tool. Please verify prayer times against a trusted
> source for your location.

### 2.4 Keywords (App Store, ≤ 100 characters, comma-separated, no spaces)

`dhikr,adkar,صلاة,prayer,tasbeeh,تسبيح,islam,muslim,adhan,سبحة,qibla,prayer times`

(Trim to ≤ 100 ASCII-equivalent characters at submission; prune low-value terms in review.)

### 2.5 Screenshots plan

| Purpose | Device size | Spec | Content |
|---|---|---|---|
| 1st (hero) | 6.7" (1290×2796 pt) / 6.1" | PNG, no alpha, app UI; text ≤ 1/3 height | "Morning adhkar list" home screen, Arabic |
| 2nd | same | PNG | Tasbeeh counter mid-state |
| 3rd | same | PNG | Prayer times screen with countdown to next prayer |
| 4th | same | PNG | Reminders setup screen |
| 5th | same | PNG | Offline banner / sync toggle (privacy story) |
| 6th (optional) | iPad 12.9" (2048×2732) | PNG | Same screens on iPad |

Rules: every screenshot must reflect the **current submitted build**; no foreshadowing upcoming
features; include at least one per supported language where practical; avoid charts/tables;
keep Arabic RTL scripts legible; no device frames required.

### 2.6 Privacy policy URL (placeholder)

- URL placeholder: `https://athkar.app/privacy` (to be finalized; the URL must resolve before first
  submission and be stable in `Info.plist` (`Privacy - Policy URL` usage) and the store form).
- Policy content asserts: no account required; data stays on-device; optional sync data encrypted in
  transit and at rest; no third-party tracking; data retained on-device only and deleted with the
  app; how to request deletion of synced account data.

### 2.7 App Privacy answers (App Store Connect)

| Question | Answer | Rationale |
|---|---|---|
| Does the app collect data? | **Yes, minimum subset** — but **no PII** and none linked to identity | Where any platform/crash SDK runs (Sentry/Crashlytics) or push is used, the honest declaration is required. |
| Tracking | **Not tracking** | No cross-app/ads tracking; no IDFA/AAID use. |
| Data collected — Diagnostics (Crash Data) | **Collected, not linked to identity** | Symbolicated crashes with `install_uuid` (pseudonymous), 90-day retention. |
| Data collected — Diagnostics (Performance Data) | **Collected, not linked to identity** | Mobile perf/ANR metrics per §4 of `observability.md`. |
| Data collected — User Content (Notifications) | **Collected, not linked to identity** | Push sends only the **user-generated** reminder content they scheduled (adhkar reminder); transmitted via FCM/APNs, not stored. |
| Anything else | No | No contacts, location, payment card, browsing, identifiers, or other categories. |

Answering rule (matches `release-plan.md` no-PII rule and MASVS-DATA-2): **declare exactly what is
collected; nothing more.** If an SDK is later removed, remove the declaration in the same release.

---

## 3. Play Store listing

### 3.1 App name & descriptions

- **App name (Android):** `Athkar — Dhikr & Prayer` (localized ar: `أذكار`).
- **Short description** (80 chars max): same as §2.2.
- **Full description:** same text as §2.3 (Arabic primary, English secondary). Play renders the
  Locale-formatted description; keep the two language variants as separate locale entries.

### 3.2 Feature graphic

| Field | Spec |
|---|---|
| Dimensions | **1024 × 500 px**, PNG or JPG, no alpha channel, < 1 MB |
| Content | Brand mark + tagline "Your adhkar, always with you."; no device mockups with app-content; margins safe-zone |
| Localization | One localized graphic per supported locale if text appears in it |

### 3.3 Screenshots (Play Store)

| Spec | Requirement |
|---|---|
| Count | 2–8 per device type; submit 4 phone + 2 tablet minimum |
| Phone | Phone screenshots; min width 320 px, max 3840 px; aspect 16:9 or 9:16 (portrait recommended) |
| Tablet | Tablet screenshots; min width 320 px; aspect same rules |
| Format | PNG or JPEG; no alpha for JPG |
| Content | Same screenshot set as §2.5 (mirror of the iOS set, re-rendered for Android) |
| App icon | 512 × 512 px, 32-bit PNG, no alpha, rounds into Play-style shapes |

### 3.4 Play Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share personal data? | No personal data shared; minimum non-PII diagnostics (crash/performance, pseudonymous `install_uuid`) collected for app functionality |
| Data types shared with third parties | None |
| Data encrypted in transit | Yes (TLS 1.3, pinned) |
| Data encrypted at rest | Yes (SQLCipher AES-256-GCM, keys in Keystore/Keychain) |
| Can users request data deletion? | Yes — delete account/sync data server-side; on-device data deleted with the app |
| Data processed for advertising/tracking | No |
| Emergency/Family-sharing exceptions | None declared |

---

## 4. Signing & provisioning

### 4.1 Play App Signing (Android)

- Enroll in **Play App Signing** at first AAB upload. The Play-managed **app signing key** signs the
  AAB into APKs; the developer-side **upload key** signs only the upload.
- Upload key: generated once, stored in a **hardware-backed CI keystore** (`scripts/ci/keystore/`),
  passphrase in the secret manager, access restricted to the release service account
  (`secret-rotation-policy.md` §6).
- Rotation/revocation of the upload key: generate a new key, upload the public certificate in Play
  Console, sign the next release with it — end users are unaffected (they validate the Play-managed
  key). The Play-managed key is rotated only via the Console's "request key reset" support flow.
- CI gates: `apksigner verify --verbose` on every signed artifact (threat model §3 + MASVS-CODE-2).

### 4.2 Apple signing (App Store)

- **Distribution certificate**: one CI-managed Apple Distribution certificate, fingerprint-pinned in
  the pipeline (`secret-rotation-policy.md` §6). All builds sign with exactly this cert.
- **Provisioning profile**: an App Store distribution profile, regenerated when the cert rotates or
  a capability list changes; stored as an encrypted secret in CI.
- **Cert/key rotation** is a rehearsed manual procedure, never a background job
  (`secret-rotation-policy.md` §6).
- CI gates: `codesign --verify` and `codesign -d` capabilities audit on the archive; `xcrun
  altool`/`notarytool` notarization inside the pipeline.

### 4.3 App IDs & capabilities

**Apple** — App ID `com.athkar.app` with these **entitlements**:

| Capability / entitlement | Value / purpose |
|---|---|
| **Push Notifications** | `aps-environment` explicit for release, development for debug; required for wake-up-hint pushes (`architecture.md`; push is only a nudge) |
| **Keychain sharing** | `keychain-access-groups = com.athkar.app.*` — shared between app and any app extensions, and future SyncKit-x storage |
| **Associated Domains** | `applinks:athkar.app` (Universal Links per `threat-model-stride.md` §5) |
| **In-App Purchase** | `com.apple.developer.in-app-purchases` — if the optional "author/benefit/donation" IAP ships (renewable subscription of the one-off gifting class); receipt validated server-side (`runbooks.md` RB-08) |
| App Groups (optional) | Only if a Widget extension ships |
| Data Protection | App sandbox uses Data Protection default class; `NSURLIsExcludedFromBackupKey` on the data dir (`masvs-l2-checklist.md` MASVS-STORAGE-6) |

**Android** — Min SDK as chosen at build time (target current API); **no location, no storage, no
contacts, no camera, no SMS** permission. Minimal set:

| Permission | Rationale |
|---|---|
| `android.permission.INTERNET` | sync/auth when online (offline-first app works without it) |
| `android.permission.POST_NOTIFICATIONS` | runtime prompt for reminder notifications (Android 13+); framed as a user-opt-in via the priming UX (§5) |
| `android.permission.SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (policy-gated) | exact-time reminder scheduling; user-initiated feature → acceptable under Play policy; always check Play Console policy contact before release |
| `android.permission.VIBRATE` | reminder vibration |
| (no `WAKE_LOCK` unless a widget needs it) | keep the manifest lean |

IAP (Android): Google Play Billing Library, receipts verified server-side; subscribe-or-donation
policy-compliant; excluded from Android backups (`allowBackup=false` +
`dataExtractionRules`, `masvs-l2-checklist.md` MASVS-STORAGE-6).

---

## 5. App & policy compliance notes

These are the specific things App Review / Play policy reviewers will probe for an offline-first
app; address them in the submission notes text.

### 5.1 Offline-first (both stores)

- State assertively in the long description: the app's core works **with no network**; a network is
  used only for optional sync and server-issued prayer data. The reviewer should not be able to
  claim the app is "unusable offline".
- Demo path: after setup, put the device in Airplane Mode and walk the core flows (adhkar, counter,
  reminders, prayer times from on-device cache). Include this in the submission-review "functional
  test" note.

### 5.2 No unnecessary permissions (both stores)

- The permission ceiling (§4.3) is already the *minimum*. Justify each in the review notes.
- `POST_NOTIFICATIONS` is requested only at the moment of value (user schedules a reminder) via a
  **priming dialog** first ("We'll remind you at Fajr if you allow notifications") so the OS dialog
  is not the first thing users see — this is both a conversion and a policy consideration.
- On Android, exact-alarm permissions are declared with the "appointment reminder" justification
  and only used for user-created reminders (Play restrict / appeal flow prepared in advance).

### 5.3 Notification priming

- Rules: notification requests only after a user action (scheduling a reminder); never at cold
  launch; a contextual pre-permission screen; notifications must be dismissible and
  settings-manageable (per-category toggles). This primes acceptance and satisfies the "ask in
  context" App Review guidance.

### 5.4 No sideloading / no private API (both stores)

- No sideloading channels advertised (no APK links, no "download the app" references to non-store
  sources) — compliance with Play's App Integrity and App Review's "no sideloading" flag.
- No private/undocumented APIs, no hot-code-patching runtime inside the app, no dynamic script
  loading from the network (the control-plane block is data config only, never code — state this
  explicitly). This keeps the review and MASVS-CODE posture clean.
- The app's runtime integrity checks (root detection, signature hash) must be **non-blocking** to
  legitimate reviewers and sandboxes (`masvs-l2-checklist.md` MASVS-RESILIENCE-1/3).

### 5.5 Miscellaneous reviewer traps that this project already sidesteps

| Item | Position |
|---|---|
| Data usage disclosures | Covered by §2.7 / §3.4; no third-party cookies; no ads SDK present |
| Account deprovisioning | Optional account deletion path exists server-side and is documented |
| IAP entitlements | Restore/purchase flows testable; receipts verified server side (RB-08) |
| Rich content | Offline content is bundled; no external WebView required (MASVS-PLATFORM-2/3) |
| Accessibility | Localized VoiceOver/TalkBack labels on all interactive elements — include a11y pass in the screenshot/app-review script |

---

## 6. CI/CD pipeline (GitHub Actions)

Pipeline goal: from a tagged `main` commit to **signed AAB/IPA in the stores with gradual
rollout**, with every artifact symbolicated and gated.

### 6.1 Workflow summary (`.github/workflows/release.yml`)

```yaml
name: release
on:
  push:
    tags: ["v*"]               # trigger: release tag `vMAJOR.MINOR.PATCH`
  workflow_dispatch: {}         # manual: also available

jobs:
  validate:                     # gate 1 – shared by both platforms
    runs-on: ubuntu-latest
    steps:
      - checks-out repo
      - version check: regex `^[0-9]+\.[0-9]+\.[0-9]+$` + SemVer bump rule (§1.1 release-plan)
      - analytics schema validation: validate generated events vs contracts/analytics/events.schema.json (§6.3 observability)
      - secret scan: gitleaks (fail on secret) + dependency audit (OSV/Dependabot): blocked if fix unavailable
      - contract tests: both clients vs contracts/openapi/* (schema diff + generated-client tests)
      - flag-gating lint: every UI-reachable intent is behind a registered flag (release-plan §3.1)

  android:
    needs: validate
    runs-on: ubuntu-latest
    steps:
      - setup JDK
      - gradle :app:assembleRelease :app:bundleRelease          # signed AAB + APK (keystore from secret store)
      - unit + integration tests (:core:test, :app:test)        # gate 2: all green
      - lint: detekt (incl. ApiDetektRule) + ktlint + :app:lint # gate 3: zero violations
      - security scan: apksigner verify, exported-audit, R8 mapping present
      - upload mapping + symbols: crashlytics upload / sentry-cli upload-proguard  # before promotion (§7.1 release-plan)
      - verify symbol presence in symbol server (scripts/ci/verify-symbols.sh)    # hard gate
      - artifact build report (versionCode/build number, checksums)

  ios:
    needs: validate
    runs-on: macos-14
    steps:
      - setup Xcode + ruby (fastlane)
      - unit + integration tests (XCTest)
      - lint: swiftlint + architecture checks (Mosync/Custom rule)
      - xcodebuild archive + codesign --verify + notarytool    # signed+notarized IPA
      - upload dSYM(s): sentry-cli / crashlytics upload-symbols  # gate (§7.1)
      - verify symbol presence
      - produce IPA artifact + build report

  deploy-android:
    needs: android
    runs-on: ubuntu-latest
    steps:
      - fastlane lane :internal_android      # upload AAB to Play internal track
      - rollout gate check (script): snapshot crash-free/ANR/conversion for prior release to authorize
      - fastlane lane :promote_android        # internal → production staged rollout (1%–100% per §6.2 release-plan)

  deploy-ios:
    needs: ios
    runs-on: macos-14
    steps:
      - fastlane lane :testflight             # internal+external TestFlight (canary soak)
      - rollout gate check (script): same 3 gates for canary cohort
      - fastlane lane :appstore               # App Store Connect submission + phased release percent API
```

### 6.2 Sequence and gates (matches `release-plan.md` §5)

```
build → unit/integration tests → lint → contract tests → security scan → upload dSYM/mapping
→ signed AAB/IPA → store upload (fastlane / ASC API / Play API) → gradual rollout gates
                                        │
    halt on: crash-free < 99.5% | ANR > 0.47% | conversion drop > 3 pp   (evaluated per stage)
```

Pipeline rules:

- **Signed artifacts only.** Gradle signs with the CI keystore from the secret manager; Xcode signs
  with the pinned Distribution cert + profile from encrypted secrets. Secrets never appear in logs.
- **Symbols before promotion.** dSYM/mapping upload is a hard gate before any store track is
  promoted (a store build without symbols is rejected by `verify-symbols.sh`).
- **Rollout is API-driven.** Android promotion sets the Play staged-rollout percentage via the Play
  Developer API; iOS uses the App Store Connect phased-release API for the 1/5/20/50/100 % steps
  (`release-plan.md` §6.1). The **rollout gate script** re-reads `observability.md` §4 panels
  between steps and refuses a step-up that fails a gate (the same logic that halts automatically at
  runtime).
- **No secrets in the repo.** All keys and tokens (keystore passphrase, Apple API key, Play JSON
  service account, Sentry/Crashlytics tokens, APNs key) live in secret managers, referenced only by
  name in the workflow.
- **Reproducibility:** each run records the commit SHA, tag, build number, tool versions, and
  dependency lockfile hash, published as a "build report" artifact.

### 6.3 Tools used in the pipeline

| Step | Tool |
|---|---|
| Build | Gradle (Android) / `xcodebuild` + fastlane (iOS) |
| Unit/integration tests | JUnit 5 + coroutines-test / XCTest (see `architecture.md`) |
| Lint | Detekt + ktlint / SwiftLint (+ `ApiDetektRule`) |
| Contract tests | schema-diff + generated client tests (`contracts/openapi/*`) |
| Security scan | gitleaks, OSV/Dependabot, `apksigner`/`codesign --verify`, exported-audit script |
| Symbol upload | Sentry CLI / Firebase Crashlytics Gradle plugin + upload-symbols |
| Store upload | fastlane (`deliver`,`supply`,`pilot`,`promote_app`) + App Store Connect API + Play Developer API |
| Rollout gates | CI script reading `observability.md` §4 panels (Firebase/BigQuery/Grafana queries) |

---

## Appendix: submission checklist

Before each store submission, verify:

- [ ] Version and build number fresh (not reused) per `release-plan.md` §1.
- [ ] Every new feature behind a flag; flags listed in the build report.
- [ ] dSYM/mapping present in the symbol server for this exact build UUID.
- [ ] App Privacy (iOS) / Data safety (Play) answers match what the build actually does (§2.7/§3.4).
- [ ] Permissions diff reviewed: no new permission without a documented justification (§4.3/§5.2).
- [ ] Screenshots re-rendered from this build; language variants in place (§2.5/§3.3).
- [ ] Privacy policy URL live and matching the form answers (§2.6).
- [ ] In-app purchase test receipt + restore path verified end-to-end (RB-08).
- [ ] Offline demo scripted for the reviewer; Airplane-Mode walkthrough recorded (§5.1).
- [ ] Notification priming UX in place; no cold-launch permission prompt (§5.3).
- [ ] No sideloading refs; no network-loaded code; control-plane is data-only (§5.4).
- [ ] Release notes (Arabic + English) ready; changelog updated in-app and in the store.
- [ ] Rollout plan staged: canary → 1% → 5% → 20% → 50% → 100% with gates armed.
- [ ] On-call aware; runbook owner assigned for the release window.