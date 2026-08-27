# Athkar — Release & Rollout Plan

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: Release Engineering (with Security Engineering review)
Status: Active

This document defines how Athkar versions, branches, flags, builds, rolls out, monitors, and
rolls back every production release. Consistent with the other operations docs, every number
below states **how it is measured and with which tool**; no threshold is aspirational.

Related documents: `docs/security/secret-rotation-policy.md` (signing-key rotation and
TLS-pin kill switch), `docs/security/threat-model-stride.md`, `docs/security/masvs-l2-checklist.md`,
`docs/operations/observability.md` (metrics definitions and dashboard), `docs/operations/runbooks.md`
(incident handling), `docs/architecture/architecture.md` (sync engine, outbox, tombstones).

---

## Table of contents

1. [Versioning](#1-versioning)
2. [Branching & release model](#2-branching--release-model)
3. [Feature flags](#3-feature-flags)
4. [Emergency kill switch](#4-emergency-kill-switch)
5. [Release lifecycle & gates](#5-release-lifecycle--gates)
6. [Gradual rollout](#6-gradual-rollout)
7. [Crash symbolication & monitoring](#7-crash-symbolication--monitoring)
8. [Rollback plan](#8-rollback-plan)
9. [Appendix: every number, its measurement, and its tool](#9-appendix-every-number-its-measurement-and-its-tool)

---

## 1. Versioning

### 1.1 Semantic versioning (MAJOR.MINOR.PATCH)

All user-facing versions are **SemVer 2.0.0**: `MAJOR.MINOR.PATCH`.

| Segment | Bump when | Examples |
|---|---|---|
| **MAJOR** | Breaking change for users or for the data/sync protocol: a migration that invalidates local data, a removed feature, an incompatible sync delta format, or an OAuth flow change that forces re-authentication for everyone. | `2.0.0` (sync protocol v2), `3.0.0` (drop a feature) |
| **MINOR** | A new, non-breaking feature or improvement (always shipped behind a feature flag per §3). Includes backward-compatible additions to the local DB schema and sync payloads. | `2.3.0` (adds salaah/jamaah mode) |
| **PATCH** | Bug fixes, performance fixes, and resource changes with no new behavior. No user-facing feature reintroduction here. | `2.3.4` |

Rules enforced in CI:

- The tag/version string must match `^[0-9]+\\.[0-9]+\\.[0-9]+$`.
- A PR touching the sync protocol or the local DB schema (`contracts/openapi/*`, `docs/db/schema.md`,
  `android/core/build.gradle.kts`) must bump MINOR at minimum and cannot ship MAJOR changes under a
  MINOR version. Measurement: a GitHub Actions version-compatibility check that compares the change
  set against an allow-list and fails the build otherwise (tool: GitHub Actions script over the diff).

### 1.2 Auto-incrementing build number

- **Android:** `versionCode` is a monotonically increasing integer, set to the CI-provided build
  number. `versionName` is `MAJOR.MINOR.PATCH`. The Play Console rejects a build whose `versionCode`
  is not strictly greater than the last one; CI fails if the build number is not a fresh integer.
  Measurement: `./gradlew :app:printVersionCode` output compared against the last published
  `versionCode` from the Play Developer API (tool: Gradle + `bump.sh` in `scripts/build/`).
- **iOS:** `CFBundleVersion` is the same monotonically increasing integer build number;
  `CFBundleShortVersionString` is `MAJOR.MINOR.PATCH`. App Store Connect requires a unique
  `CFBundleVersion` per submission; CI fails if it collides (tool: App Store Connect API
  `appStoreVersions` lookup + `fastlane lane :bump_build`).
- **Single source of truth:** the numeric build number is minted **once per pipeline run** by the CI
  service (GitHub Actions run number or a dedicated counter service) and written into
  `android/app/build.gradle.kts` and the Xcode project by `scripts/build/generate-version.sh` before
  compilation. Both platforms in the same release **share** the same build number whenever a release
  ships both simultaneously.
- Build numbers are **never reused**, even after a rollback; a re-release gets a fresh number.

---

## 2. Branching & release model

Trunk-based development:

- **One long-lived branch: `main`** (trunk). It is always releasable.
- Developers work on **short-lived topic branches** (hours to ~2 days) sourced from `main`, merged
  via pull request. No long-lived feature branches, no release branches.
- Every merge to `main` must:
  1. pass CI: unit + integration tests, lint, contract tests, security scan (see §5),
  2. be reviewed by 1 owner per affected module,
  3. ship behind a feature flag if it introduces new behavior (§3),
  4. update the changelog at the top level of `main`.
- A **release is a tag** (`vMAJOR.MINOR.PATCH`) taken from a specific `main` commit; CI builds the
  tagged commit. "Cutting a release" never forks the code, it freezes an artifact.
- **Hotfix rule:** fixes land on `main` (usually as a revert or small patch) and are released as the
  next PATCH. There is no separate patch branch. If a hotfix must ship fast, the gate is an
  expedited merge directly to `main` followed by a tagged build — never a divergent branch.

Measurement / how-to: branch age and PR merge cadence are tracked from GitHub API events
(tool: GitHub Actions + a weekly `branch-age` report); release tags are validated by a git hook
`hooks/pre-tag` that verifies `main` is at the tagged commit (tool: `git`).

---

## 3. Feature flags

**Every new feature ships behind a feature flag.** A feature without a flag is not releasable.

### 3.1 Registry and defaults

- All flags live in a single registry: `FeatureFlags.kt` (Android, `android/app/`) and
  `FeatureFlags.swift` (iOS). A new feature **must** register a flag there; a CI lint (tool:
  `ApiDetektRule` extension in `scripts/ci/`) fails the build if any UI-navigable view or domain
  intent is not gated by at least one flag from the registry.
- **Default is OFF.** New and risky features start disabled for 100% of users; they are enabled only
  incrementally through rollout (§6).
- Flags are typed (`BooleanFeatureFlag`, `PercentageFeatureFlag` for staged enablement,
  `EnumFeatureFlag`) with an explicit, single allocation of bucketing randomness.

### 3.2 Delivery and targeting

- Flags are delivered through two channels, both **signed and versioned**:
  1. **Bundled defaults** in the binary (used offline and as the fallback).
  2. **Remote config overlay** — served by the control plane (`GET /v1/control-plane`, signed per §4)
     and mirrored in Firebase Remote Config / the backend equivalents. Offline clients keep running
     the last known-good signed value.
- Targeting is by stable `install_uuid` (app-scoped random UUID, not an OS device identifier) hash,
  an `app_version` range, a rollout percentage, and/or individual dev/test accounts via an
  allow-list.
- **Distinguishable identity:** the active flag set is a property of every crash report and every
  analytics event (`feature_flags`), so each crash/event is linked to the exact flag combination
  that produced it (see §7 and `docs/operations/observability.md`).

### 3.3 Flag hygiene

- A flag that has reached 100% and been stable for 2 weeks must either be removed (code path made
  unconditional) or get an explicit waiver with a removal date. Flag debt is reviewed at every
  release. Measurement: a CI script lists flags that are ON at 100% for two consecutive releases
  and opens a removal ticket (tool: GitHub Actions + flag registry diff).

---

## 4. Emergency kill switch

Goal: **disable a degradable feature (or all remote/network functions) fleet-wide in under 5
minutes without shipping a release.**

### 4.1 Design

- Two layered, redundant channels deliver the "active" values, each signed with a server key whose
  public part the app verifies at runtime (key held in HSM/KMS, see `secret-rotation-policy.md`):
  1. **Control-plane piggyback.** Every sync round-trip and every app-foreground network request
     carries a `Control-Plane-Version` header; responses embed the signed control block. Clients
     fetch a fresh block on foreground and on every sync; the signed block has a **120-second TTL**.
  2. **Firebase Remote Config** (`remote-config` keys below) fetched with a short minimum interval,
     as a second independent delivery path.
- The control block contains, at minimum:
  - `app.kill_switch` — `false` (normal), `true` (disable gated remote/network functionality), plus
    an optional severity (`degrade` vs `full`);
  - `app.min_supported_version` / `app.min_supported_build` — server-side minimum, same concept;
  - `tls.pins.override` (see `secret-rotation-policy.md` §3);
  - per-feature overrides (`feature.<name> = off | percentage`).
- The app evaluates the control block **before every network-touching or feature-critical action**,
  caches the last valid signed value, and applies changes within one evaluation cycle. Because
  evaluation happens on every foreground and every sync and sync runs on a schedule plus network
  reachability events, the **fleet-wide effective latency target is < 5 minutes** from the moment a
  new block is published for any device that comes online within that window.

### 4.2 Offline limitation (honest statement)

An offline-first app that works with no network cannot be remotely reached while offline. The kill
switch applies to **network and remotely-gated functionality** (sync, control-plane-dependent
features, pin overrides). For a device that stays fully offline, the fallback guarantees are:
the **server refuses** traffic from build numbers below `app.min_supported_build` at the API gateway
(server-side enforcement), and kill-switched features that are purely local are gated by the last
cached signed block. A local-only feature that must be neutralized remotely still requires a release.

### 4.3 Activation procedure

See `docs/operations/runbooks.md` → "Remote-config kill-switch activation". In one sentence: confirm
the incident, publish the signed control block (`app.kill_switch = true`) plus the matching
Firebase Remote Config value, observe the sync/network metrics on the dashboard, then issue the
mitigation. The runbook also covers deactivation.

Measurement / what tool: kill-switch propagation is measured as the percentage of devices that
applied the block, from the `remote_config_applied` analytics event (tool: Firebase Analytics /
`docs/operations/observability.md` event dictionary); dashboard goal is **> 95% of online fleet in
< 5 minutes** (tool: Grafana panel over event timestamps).

---

## 5. Release lifecycle & gates

A release candidate proceeds through the pipeline **and** gates. Order:

1. `main` reaches a tagged commit (`vX.Y.Z`) → CI runs the full pipeline for both platforms
   (`docs/operations/store-assets.md` §7 defines the exact GitHub Actions steps):
   build → unit/integration tests → lint → contract tests → security scan → dSYM/mapping upload →
   signed AAB/IPA.
2. **Internal/external canary.** Android: internal testing track at 100% of internal testers +
   closed testing cohort. iOS: TestFlight internal group (≤ 100) + external testers (small beta
   cohort). Canary must sit green for a configurable soak (default 24 h) on every §6 gate metric.
3. **Play staged rollout / App Store phased release / TestFlight→App Store transition.**

| Gate | Who decides | Tool | Blocks release if |
|---|---|---|---|
| Build green (all lanes) | CI | GitHub Actions | Any lane fails |
| Lint (incl. architecture + flag gating rules) | CI | Detekt/ktlint/SwiftLint + `ApiDetektRule` | New violations |
| Contract tests (client vs `contracts/openapi/*`) | CI | schema-diff + generated client tests | Payload drift or breaking protocol change under MAJOR/MINOR mismatch |
| Security scans | CI | gitleaks secret scan, dependency audit (OSV/Dependabot), signature/`apksigner`/`codesign --verify` | Any critical/high finding |
| dSYM/mapping upload | CI | Sentry CLI / Firebase Crashlytics Gradle plugin | Upload missing → release blocked (see §7.1) |
| Store review (human) | App Store / Play | iTC / Play Console | Policy rejection (see `store-assets.md` §6) |

Release checklist (run before tag):

- [ ] Changelog updated; version bumped by SemVer rule (§1.1).
- [ ] Every new feature has a flag (§3); no un-flagged UI reachable.
- [ ] `contracts/openapi/*` in sync with both clients (DRY contract tests pass).
- [ ] Secrets scan clean; no new credentials in the tree.
- [ ] dSYMs/mapping snippet verified in the crash tooling for the previous build.
- [ ] `app.min_supported_build` on the server is ≤ this build.
- [ ] 90-day crash backlog reviewed: no un-triaged new crashes from §7.3.

---

## 6. Gradual rollout

### 6.1 Stages

Play "staged rollout" and an equivalent percentage gate on our side: **1% → 5% → 20% → 50% → 100%**
over **~5 days** (each stage is a *command*, not a fixed clock step — the stage advances only when
the §6.2 gates pass for the required observation window).

| Stage | Rolling out to | Hold (observation window) | Cumulative time (nominal) |
|---|---|---|---|
| 0 — Canary | internal testers + closed cohort | 24 h | Day 0 |
| 1 | 1 % of users | ≥ 12 h | Day 0.5–1 |
| 2 | 5 % | ≥ 12 h | Day 1–2 |
| 3 | 20 % | ≥ 20 h | Day 2–3.5 |
| 4 | 50 % | ≥ 20 h | Day 3.5–4.5 |
| 5 | 100 % | — | Day 5 |

Rollout commands are executed through the store consoles and mirrored in our internal flag
targeting for features that are still percentage-flagged:
- **Android:** Play Console staged rollout (set the percentage per release) or the Play Developer
  API `tracks.update` with a fraction; the AAB is always fully published to the *internal* track,
  while *production* holds the percentage.
- **iOS:** App Store Connect **phased release** supports step percentages
  (1/5/10/20/50/100%); we map to the closest available steps for the same effect and advance via
  the App Store Connect API once the §6.2 gate passes. Immediately after the staged rollout
  completes we keep phase control until the 100 % milestone clears.
- **Feature flags:** percentage-flagged features inside the binary follow the *same* percentages via
  the control-plane block, so the risky feature and the release ramp in lockstep.

### 6.2 Automated rollout gates (halt rollout automatically)

The rollout **halts automatically** when any gate metric fails. A halt is: CI/operations automation
reverts the rollout to the last proven stage (≤ previous percentage or back to canary), fires a
page, and opens an incident (`docs/operations/runbooks.md` → "Incident response process"). Explicit
human sign-off is required to advance a halted stage.

| Gate | Halt condition | Metric source (how to measure) | Tool |
|---|---|---|---|
| Crash-free sessions | **< 99.5 %** of sessions in the staged cohort | Crash-free *session* rate, not users; computed from crash + session heartbeat events | Firebase Crashlytics and/or Sentry; dashboard panel (Grafana) |
| ANR rate | **> 0.47 %** of sessions with an ANR | ANR documents aggregated per build; expressed as % of sessions | Firebase Performance / Play Console "ANRs & crashes" + Play Developer API; Sentry ANR Integration; `adb shell dumpsys activity` on lab devices |
| Primary-path conversion | **> 3** percentage-point absolute drop vs baseline | Primary path = launch → adhkar list → `adhkar_session_start` → `adhkar_session_complete`. Conversion = completes / starts. Baseline = same-install 7-day pre-rollout rate (or previous stable version when new installs dominate). Compare staged cohort vs baseline daily. | Firebase Analytics funnels / BigQuery export + Grafana comparator; Sentry custom events as cross-check |

Additional, non-blocking signals monitored at every stage (inform the operator, not a hard stop):
sync latency p95, sync success rate, outbox dead-letter rate, notification delivery rate, push-to-
first-open, and auth failure rate — all defined in `docs/operations/observability.md`.

Evaluation cadence: gate metrics are computed on a **15-minute rolling window** for the staged
cohort; on failure, the automation triggers the halt within one evaluation cycle (≤ 15 min).

How-to-measure note for conversion: cohorting is by `install_uuid` stable bucketing so the 1 %/5 %
stages are disjoint and a user never re-samples across stages; the same bucketing is reused by the
feature-flag targeting tool so the flag audience equals the rollout audience.

### 6.3 Rollout owners

Each stage transition requires two approvals: (1) the automated gate report is green ≥ holding
window, (2) the release engineer rotates the percentage (tool: GitHub Actions
`promote-release.yml` for API-driven stores, or the Play Console/App Store Connect UI) and records
the stage in the release log.

---

## 7. Crash symbolication & monitoring

### 7.1 dSYM / mapping upload on EVERY build

- **Every CI build** (release *and* debug/test) uploads its symbol artifacts:
  - iOS: the Xcode archive **dSYMs** (`.app.dSYM`) uploaded immediately after `xcodebuild archive`,
    via `fastlane lane :upload_symbols` calling `sentry-cli debug-files upload` or the Crashlytics
    upload-symbols tool; a build without a successful upload **fails** the pipeline.
  - Android: the **`mapping.txt`** from R8/proguard (release) is uploaded by the Crashlytics Gradle
    plugin (`crashlyticsUploadDeobsDebugAndroidTest`/release ordering) or `sentry-cli upload-proguard`;
    if obfuscation is disabled, the build is green only if the upload tool reports "no mapping needed"
    explicitly.
- Upload happens **before** the dSYM/IPA/AAB can be ranked for release promotion; a store build with
  missing symbols is a release-blocking defect.

Measurement / what tool: a CI gate that queries the symbol server
(Sentry `files` API / Crashlytics `debug-symbols` endpoint) for the build's UUID after upload
(tool: `fastlane` + shell assertions in `scripts/ci/verify-symbols.sh`).

### 7.2 Every crash linked to version + feature flags

- Crash reports carry: `app_version` (MAJOR.MINOR.PATCH), `build_number`, platform/OS version, and
  the full `feature_flags` set active at crash time (registered events, §3.2).
- The crash pipeline is: symbolicated crash → **issue/signature** grouped by stack fingerprint +
  version family + flag-set; the tooling bucket is Sentry or Crashlytics. Each issue is auto-linked
  to the GitHub issue tracker entry "`[Crashes] <signature>` from `vX.Y.Z`+ flags `<set>`".

### 7.3 Alerting

- **Retention:** crash and symbol data is retained **90 days** in the crash tooling, then purged
  (enforce a data-retention cron; tool: Sentry retention / Crashlytics retention settings + a
  scheduled export of the non-PII triage summary). Analytics events are retained 90 days in raw
  form per `observability.md`.
- **New-crash alert:** any crash **signature** that has not been seen in the prior 90 days and that
  exceeds **0.1 % of sessions per rolling hour** fires an alert **within 1 hour** of the threshold
  being crossed. Implementation: Sentry Alert Rule (event = "New Issue", frequency ≥ 1 per 15 min,
  level warning) or Firebase Crashlytics alerting + a BigQuery/Firestore job that evaluates the
  0.1 %-of-sessions condition hourly (tool: Sentry/Crashlytics + Grafana alert group). The alert is
  routed to on-call (see `runbooks.md`).

---

## 8. Rollback plan

Mobile rollback is about **stopping harm fast** then **correcting the code**. Because installed
clients cannot be downgraded automatically, order of operations matters.

### 8.1 Rollback decision tree (in priority order)

1. **Remote-config/feature-flag kill (§3, §4).** If the fault is feature- or network-scoped and the
   code is present but bad, flip the feature flag OFF or the `app.kill_switch` ON. Effective < 5 min
   for the online fleet. This is the preferred first action — it needs no store action.
2. **Stage rollback (percentage reduction).** If killing the feature is insufficient (e.g., a general
   stability or data-store bug), reduce the staged rollout back to the last proven stage (e.g.,
   50 % → 20 % → 5 % → 1 %) via Play staged rollout / App Store Connect phased-release state / our
   flag targeting. Existing installs keep the binary but exposure narrows.
3. **Halt + unpublish (Android only).** In Play Console, pause the release; new installs/promotions
   stop receiving it. Applies to the *new* cohort only. iOS has **no** unpublish-and-downgrade for
   already-installed users.
4. **Hotfix PATCH.** Ship a new build (`versionCode`/`CFBundleVersion` incremented, SemVer PATCH or
   MINOR as warranted) with the fix; if a store review is needed, request **expedited/emergency
   review**:

   | Store | Fast-path | Expected time |
   |---|---|---|
   | App Store | iTC "App Review contact" + expedited request; TestFlight build already approved reduces risk | hours to ~24 h |
   | Play | Managed/closed-track first is already reviewed; production can be updated via internal track → staged | hours |

5. **Backend rollback.** The Sync/Auth/Notification services are **stateless** with respect to
   client outboxes (clients retry idempotently by UUIDv7 — see `architecture.md`). Server rollback
   is a version revert with zero client impact; the API gateway version gate
   (`app.min_supported_build`) is applied server-side first when we deprecate a client.
6. **Data rollback.** Client data is local-first; the forward-only migration policy means a
   migration bug is repaired by (a) restoring from the local snapshot taken every 200 edits
   (`SYNC_PAGE_SIZE`, keep-on-disk 24 h), or (b) a device re-enrollment/re-sync from one replica, or
   (c) reinstalling the app. Never mutate server tombstones to "undo" a delete. Where a server-side
   correction is required (e.g., a bad flag-default deploy), prefer additive correction over
   destructive rewrite.

### 8.2 Rollback checklist

- [ ] Damage stopped: feature flag OFF / kill switch set / stage reduced (whichever applies first).
- [ ] Incident opened with severity, spread, and affected version (see `runbooks.md`).
- [ ] Gate metrics (crime scene): crash-free, ANR, conversion, sync latency grabbed for the
  post-rollback comparison window.
- [ ] New build minted with **fresh** build number (never reuse) and hotfix/rollback rationale in
  the changelog.
- [ ] dSYM/mapping uploaded for the new build before promotion (§7.1).
- [ ] Rollout re-entered at canary/1 % and re-gated per §6.2.
- [ ] If data was touched: snapshot restored or re-enrollment path verified with the affected
  cohort.
- [ ] Postmortem filed; runbook and this plan updated with what the incident taught.

---

## 9. Appendix: every number, its measurement, and its tool

| Number (threshold/policy) | Measured as | Tool | Where configured |
|---|---|---|---|
| `MAJOR.MINOR.PATCH` semver | Tag regex + version bump rule in CI | GitHub Actions + `git tag` | `.github/workflows/version-check.yml` |
| Monotonic build number | `versionCode` equality vs Play API; `CFBundleVersion` uniqueness vs ASC API | Gradle/`fastlane` + `scripts/build/generate-version.sh` | CI job |
| Trunk-only; no release branches | Branch list + tag validation | GitHub API + `hooks/pre-tag` | Repo settings |
| Flag required per new feature | Detekt/ApiDetektRule extension scanning UI-reachable intents | Detekt | CI lint lane |
| Kill switch effective < 5 min | % of online devices reporting `remote_config_applied` within 5 min | Firebase Analytics + Grafana | `dashboard.grafana.json` panel |
| Rollout 1/5/20/50/100 % (~5 d) | Stage transition log + store-track percentages | Play Developer API / App Store Connect API + `promote-release.yml` | Release automation |
| Crash-free sessions < 99.5 % halts | Crash-free *session* rate, 15-min window, staged cohort | Crashlytics / Sentry + Grafana alert | Alert rule, `runbooks.md` |
| ANR > 0.47 % halts | ANR docs ÷ sessions, 15-min window | Firebase Performance / Play Console ANRs + Sentry ANR | Alert rule |
| Conversion drop > 3 pp halts | completes÷starts, staged vs 7-day baseline | Firebase Analytics funnel / BigQuery + Grafana comparator | Alert rule, `observability.md` |
| dSYM/mapping on every build | Symbol UUID present in symbol server before promotion | Sentry CLI / Crashlytics plugin + `scripts/ci/verify-symbols.sh` | CI release lane |
| Crash ↔ version + flags | Crash extra keys `app_version`, `build_number`, `feature_flags` | Sentry/Crashlytics custom fields | SDK config |
| 90-day crash retention | Purge job on completed retention | Sentry retention / Crashlytics retention cron | Data tools |
| New crash > 0.1 % sessions → alert ≤ 1 h | Signature unseen in 90 d and rate > 0.1 %/h | Sentry/Crashlytics alert + hourly BigQuery job | Alert rule |
| Rollback ≤ 5 min to damage-stop | Time from incident to flag/kill applied | Incident timeline in `runbooks.md` | On-call |