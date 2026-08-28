# Athkar — أذكار

**An offline-first dhikr & prayer-times app for Android and iOS**, built around a shared,
platform-free Kotlin core that handles conflict-free multi-device sync.

<div dir="rtl">

## نبذة عن المشروع

**أذكار** تطبيق للأذكار ومواقيت الصلاة لنظامَي Android و iOS، مبني على مبدأ **العمل دون اتصال أولاً
(Offline-first)**: جميع المزايا — قوائم الأذكار، التذكيرات، مواقيت الصلاة، مركز الإشعارات، الإعدادات —
تعمل بالكامل بدون إنترنت. الشبكة تُستخدم فقط لمزامنة الحالة بين أجهزة المستخدم ولجلب البيانات
الصادرة من الخادم.

يحتوي هذا المستودع على:
- **نواة Kotlin خالصة** (`android/core`) تنفّذ الساعة المنطقية الهجينة (HLC) وهياكل CRDT ومحرّك
  المزامنة — بدون أي اعتماد على Android أو iOS، ومغطّاة باختبارات خصائص (property tests).
- **عقد OpenAPI 3.1** يولّد عملاء الشبكة للمنصتين من مصدر واحد.
- **توثيق هندسي كامل**: قرارات معمارية، مخطط قاعدة البيانات، نمذجة التهديدات STRIDE، قائمة MASVS L2،
  خطة الإصدار، دلائل الاستجابة للحوادث، والمراقبة.

</div>

---

## Status

| Area | State |
|------|-------|
| `android/core` — HLC, CRDT, sync policy, prayer/qibla astronomy (pure Kotlin/JVM) | **Implemented + tested** (35 tests, 0 failures) |
| `android/app` — Compose shell, Hilt DI, Keystore session | **Implemented** — builds a running debug APK |
| `android/data` — Room + SQLCipher, DAOs, repositories | **Implemented** (13 files) |
| `android/sync` — Ktor client, DTOs, WorkManager worker | **Implemented** (5 files) |
| `android/domain` — repository ports, merge use case | **Implemented** (2 files) |
| `android/designsystem` — theme, type scale, tokens | **Implemented** (2 files) |
| `android/feature-athkar` — chapter index, readings, tally, favourites | **Implemented** (2 files) |
| `android/feature-prayer-times` — prayer schedule + qibla compass | **Implemented** (6 files) |
| `android/app` — prayer-time alarms, notification channel, boot/time-change receivers | **Implemented** (3 files) |
| `contracts/openapi` — API contract | **Complete** (10 endpoints) |
| `docs/` — architecture, DB, security, operations | **Complete** (~3,300 lines) |
| `ios/Athkar/*` | **Implemented + tested** — SwiftUI app, 13 tests, 0 failures on a macOS runner |
| `backend/` | Scaffolded — contract-first, implementation pending |

The Android app **assembles and runs**: `assembleDebug` produces a ~35 MB debug APK
(`com.athkar.app.debug`, v1.0.0/1000, minSdk 26, targetSdk 35) with three tabs — adhkar, prayer
times and qibla. It ships the full text of *Hisn al-Muslim* — 132 chapters, 267 readings — alerts at
each prayer time, and works with no network at all. iOS and the backend remain specification-only, so device-to-device sync does not function.

**Test coverage is currently uneven and worth knowing about:** all 35 tests live in `core`. The
`app`, `data`, `sync`, `domain`, and `feature-*` modules have `src/test` and `src/androidTest`
directories wired into the build but **no test files yet**.

---

## Repository layout

```
athkar/
├── android/
│   ├── core/                    ← pure Kotlin/JVM, ZERO Android deps (implemented)
│   │   └── src/main/kotlin/com/athkar/core/
│   │       ├── clock/Hlc.kt              hybrid logical clock (48-bit time + 16-bit counter)
│   │       ├── crdt/Lww.kt               last-writer-wins register
│   │       ├── crdt/LwwMap.kt            LWW-element-map CRDT (settings sync)
│   │       ├── crdt/UuidV7.kt            RFC 9562 time-ordered ids
│   │       ├── domain/AdhkarReminder.kt  shared domain entity + field conflict resolution
│   │       └── sync/                     DeltaCursor, Outbox, SyncPolicy (backoff/tombstone/replica)
│   ├── app/                     composition root — Compose shell, Hilt, Keystore session
│   │       MainActivity.kt, AthkarApplication.kt, security/{SessionManager,
│   │       AndroidKeystoreKeyProvider}.kt, di/AppSecurityModule.kt
│   ├── domain/                  use-case orchestration (pure)
│   │       AdhkarRepository.kt (port), MergeRemoteUse.kt
│   ├── data/                    Room + SQLCipher persistence
│   │       db/{AppDatabase,AthkarDbFactory}.kt, db/dao/{Adhkar,Sync,Notification}Dao.kt,
│   │       db/entity/*, repository/*Impl.kt, di/{Database,Repository}Module.kt
│   ├── sync/                    Ktor client + WorkManager background sync
│   │       network/{SyncApi,NetworkModule}.kt, dto/SyncDtos.kt, worker/AthkarSyncWorker.kt
│   ├── feature-athkar/          adhkar UI — AthkarScreen.kt, AthkarViewModel.kt
│   ├── feature-prayer-times/    prayer schedule, qibla compass, location + method pickers
│   ├── build.gradle.kts         root build + enforcePureDomainLayers check
│   ├── settings.gradle.kts      includes all 7 modules
│   └── gradle/libs.versions.toml   version catalog (single source for all dependencies)
├── ios/Athkar/                  Domain, Data/{GRDB,Sync}, Presentation, Security, Notifications, DeepLinks
├── backend/                     server implementation (contract-first)
├── contracts/openapi/athkar.yaml   single source of truth for both clients
├── scripts/{build,ci,test}/     automation
└── docs/                        architecture, db, security, operations
```

---

## Architecture at a glance

Three layers, with the dependency rule **enforced by the build**, not by convention:

| Layer | Responsibility | May depend on |
|-------|----------------|---------------|
| **Presentation** | Rendering, input, navigation, process restoration | Domain (interfaces), Data (injected) |
| **Domain** | Business rules, entities, CRDT/conflict/sync logic | **Nothing outside itself** |
| **Data** | Room/GRDB persistence, network drivers, token storage | Domain (implements its interfaces) |

Four accepted ADRs (full rationale in [docs/architecture/architecture.md](docs/architecture/architecture.md)):

- **ADR-01 — Three-layer separation.** Domain may never import UI or platform packages.
  **Currently enforced two ways:** Gradle module boundaries (`core` and `domain` declare no Android
  dependency, so those packages aren't on the classpath and *cannot* compile), plus an
  `enforcePureDomainLayers` Gradle task in [`android/build.gradle.kts`](android/build.gradle.kts)
  that scans `:core` and `:domain` sources for denylisted imports and fails `check` on any hit.
  The ADR additionally specifies a Detekt `ApiDetektRule` and an `archTest` bytecode suite for
  transitive leakage — **neither is wired up yet**; the current task is a textual import scan.
- **ADR-02 — Unidirectional data flow.** `UI event → Intent → Reducer/UseCase → immutable State → render`.
  Intents are a `sealed interface`, so an unhandled UI event fails to compile.
- **ADR-03 — Local DB is the single source of truth.** Every screen reads a reactive local Flow
  (Room `Flow` / GRDB `ValueObservation`), never a network response. Budget: **local read < 16 ms**.
- **ADR-04 — Feature modules.** Max 12 modules, each exposing ≤ 10 public symbols, incremental
  build **< 45 s**.

Every number above is tied to a named measurement tool in the ADR document — none is aspirational.

---

## The sync engine

The correctness-critical piece, living in `android/core` as pure, unit-testable policy:

**Outbox with idempotency keys.** Every local write is enqueued with a **UUID v7** idempotency key.
The server returns the same result for any replay, so retries are safe by construction.
Row lifecycle: `PENDING → IN_FLIGHT → DONE | FAILED → (retry) | DEAD_LETTER`.

**Cursor-based delta sync.** The client sends an opaque server-issued cursor; the server returns
≤ 200 changes with a strictly greater server HLC, plus `nextCursor` / `hasMore`. A mid-stream
network failure resumes from the last *decoded* page, never from the start.

**HLC per-field conflict resolution.** Merging is per-field via a Hybrid Logical Clock. The physical
component is **issued by the server**, so a client cannot fabricate competing time — clients only
advance a logical counter. The total order `(hlc, writerId)` makes merges **commutative,
associative, and idempotent**, which the property tests verify across N replicas in random order.
Tombstones defeat all writes; sensitive fields are excluded from automatic LWW and routed to a
manual-merge screen.

**Backoff & dead letter.** Failed operations retry with exponential backoff and full jitter
(1/2/4/8/16 s … capped at 5 min) for up to 8 attempts, then park in the dead-letter queue with an
in-app notification.

**Tombstones & snapshots.** Deletes are logical and retained ≥ 90 days (or 10,000 rows) before
physical purge, so a late offline write cannot resurrect a deleted entity. A snapshot every 200
edits keeps the op-log under 2 MB.

Sequence diagrams for the happy path and three failure paths:
[docs/architecture/mermaid-diagrams.md](docs/architecture/mermaid-diagrams.md).

---

## Local database

A single SQLite file in **WAL mode**, shared conceptually between Room (Android) and GRDB (iOS).
Eight tables: `entities`, `outbox`, `outbox_dead_letter`, `tombstones`, `settings`, `sync_cursor`,
`notifications`, `encrypted_credentials` (references only — secrets live in Keystore/Keychain).

Primary keys are UUID v7 so B-tree inserts stay append-ordered; every column used in a `WHERE`,
`ORDER BY`, or `GROUP BY` is indexed. Full column-level schema:
[docs/db/schema.md](docs/db/schema.md).

---

## API contract

[`contracts/openapi/athkar.yaml`](contracts/openapi/athkar.yaml) (OpenAPI 3.1) is the single source
of truth — both clients are generated from it, and a CI job fails the build on any unannounced
breaking change.

| Group | Endpoints |
|-------|-----------|
| Auth | `POST /auth/login`, `/auth/refresh`, `/auth/logout` — OAuth 2.1 + PKCE (S256), 15-min access tokens, rotating refresh tokens with replay detection |
| Sync | `POST /sync/push`, `GET /sync/changes`, `POST /sync/ack`, `POST /sync/manual-merge` |
| Billing | `POST /billing/validate` — server-side StoreKit 2 / Play Billing 7 receipt validation |
| Notifications | `POST /notifications/register` |
| Config | `GET /config/remote` |

---

## Documentation index

### Architecture
| Document | Contents |
|----------|----------|
| [architecture.md](docs/architecture/architecture.md) | ADRs 01–04 with rationale, measurement tooling, and 10 rejected alternatives |
| [container-diagram.md](docs/architecture/container-diagram.md) | C4 L1 container + L2 component diagrams (Android and iOS) |
| [mermaid-diagrams.md](docs/architecture/mermaid-diagrams.md) | Sync sequence diagrams (happy path + 3 failure paths), component graph, coordinator state machine |

### Data
| Document | Contents |
|----------|----------|
| [schema.md](docs/db/schema.md) | Full SQLite schema, indexes, WAL rationale, Room/GRDB mappings, retention policies |

### Security
| Document | Contents |
|----------|----------|
| [threat-model-stride.md](docs/security/threat-model-stride.md) | STRIDE analysis: device theft, network interception, repackaging, backup leakage, deep-link abuse, inter-app channels |
| [masvs-l2-checklist.md](docs/security/masvs-l2-checklist.md) | OWASP MASVS v2 Level 2 control-by-control checklist with verification notes |
| [secret-rotation-policy.md](docs/security/secret-rotation-policy.md) | Secret inventory, TLS pin rotation (60-day overlap), API/OAuth key rotation, revocation procedure |

### Operations
| Document | Contents |
|----------|----------|
| [release-plan.md](docs/operations/release-plan.md) | Versioning, branching, feature flags, kill switch, release gates, gradual rollout, rollback |
| [runbooks.md](docs/operations/runbooks.md) | Incident response process, generic rollback checklist, per-alert runbooks |
| [observability.md](docs/operations/observability.md) | Telemetry pipeline, distributed tracing, 8-metric operational dashboard, analytics event dictionary |
| [store-assets.md](docs/operations/store-assets.md) | App Store / Play listings, signing & provisioning, compliance notes, CI/CD pipeline |

---

## Building & testing

`android/` is a 7-module Gradle build (AGP 8.7.3, Kotlin 2.2.0, JVM target 17, compileSdk 35).
All dependency versions are pinned in
[`android/gradle/libs.versions.toml`](android/gradle/libs.versions.toml).

The Gradle wrapper is committed, so a JDK 17 and an Android SDK are all you need.

```bash
cd android

./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # all module tests (JUnit 5 unit + property tests in :core)
./gradlew :core:jacocoTestReport   # coverage → core/build/reports/jacoco/test/html/index.html
./gradlew check             # includes enforcePureDomainLayers (layer-purity gate)
```

Install the debug build on a connected device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

APKs are **not committed** — `.gitignore` excludes `build/` and `*.apk`. Build locally with the
command above.

### Test suites

All tests currently live in `core`:

| Suite | Covers |
|-------|--------|
| `CrdtMapPropertyTest` | `LwwMap` associativity, commutativity, idempotence across replicas |
| `ConflictResolverPropertyTest` | Per-field entity merge convergence in random delivery order |
| `OutboxAndCursorTest` | Outbox state machine, backoff schedule, dead-lettering, cursor advancement |

Last recorded run: **35 tests, 0 failures**. Prayer times are verified against published timings
and the qibla against published great-circle bearings for eleven cities. The Android modules have
no tests yet — see Known gaps.

---

## Android stack

| Concern | Choice |
|---------|--------|
| UI | Jetpack Compose (BOM 2024.12.01), Material 3, Navigation Compose |
| DI | Hilt 2.57.1 (KSP) |
| Persistence | Room 2.7.0 over **SQLCipher** (AES-256), WAL journal mode |
| Networking | Ktor 3.0.1 client (OkHttp engine, auth, content negotiation, kotlinx-serialization) |
| Background work | WorkManager 2.10.0 — `AthkarSyncWorker` |
| Security | Android Keystore / StrongBox key provisioning, `androidx.biometric`, `security-crypto` |
| Observability | OpenTelemetry 1.44.1 (API, SDK, OTLP exporter) |
| Test tooling | JUnit 5, Robolectric, MockK, Turbine, Espresso |

The local database is **encrypted at rest**: `AthkarDbFactory` opens Room through a SQLCipher
`SupportFactory` whose 256-bit key comes from hardware-backed Keystore via a `DbKeyProvider`
abstraction, so the Data layer never touches raw key material.

---

## Security posture

- **No secrets on disk in the clear.** Keys live in Android Keystore / iOS Keychain; the database
  stores references only. SharedPreferences/UserDefaults are explicitly rejected.
- **OAuth 2.1 + PKCE (S256)**, no embedded client secrets, 15-minute access tokens, rotating refresh
  tokens with server-side reuse detection that revokes the token family in ~1 second.
- **TLS certificate pinning** on a 60-day rotation cycle with overlap, so a rotation never bricks
  installed clients.
- **Never trust the device clock.** Ordering and merge come from server-issued HLC; local
  `updated_at` is display-only.
- **Push is a hint, not a mechanism.** Silent push delivery is not guaranteed, so correctness rests
  on scheduled sync + the idempotent outbox — a lost or duplicated push is harmless.

See [docs/security/](docs/security/) for the full threat model and MASVS L2 evidence.

---

## Adhkar content

The bundled collection is the complete text of **حصن المسلم** (*Hisn al-Muslim*) by
Sa'id ibn Ali ibn Wahf al-Qahtani — 132 chapters, 267 readings with their repetition counts —
fetched from the book's official API and shipped inside the APK:

| | |
|---|---|
| Source | `https://www.hisnmuslim.com/api/ar/husn_ar.json` |
| Retrieved | 2026-08-28 |
| Bundled at | [`android/data/src/main/assets/athkar_seed.json`](android/data/src/main/assets/athkar_seed.json) (134 KB) |

`AdhkarSeeder` installs it on first launch and replaces it whenever the file's `version` field is
raised by an app update. Only rows it wrote are replaced, and favourites are carried across, so a
content update never destroys what the user marked.

---

## Releasing

Releases are cut by tagging. [`release.yml`](.github/workflows/release.yml) runs the tests, builds a
signed APK, refuses to continue if the APK carries a debug signature, and publishes a GitHub Release
with the APK and its `sha256`.

```bash
git tag v1.0.1
git push origin v1.0.1
```

`versionCode` is derived from the tag as `major*10000 + minor*100 + patch`, so it can never move
backwards between releases.

**Signing.** The keystore is never committed. Locally the build reads
`~/.athkar-signing/keystore.properties`; CI supplies the same four values from repository secrets
(`ATHKAR_KEYSTORE_BASE64`, `ATHKAR_KEYSTORE_PASSWORD`, `ATHKAR_KEY_ALIAS`, `ATHKAR_KEY_PASSWORD`).
When neither is present the build falls back to the debug key and warns loudly — convenient for a
fresh clone, fatal for a release, which is why the workflow checks the signature before publishing.

> **The keystore is irreplaceable.** Lose it and no future build can ever update an installed app;
> every user would have to uninstall and lose their data. Back up `~/.athkar-signing/` somewhere
> durable and offline.

**Update notifications.** Add the repository to
[Obtainium](https://github.com/ImranR98/Obtainium) on the device — it watches the releases, notifies
on each new tag, and installs with one tap.

---

## Known gaps

- **No tests outside `core`.** The `app`, `data`, `sync`, `domain`, and `feature-*` modules have
  empty `src/test` and `src/androidTest` directories.
- **ADR-01 enforcement is partial.** The `enforcePureDomainLayers` task is a textual import scan;
  the Detekt `ApiDetektRule` and `archTest` bytecode suite specified in the ADR are not wired up.
- **No CI on pull requests** — [`release.yml`](.github/workflows/release.yml) runs only on tags.
- **Adhkar content is read-only.** There is no way to add a personal dhikr; the bundled collection
  is the whole corpus.
- **No adhan audio.** Prayer alerts use the device's default alarm tone.
- **Sync has no server** — `BASE_URL` points at `api.athkar.example.com`, which does not exist, so
  the outbox accumulates locally and never drains.
- **No one has looked at the iOS screens.** [`ios/`](ios/README.md) compiles and its tests pass on a
  macOS runner, but the app has never been run on a device or a simulator by a human.
- **The TestFlight workflow has never run** — it needs a paid Apple Developer account.
- **The backend is contract-only.**

---

## License

**Proprietary.** All rights reserved. See the `license` field in
[`contracts/openapi/athkar.yaml`](contracts/openapi/athkar.yaml).
