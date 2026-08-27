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
| `android/core` — HLC, CRDT, sync policy (pure Kotlin/JVM) | **Implemented + tested** (22 tests, 0 failures; ~88% line coverage) |
| `contracts/openapi` — API contract | **Complete** (10 endpoints) |
| `docs/` — architecture, DB, security, operations | **Complete** (~3,300 lines) |
| `android/app`, `data`, `sync`, `feature-*` | Scaffolded — directories reserved, not yet implemented |
| `ios/Athkar/*` | Scaffolded — directories reserved, not yet implemented |
| `backend/` | Scaffolded — contract-first, implementation pending |

This repository is **design-and-core-first**: the hard, correctness-critical part (distributed
merge semantics) is written and proven by tests, and the platform shells are specified in `docs/`
before being built.

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
│   ├── app/                     composition root: DI, navigation, platform glue
│   ├── domain/                  use-case orchestration (pure)
│   ├── data/                    Room persistence + network drivers
│   ├── sync/                    background sync worker / scheduler
│   ├── feature-athkar/          dhikr + adhkar reminders UI
│   └── feature-prayer-times/    prayer times + next-prayer countdown UI
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

- **ADR-01 — Three-layer separation.** Domain may never import UI or platform packages. Enforced
  three ways: Gradle module boundaries (the packages aren't on the classpath, so it *cannot*
  compile), a custom Detekt `ApiDetektRule` that fails the build on denylisted imports, and an
  `archTest` bytecode suite that catches transitive leakage.
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

The `core` module is a standalone Kotlin/JVM project (Kotlin 2.2.0, JVM target 17).

> **Note:** no Gradle wrapper is committed yet — you need **Gradle 8.10+** on your `PATH`.
> Adding `gradlew` is tracked as a known gap below.

```bash
cd android/core

gradle test              # JUnit 5 unit + property tests
gradle jacocoTestReport  # coverage → build/reports/jacoco/test/html/index.html
gradle build             # compile + test + jar
```

Test suites:

| Suite | Covers |
|-------|--------|
| `CrdtMapPropertyTest` | `LwwMap` associativity, commutativity, idempotence across replicas |
| `ConflictResolverPropertyTest` | Per-field entity merge convergence in random delivery order |
| `OutboxAndCursorTest` | Outbox state machine, backoff schedule, dead-lettering, cursor advancement |

Last recorded run: **22 tests, 0 failures**, ~88% line / ~80% instruction coverage.

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

## Known gaps

- No Gradle wrapper (`gradlew`) committed — a fresh clone needs a system Gradle install.
- No CI workflow in `.github/workflows/` yet; the intended pipeline is specified in
  [store-assets.md](docs/operations/store-assets.md#6-cicd-pipeline-github-actions).
- Android app/data/sync/feature modules and the iOS target are directory scaffolds only.
- Backend is contract-only.

---

## License

**Proprietary.** All rights reserved. See the `license` field in
[`contracts/openapi/athkar.yaml`](contracts/openapi/athkar.yaml).
