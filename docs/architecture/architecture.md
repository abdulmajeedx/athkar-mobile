# Athkar — Architecture Decision Record & Rationale

- Status: **Accepted**
- Date: 2026-08
- Scope: Android (Kotlin) + iOS (Swift) offline-first dhikr & prayer-times app

This document records the significant architectural decisions for the Athkar mobile app,
the rationale behind each, and — critically — **how every stated metric is measured and with
what tool**. Every metric below is verifiable in CI or via a monitoring dashboard; none is
aspirational.

---

## Table of contents

1. [Architecture overview](#1-architecture-overview)
2. [ADR-01 Three-layer separation (Presentation / Domain / Data)](#adr-01-three-layer-separation)
3. [ADR-02 Unidirectional data flow](#adr-02-unidirectional-data-flow)
4. [ADR-03 Local DB as the single source of truth](#adr-03-local-db-as-the-single-source-of-truth)
5. [ADR-04 Feature modules](#adr-04-feature-modules)
6. [Offline-first sync engine](#offline-first-sync-engine)
7. [Process-death handling](#process-death-handling)
8. [Rejected alternatives and why](#rejected-alternatives-and-why)

---

## 1. Architecture overview

Athkar is an **offline-first** app. Features (dhikr lists, adhkar reminders, prayer times,
internal notification center, settings) work with **no network**. The network exists only to
converge state across a user's devices and to ingest server-issued facts such as prayer times
and the user's server account state.

Top-level module split (Android) mirrors the platform-agnostic `android/core` layer plus the
feature/data/sync modules already scaffolded in the repo:

```
app                          (thin composition root: DI, navigation, platform glue)
core                         (pure Kotlin, JVM-only, NO Android/UI deps)
   ├── clock   -> Hlc.kt     (hybrid logical clock)
   ├── crdt    -> Lww, LwwMap, UuidV7, conflict resolver
   ├── domain  -> AdhkarReminder, EntityConflictResolver, Field, FieldWrite
   └── sync    -> DeltaCursor, Outbox, SyncPolicy (backoff, tombstone, replica state)
domain                       (use-case orchestration; pure)
data                         (Room persistence + network drivers)
sync                         (background sync worker / scheduler)
feature-athkar               (presentation for dhikr + adhkar reminders)
feature-prayer-times         (presentation for prayer times + next-prayer countdown)
```

The **`core/*` package is deliberately a standalone JVM (Kotlin Multiplatform-ready) unit** with
zero Android or iOS imports. It is the concrete embodiment of ADR-01's Domain layer. The actual
`schema.md` (docs/db/schema.md) maps these types on to Room (SQLite) and GRDB.

---

## ADR-01 Three-layer separation

**Decision.** Code is organized into exactly three layers:

| Layer        | Responsibility                                                                 | May depend on                                                       |
|--------------|--------------------------------------------------------------------------------|---------------------------------------------------------------------|
| **Presentation** | Rendering, user input, navigation, platform UI widgets, process-restoration | Domain (interfaces), Data (via injected repositories/usecases)      |
| **Domain**   | Business rules, use cases, entities, pure CRDT/conflict/sync logic, intents   | **Nothing outside itself** — no UI, no platform, no networking       |
| **Data**     | Persistence (Room/GRDB), network drivers, sync plumbing, auth token storage   | Domain (implements its interfaces)                                  |

**Architecture violation rule.** *Domain must NEVER import any UI or platform package.* This is
not a convention — it is **enforced by a lint rule that fails the build**.

### Lint-rule design

We use **Gradle dependency isolation + a custom lint/archTest**, layered for defense in depth:

1. **Gradle module boundary (primary).** `core` and `domain` are their own Gradle modules whose
   `dependencies {}` block contains **only** `kotlinx-coroutines-core` and test tooling (see the
   published `android/core/build.gradle.kts`). Because Gradle fails the build on any dependency
   that is not declared, importing `android.content`, `javax.swing`, `java.net`, UIKit, etc.,
   **cannot compile** — the artifact does not even contain those packages on the classpath.
   This is the strongest guarantee and costs nothing at runtime.

2. **Custom `ApiDetektRule` (domain layer, Kotlin).** A Detekt rule that runs a compiler/PSI scan
   over all `core`/`domain` sources and flags any `import` whose top-level/named package matches a
   denylist:
   - `android.*`, `androidx.*` (Android platform + Jetpack)
   - `java.net.*`, `java.sql.*`, `javax.*`, `org.json` (networking / platform)
   - `kotlinx.coroutines` **android** variants (e.g. `kotlinx.coroutines.android`)
   - `org.junit.*` leaking into `src/main` (allowed only in `src/test`)
   - Any iOS import (`UIKit`, `Foundation` internals) when Kotlin Multiplatform is enabled later.
   The rule is wired as a **`check`-time task that fails the build** (`failOnError = true`), runs in
   CI on every PR, and produces SARIF annotations pointing at the offending import.

3. **`archTest` JUnit suite (`ArchitectureRule`).** Using the reflection/bytecode reader we already
   ship for property tests, an `@ArchTest` suite inspects compiled classes and asserts e.g.
   `classes in "core.*".shouldNotDependOnClassesThatLiveIn("androidx.*")`. This is a true
   compile-to-class dependency check (not textual) and catches transitive leakage that a naïve
   import scan could miss.

**"How to measure / what tool."**
- The architecture boundary is *enforced*, not measured: the Gradle module split (tool: Gradle
  configuration resolution) and Detekt `ApiDetektRule` (tool: Detekt) both fail the build. CI gate:
  `./gradlew :core:detekt :core:archTest`.
- Layer dependency graph is visualized by **Gradle `dependencies` / Gradle Build Scan** to confirm
  `core`'s graph is empty except for the declared JVM deps.

---

## ADR-02 Unidirectional data flow

**Decision.** All state flows in one direction: **UI event → Intent → Reducer/UseCase → new
immutable State → render**. There is no two-way binding and no screen that mutates shared state
directly.

- **Immutable state.** The UI state for every screen is an immutable, value-typed object
  (Kotlin `data class` / Swift `struct`). Recomposition happens only through state objects that
  are produced by pure reducers, so previews/tests are trivial.
- **Closed set of Intent (sealed type).** Every action a screen can dispatch is a member of a
  `sealed interface` (Kotlin) / `enum`-backed protocol (Swift). The set is closed by the compiler:
  a reducer `when(intent)` has exhaustive branches, so adding a new intent fails to compile until
  it is handled — no unhandled UI events can silently reach business logic.
- **Logging of transitions in debug.** Every state transition is logged in a debug-only
  `StateTransitionLog` — reducers are pure, so we can log `(oldState, intent, newState)` for every
  reducer invocation. A small `Environment.debug` flag (off in release) gates the logging so there
  is zero overhead in production builds. Logs are emitted as structured lines to Logcat/`os_log`
  and captured by the local reproduction harness used by crash reporting (see "crash-free" metric
  below).

**"How to measure / what tool."** Transition logging is validated by a unit test that runs every
reducer with a stub logger and asserts *at least one* log line per intent (tool: JUnit / XCTest).
Performance cost is measured by **Marco lab framework** / **Macrobenchmark** for the debug build to
confirm the log path adds `< 1 ms` per transition (see ADR-03 table for the benchmark tooling).

---

## ADR-03 Local DB as the single source of truth

**Decision.** Every screen reads from a **local reactive Flow** (Room `Flow`/`LiveData`, GRDB
`ValueObservation`), never from a network response directly. Network responses are written into
the local DB first; the UI is a pure projection of local state that updates when the DB changes.

**Why.** This gives instant reads (no latency for UI on the hot path), works 100% offline, and
means the network and the UI can never disagree: there is one source of truth, and the UI is always
consistent with what is persisted. Because reads are reactive, pulling-to-refresh or background sync
updates the UI with no extra wiring.

- **Local read `< 16 ms`** budget to keep the interactive frame budget and scrolling smooth across
  the whole UI.
- The sync engine is the *only* writer to the local DB other than the UI's own outbox enqueue.

**"How to measure / what tool."**

| Metric | Tool |
|--------|------|
| **Local read `< 16 ms`** | **Room `InMemoryRoomDatabase`** micro-benchmark in `androidTest`: time 10,000 reads of a seeded entity table via `MeasureTimeMeasurer`, run on a Pixel reference device in CI benchmark lane; assert p95 < 16 ms. iOS: **`XCTest` + Instruments** Time Profiler around GRDB `ValueObservation` reads. |
| **Reactive read latency** (DB write → Flow emission) | Macrobenchmark trace section (JankStats) marking the emission gap. |
| **Database query plan** | `EXPLAIN QUERY PLAN` on every indexed query; verified automatically by a test that runs the query plan tool (Room `RoomDatabase` query log / `sqlite3` in CI) and fails if a filtered/ordered query is missing an index. |

The DB runs in **WAL mode** (see docs/db/schema.md) so concurrent readers never block the writer
and reads are cheap snapshot transactions — a requirement for the < 16 ms budget.

---

## ADR-04 Feature modules

**Decision.** The app is decomposed into a bounded set of Gradle/Xcode modules.

- **Max 12 modules** at the app level (currently: core, domain, data, sync, feature-athkar,
  feature-prayer-times, app — well within budget). New features must first prove they cannot live
  in an existing module before adding a 13th; the build warns (and docs require) an ARC to exceed 12.
- **Each module exposes a single public API of ≤ 10 exported symbols.** Convention: one public
  `FooApi`/`FooFacade` type per module in a dedicated `*.api` package; everything else is
  `internal`. Enforced by a build-time check that counts public declarations in the compiled output
  (reflection/ArchUnit on the produced jar/framework headers).
- **Incremental build `< 45 s`** for a typical change (edit + compile + unit test of one module).

**"How to measure / what tool."**
- Public-symbol count: **ArchUnit/JUnit `@ArchTest`** counting `public` top-level declarations per
  module (fail if > 10); iOS via a script over `.swiftinterface`.
- Module count: a Gradle task / Ruby script reading the module graph (tool: Gradle / `xcodebuild
  -list`).
- **Incremental build `< 45 s`**: **Gradle `--profile` / Gradle Build Scan** time-to-compile of
  `:feature-athkar:compileDebugKotlin` after touching one file; iOS via **`xcodebuild` buildlog +
  `swiftc -incremental` timings**, tracked in CI. Fail the CI job if the median exceeds 45 s.

---

## Offline-first sync engine

The sync engine lives in `android/core/src/main/kotlin/com/athkar/core/sync` (pure policy,
unit-tested) and is surfaced to I/O by the platform `sync` module. Its beats:

### Outbox with idempotency keys

- Every local write destined for the server is enqueued as an **outbox row** with an
  **idempotency key = UUID v7** (time-ordered, RFC 9562 — see `UuidV7.kt`). The server returns the
  *same* result for any replay of the same key, so retries are safe by construction.
- Row machine: `PENDING → IN_FLIGHT → DONE | FAILED → (retry) | DEAD_LETTER` (`OutboxState`), exactly
  as implemented in `Outbox.kt`.

### Cursor-based delta sync

- Client sends an **opaque cursor** (max server HLC seen); server returns `≤ 200` changes with
  server HLC strictly greater than the cursor (`SYNC_PAGE_SIZE`, `DeltaCursor.kt`), plus
  `nextCursor` and `hasMore`. A mid-stream network failure resumes from the last **decoded** page, never
  the start.

### HLC conflict resolution (last-writer-wins per field)

- Merging is **per-field** with the **Hybrid Logical Clock** (`Hlc.kt`). The physical component is
  **issued by the server**, so a client cannot fabricate time that competes with the server;
  clients only advance a logical counter. Total order `(hlc, writerId)` makes `merge(a,b)` agree
  with `merge(b,a)` and associativity holds for 3+ replicas (`EntityConflictResolver.kt`).
- Tombstone defeats all; a deleted entity is never resurrected.
- **Sensitive fields** (`ManualMergeFields`) are excluded from automatic LWW and route to a **manual
  merge** screen showing both versions side by side.

### Tombstones

- Deletes are logical (tombstone in `entities`, row in `tombstones` table) and propagated via
  outbox. Retained ≥ 90 days or up to a 10,000-row cap, then physically purged
  (`TombstonePolicy.MAX_TOMBSTONES_AGE_DAYS/MAX_TOMBSTONE_COUNT`) so a late-arriving offline write
  cannot resurrect a deleted entity.

### Backoff & dead letter

- Failed outbox ops retry with **exponential backoff 1/2/4/8/16 s ... capped at 5 min**, up to
  **8 attempts** (full jitter per `BackoffWithJitter.kt`), then the row is parked in the
  **dead-letter** state and the user is notified in-app. `OutboxPolicy.onFailure` decides whether to
  stay `FAILED` or go `DEAD_LETTER`.

### Snapshotting

- **Snapshot every 200 edits** (`SYNC_PAGE_SIZE`) so the operation log stays small; **op-log < 2 MB**
  enforced at write time — when the cap is approached, an older snapshot is compacted and the log is
  truncated.

**"How to measure / what tool."**
- Outbox/retry/dead-letter and cursor advancement: unit-tested in `OutboxAndCursorTest.kt`
  (JUnit 5, coroutine test). Backoff timing: a deterministic test injects a fixed `Random` and
  asserts exact 1/2/4/8/16/… capped-5 min schedule (tool: JUnit).
- Conflict-resolver convergence: property tests `ConflictResolverPropertyTest.kt` / `CrdtMapPropertyTest.kt`
  (tool: Kotlin property testing) asserting associativity, commutativity, idempotence across N
  replicas in random order.
- **Op-log < 2 MB / snapshot cadence**: a CI byte-size assertion after a stress soak (tool: script
  over the DB file + outbox log).
- Dead-letter rate & retry success: **Crashlytics/Sentry custom events** + **Analytics** funnel
  (`outbox_over20`, `dead_letter`) with a dashboards alert.

---

## Process-death handling

The requirement: after a process death / OS force-stop / background eviction, restore the **last
screen + unsaved input** in **< 400 ms**.

- **Android** uses **`SavedStateHandle`** (with `Navigation`'s saved-state support) to persist the
  current route/arguments and any in-progress form fields keyed by the UI state's stable ids.
- **iOS** uses **SwiftUI State Restoration / UIKit `UIStateRestoring`**, persisting the last route
  and the draft fields of the focused screen.
- **Unsaved input** is additionally mirrored to the local DB (as a draft row / `settings`/draft
  table) so it survives even a total cache wipe of the restoration registry.
- Restoration runs before first composition; whole flow (restore → re-render) must complete in
  **< 400 ms**.

**"How to measure / what tool."**
- **Android**: **Macrobenchmark** `Startup`/`RestoreScenario` measuring cold-start-to-fully-restored
  and process-death-restore (kill + relaunch) on a reference Pixel; assert p95 < 400 ms. Verify with
  `adb shell am kill` + relaunch test in instrumented CI.
- **iOS**: **`XCTest` UI test** that terminates and relaunches the app, then asserts the restored
  screen content; timing via **Instruments** (`Time Profiler`) around the restore path.
- Crash-free / ANR: **Crashlytics or Sentry** crash-free session rate (> 99.9% target), **Google
  Play Console / `adb shell dumpsys` + ANR Correlation / Firebase Performance** for ANR rate;
  memory-leak verification with **LeakCanary** (Android) and **Instruments Leaks** (iOS).

---

## Rejected alternatives and why

| # | Rejected choice | Why rejected |
|---|-----------------|--------------|
| 1 | **Reading directly from the network response for UI.** | Violates single-source-of-truth: UI would reflect unpersisted, possibly-stale data and break offline. Every screen would need its own caching/loading/error handling and network and DB could disagree. Rejected in favor of ADR-03 (always read local DB, network only writes). |
| 2 | **Using device wall-clock for ordering / HLC.** | Users change clocks; devices drift; a user could silently corrupt LWW ordering. Rejected: HLC physical time is **server-issued**; clients only advance a logical counter (`Hlc.kt` doc + `ReplicaState` doc). |
| 3 | **Storing secrets (auth/encryption keys, credentials) in SharedPreferences / UserDefaults.** | These are unencrypted, trivially readable by other apps / root / jailbreak, and not backed by hardware. Rejected: keys live in **Android Keystore / iOS Keychain**; the DB stores only references (see `encrypted_credentials` in docs/db/schema.md). |
| 4 | **Trusting silent push (FCM/APNs) to trigger/perform sync.** | Push delivery is **not guaranteed**, is blocked by Doze/low-power and unreliable networks, and can arrive out of order/duplicated. Rejected: push is only a *wake-up hint*; correctness comes from local scheduled sync + manual refresh + the outbox, and every operation is idempotent so a lost/duplicated push is harmless. |
| 5 | **Monolithic single module.** | One module defeats incremental builds (would blow the < 45 s budget), hides accidental coupling, and prevents Dependency Rule enforcement at module granularity. Rejected in favor of ≤ 12 decoupled modules (ADR-04). |
| 6 | **State kept in the cloud only, cache optional.** | Offline-first is a hard requirement (dhikr/prayer access on flights, at prayer time in low-signal areas). Cloud-only means unusable offline and adds latency to every read. Rejected: local DB is the source of truth (ADR-03). |
| 7 | **Round-robin/sequential "last-write-wins on the whole entity"** (no per-field merge). | Whole-entity LWW causes lost updates: editing field A on device 1 while editing unrelated field B on device 2 silently discards one. Rejected: **per-field HLC LWW** merge (`EntityConflictResolver`). |
| 8 | **Plain `System.currentTimeMillis()` as the sync cursor.** | Device clock cannot be trusted (see #2) for ordering; a regression would silently skip/duplicate deltas. Rejected: cursor is the **server-issued max HLC** (`DeltaCursor.kt`, `ReplicaState` guards regressions). |
| 9 | **Cursorless / timestamp-polling full re-sync on every launch.** | Full resync is wasteful, slow on large data, and cannot tolerate mid-stream drops. Rejected: **cursor-based delta sync** with incremental pages (≤ 200/page). |
| 10 | **Deep-nested ViewModel writes back to a global singleton store from multiple screens.** | Two-way data flow makes state transitions hard to log, test, and replay. Rejected in favor of UDF (ADR-02): sealed intents, immutable state, logged reducers. |
