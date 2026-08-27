# Athkar — Local Database Schema (SQLite)

**Platforms:** Android (Room) and iOS (GRDB). Both target the same relational model over a single
**SQLite** database file so the schema, indexes, WAL behaviour, and migration set are shared
conceptually; each table below is annotated with the platform mapping.

**Storage engine:** SQLite in **WAL (Write-Ahead Logging) mode** — the default for Room and enabled
via `journal_mode=WAL` for GRDB. WAL gives concurrent readers snapshot isolation (they never block
the writer) and dramatically reduces read latency — a prerequisite for the **local read < 16 ms**
budget in docs/architecture/architecture.md.

**Conventions used throughout:**
- `TEXT` primary keys are **UUID v7** (time-ordered, RFC 9562 — see `UuidV7.kt`). Time-ordered ids
  keep clustered B-tree inserts sequential and make the delta-sync cursor / outbox orderable.
- `hlc` is a stored **64-bit integer** encoding a Hybrid Logical Clock (48-bit server physical time
  + 16-bit logical counter — see `Hlc.kt`).
- `writer_id` is opaque (device or user), used **only** for deterministic tie-breaking in LWW merge,
  never for identity/authorization.
- Every column used in a `WHERE`, `ORDER BY`, or `GROUP BY` is indexed (explicit `INDEX` listed per
  table). Column-level `COLLATE`/type annotations match SQLite's dynamic typing where noted.

---

## 1. `entities` — adhkar reminders (dhikr library)

The primary user-domain entity. Stored as a bag of per-field LWW registers so `EntityConflictResolver`
can merge field-by-field; the `hlc` column is the entity-level max server HLC, `server_hcl` the
per-write server HLC used by the sync cursor.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `id` | `TEXT` | **PK** | N | UUID v7 (time-ordered) |
| `title` | `TEXT` | | Y | Dhikr heading / short text |
| `body` | `TEXT` | | Y | Full text to be recited |
| `target_count` | `INTEGER` | | Y | Repetitions for a full khatm / wird |
| `times` | `TEXT` | | Y | JSON array / set of reminder times (adhan, morning, evening, afterPrayer, custom) |
| `cat_order` | `INTEGER` | | Y | Sort order within category |
| `pinned` | `INTEGER` (0/1) | | Y | Pinned flag (boolean) |
| `server_hcl` | `INTEGER` | | N | Server-issued HLC of the last write (48/16 bit layout) |
| `writer_id` | `TEXT` | | N | Last writer opaque id (tie-break only) |
| `tombstoned` | `INTEGER` (0/1) | | N | Logical delete flag (default 0) |
| `updated_at` | `INTEGER` | | N | Local wall-clock ms (display only — **never** used for ordering/merge) |

**Indexes:**
- `PRIMARY KEY (id)` — clustered; UUIDv7 keeps it append-ordered.
- `INDEX idx_entities_cat_order ON entities(cat_order)` — list ordering.
- `INDEX idx_entities_pinned ON entities(pinned)` — pinned filtering.
- `INDEX idx_entities_tombstoned ON entities(tombstoned, cat_order)` — filtered list queries (hide tombstones).
- `INDEX idx_entities_server_hcl ON entities(server_hcl)` — delta-sync cursor advancement.

> **Why `updated_at` is display-only.** Ordering/merge correctness comes from `server_hcl` + `writer_id`
> (see Rejected alternative #2 in architecture.md). `updated_at` is retained only for "recently edited"
> UI and **must never** be used for conflict resolution.

**Platform mapping:** Android `@Entity(tableName = "entities")`; iOS GRDB `Table("entities")`
struct `AdhkarReminderRecord`.

---

## 2. `outbox` — pending/idempotent operations

An append-only log of every local write destined for the server. Guarantees replay safety: the
`idempotency_key` is a **UUID v7** and the server returns the same result for any replay of the same
key, so retries are safe. The `state` enum drives the retry state machine in `Outbox.kt`.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `idempotency_key` | `TEXT` | **PK** | N | UUID v7 (time-ordered, monotonic within a device) |
| `op_type` | `TEXT` | | N | e.g. `UPSERT_ENTITY`, `TOMBSTONE`, `LWW_MAP_SET` |
| `entity_id` | `TEXT` | | Y | Target entity; null for settings/map ops |
| `payload` | `TEXT` | | N | JSON payload (schema defined by `op_type`) |
| `created_at` | `INTEGER` | | N | Local wall-clock ms (sort/housekeeping only) |
| `state` | `TEXT` | | N | Enum: `PENDING`/`IN_FLIGHT`/`FAILED`/`DONE`/`DEAD_LETTER` |
| `attempt_count` | `INTEGER` | | N | Monotonic; `>= BackoffWithJitter.maxAttempts (8)` → dead letter |
| `last_error` | `TEXT` | | Y | Last failure message for diagnostics/dead-letter review |

**Indexes:**
- `PRIMARY KEY (idempotency_key)` — clustered, append-ordered by UUIDv7.
- `INDEX idx_outbox_state ON outbox(state)` — **the hot sync query**: pick next `PENDING`/`FAILED`
  rows. Mandatory; the worker runs this query on every tick.
- `INDEX idx_outbox_created ON outbox(created_at)` — retention/cleanup sweep.
- Composite partial-style query for retries relies on `idx_outbox_state`; a combined
  `idx_outbox_state_created ON outbox(state, created_at)` is used by the worker to pick the oldest
  retriable row deterministically.

**Outbox cleanup/GC:** `DONE` rows (attempt_count ≥ 1, or older than retention) and fully-retired
rows are purged by a periodic job (WorkManager / GRDB job) that keeps the table bounded. Rows in
`IN_FLIGHT`/**orphaned** (crashed mid-send) older than 24 h are reclaimed to `FAILED` and retried —
never resubmitted with a new key, so idempotency is preserved. See "Outbox cleanup" in GC below.

---

## 3. `outbox_dead_letter` — dead-letter station

The brief allows the dead-letter to live inside `outbox` (via `state = DEAD_LETTER`) **or** in a
separate table. We do **both** with a clear split:

- **Immediate parking:** a row transitions to `state = DEAD_LETTER` *in place* in `outbox` (as
  implemented by `OutboxPolicy.onFailure`). This preserves the original `idempotency_key` and
  `attempt_count`, which the manual-review flow needs.
- **Long-term quarantine:** once parked, the row is *moved* to the dedicated
  `outbox_dead_letter` table (below) by a GC/review task. This decouples the hot `outbox` table
  (which the worker scans on every tick) from rarely-read quarantined rows.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `idempotency_key` | `TEXT` | **PK** | N | Preserved from outbox (UUID v7) |
| `op_type` | `TEXT` | | N | Original op type |
| `entity_id` | `TEXT` | | Y | Original target entity |
| `payload` | `TEXT` | | N | Original JSON payload |
| `received_at` | `INTEGER` | | N | When it entered quarantine (ms) |
| `attempt_count` | `INTEGER` | | N | Final attempt count (8) |
| `last_error` | `TEXT` | | Y | Terminal error detail |
| `resolved` | `INTEGER` (0/1) | | N | Manual review outcome (retried or discarded) |

**Indexes:**
- `PRIMARY KEY (idempotency_key)`.
- `INDEX idx_dl_received ON outbox_dead_letter(received_at)` — retention sweep / review queue ordering.
- `INDEX idx_dl_resolved ON outbox_dead_letter(resolved)` — pending-review query.

**Retention:** dead-letter rows are retained for **90 days** after `received_at`, then physically
deleted, regardless of `resolved`, to keep the quarantine bounded.

---

## 4. `tombstones` — logical-delete registry

Cooperates with `entities.tombstoned` and `SyncPolicy.TombstonePolicy` to guarantee a deleted record
is **never resurrected** by a late-arriving offline write. Rows survive ≥ 90 days (or up to a
10,000-row cap), then are physically purged.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `entity_id` | `TEXT` | **PK** | N | UUID v7 of the deleted entity |
| `tombstoned_at` | `INTEGER` | | N | When the tombstone was recorded (ms) |
| `hlc` | `INTEGER` | | N | Server HLC of the delete — used to defeat any older write |

**Indexes:**
- `PRIMARY KEY (entity_id)`.
- `INDEX idx_tombstones_at ON tombstones(tombstoned_at)` — **GC scan** (`TombstonePolicy.isExpired`).

**Retention / GC policy (documents `TombstonePolicy` in `SyncPolicy.kt`):**
- `MAX_TOMBSTONES_AGE_DAYS = 90` — a tombstone is alive ≥ 90 days so a device offline beyond that
  window cannot resurrect it.
- `MAX_TOMBSTONE_COUNT = 10_000` — hard cap; when exceeded, **oldest** tombstones are purged first
  (monotonic-capacity).
- GC runs on the periodic sync/housekeeping job; expired rows are physically `DELETE`d from both
  `tombstones` and (if still present) `entities.tombstoned` rows are removed.

---

## 5. `settings` — LWW-element-map (settings sync)

Stores small scalar settings (language, prayer method, offsets, notification toggles) as an
`LwwMap` (see `LwwMap.kt`). Each key is an LWW register with its own `hlc` + `writer_id` so any
number of devices converge order-independently.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `key` | `TEXT` | **PK** | N | Setting key |
| `value` | `TEXT` | | Y | JSON-encoded value; `NULL` value = tombstone for that key |
| `hlc` | `INTEGER` | | N | LWW register HLC (server-issued physical part) |
| `writer_id` | `TEXT` | | N | LWW register writer (tie-break) |

**Indexes:**
- `PRIMARY KEY (key)`.
- `INDEX idx_settings_hlc ON settings(hlc)` — delta sync of settings changes (pull by server HLC > cursor).

**Merge semantics:** pointwise `LwwMap.merge` (max by `(hlc, writer_id)`, deterministic across
replicas). A `NULL` value with a newer hlc is a retained tombstone that defeats stale writes.

---

## 6. `sync_cursor` — delta-sync position

A **single row** recording the client's position in the server delta stream. Because there is exactly
one cursor per device, the primary key is a fixed constant.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `id` | `INTEGER` | **PK** | N | Always `0` — enforces the single-row invariant |
| `last_cursor` | `TEXT` | | N | Opaque server cursor (max server HLC seen); empty = initial |
| `last_sync_at` | `INTEGER` | | Y | ms of last successful pull (telemetry only) |

**Indexes:** `PRIMARY KEY (id)` is the only index; there is a single row so no other index is needed.
Uniqueness is guaranteed structurally by the fixed PK.

**Usage:** `CursorLogic.advance` computes the next cursor as the max `serverHlc` of the decoded page,
so it is robust to out-of-order delivery and idempotent (re-applying a page yields the same cursor).

---

## 7. `notifications` — internal notification center

The in-app notification inbox (dead-letter alerts, merge-required prompts, dhikr reminder
notifications, prayer-time alerts). It is a local table, independent of OS push banners, so the user
always has history even when offline or behind a silent push.

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `id` | `INTEGER` | **PK** (autoincrement) | N | Local sequential id |
| `type` | `TEXT` | | N | Category: e.g. `adhkar`, `prayer`, `sync`, `merge_required`, `dead_letter` |
| `title` | `TEXT` | | N | Short title (localized on render) |
| `body` | `TEXT` | | Y | Full text |
| `image_url` | `TEXT` | | Y | Optional CDN image |
| `deep_link` | `TEXT` | | Y | In-app navigation route to open |
| `is_read` | `INTEGER` (0/1) | | N | Read flag |
| `received_at` | `INTEGER` | | N | ms when received/created |
| `thread_id` | `TEXT` | | N | Groups related notifications (e.g. a reminder entity / category) |

**Indexes:**
- `PRIMARY KEY (id)` (autoincrement).
- `INDEX idx_notif_read_at ON notifications(is_read, received_at)` — **the inbox query**: fetch
  unread-first, newest-first. Index every column in the filter/order: both `is_read` and
  `received_at` are in this composite index.
- `INDEX idx_notif_thread ON notifications(thread_id)` — thread grouping.

**Retention:** keeps the **last 100** rows by `received_at`. A GC job deletes the oldest beyond 100
after each insert burst.

---

## 8. `encrypted_credentials` — reference-only (secrets never in the DB)

Secrets (auth tokens, refresh tokens, sync encryption keys) are **never** stored in SQLite in
plaintext or ciphertext form here. Instead:

| Column | Type (SQLite) | Key | Null | Notes |
|--------|---------------|-----|------|-------|
| `name` | `TEXT` | **PK** | N | Logical key name (e.g. `auth_token`, `sync_key`) |
| `keystore_alias` | `TEXT` | | N | Alias/reference into Android **Keystore** / iOS **Keychain** |
| `updated_at` | `INTEGER` | | N | Last rotation ms |

**Security model:** the actual secret bytes live exclusively in the hardware-backed **Android
Keystore / iOS Keychain**; this table stores *only* the opaque alias that resolves to the secret.
Enforced by ADR (see Rejected alternative #3 in architecture.md). No raw token value exists in any
SQLite file, backup, or crash report.

---

## WAL mode & configuration

- `PRAGMA journal_mode = WAL` (default on Room; explicitly set on GRDB). Consequence: **readers see a
  consistent snapshot and never block the writer**, satisfying the concurrent-read < 16 ms budget.
- `PRAGMA synchronous = NORMAL` (WAL-safe default) for durability without the full-FSYNC hit.
- `PRAGMA foreign_keys = ON` (Room/Room enables; GRDB enabled explicitly); `fullfsync`/`legacy` off.
- Statistically the single SQLite file is the sync/entities store; media (adhkar artwork) lives in the
  **Image CDN**, not the DB (only URLs in `notifications.image_url` / entity media refs).

---

## Numbered migrations

The database is versioned as `RoomDatabase.version` (Android) / `DatabaseMigrator` version (iOS).
Both platforms ship the identical logical migration set; each migration is a **forward-only** step
with a corresponding start-end version pair.

| # | From → To | Changes |
|---|-----------|---------|
| **1** | 0 → 1 | Initial schema: `entities`, `outbox`, `sync_cursor`. |
| **2** | 1 → 2 | Add `tombstones`; add `entities.tombstoned` + `entities.server_hcl` + `entities.writer_id`. |
| **3** | 2 → 3 | Add `settings` (LWW map); add `idx_settings_hlc`. |
| **4** | 3 → 4 | Add `notifications` + `idx_notif_read_at` + `idx_notif_thread`. |
| **5** | 4 → 5 | Add `outbox_dead_letter` + `idx_dl_received` + `idx_dl_resolved`; add `outbox.last_error`, `outbox.attempt_count`. |
| **6** | 5 → 6 | Add `idx_outbox_state_created` (composite) for deterministic worker ordering; add `idx_entities_server_hcl`. |
| **7** | 6 → 7 | Add `idx_entities_pinned`, `idx_entities_cat_order`, `idx_tombstones_at` (GC-optimizing indexes). |
| **8** | 7 → 8 | *Future/reserved* — e.g. thumbnail caches or new feature tables; the migration set stops here today. |

Each migration must be **both** forward and, where a test requires, reversible — but Room/GRDB only
ship forward migrations; the reversibility concern is handled by testing the schema at each version.

### Migration tests — how they're run

- **Android (Room):** `RoomDatabaseMigrationTestHelper` (androidx.room) runs an in-memory database
  through the migration from **N−1 → N** and asserts the schema matches the expected exporter schema.
  We add a test per migration: `@Test fun migrate_2_to_3_keeps_entities_and_adds_settings()` — seeds
  version-2 data, runs the migration, asserts no data loss and that new columns carry sane defaults.
  Schema-consistency is enforced by comparing against the Room-generated schema JSON
  (`room_schema_location` checked into the repo).
- **iOS (GRDB):** `DatabaseMigrator` with an explicit `registerMigration` per step; tests run each
  migration against a scratch in-memory DB and assert rows survive (GRDB `migrator.test` /
  `migrator.testMigrations`), verifying both forward application and row preservation.
- **CI:** a `migrations` lane runs both suites on every PR; the migration version constant is guarded
  so no feature branch can increment it without a matching test.

---

## Summary of indexes (every filtered/ordered column covered)

| Table | PK | Other indexes | Key filtered/ordered columns |
|-------|----|---------------|------------------------------|
| `entities` | `id` | `cat_order`, `pinned`, `tombstoned(+cat_order)`, `server_hcl` | `cat_order`, `pinned`, `tombstoned`, `server_hcl` |
| `outbox` | `idempotency_key` | `state`, `created_at`, `state+created_at` | `state`, `created_at` |
| `outbox_dead_letter` | `idempotency_key` | `received_at`, `resolved` | `received_at`, `resolved` |
| `tombstones` | `entity_id` | `tombstoned_at` | `tombstoned_at` |
| `settings` | `key` | `hlc` | `hlc` (delta pull) |
| `sync_cursor` | `id` (single row) | — (single row) | — |
| `notifications` | `id` (autoincrement) | `is_read+received_at`, `thread_id` | `is_read`, `received_at`, `thread_id` |
| `encrypted_credentials` | `name` | — (tiny, reference-only) | — |

**Index coverage note:** `EXPLAIN QUERY PLAN` is run automatically (tool: Room query log /
`sqlite3` in CI) over every DAO/repository query and **fails the build** for any query that filters
or orders by an unindexed column — see ADR-03 in architecture.md.

## GC / retention policy summary

| Table | Policy | Trigger |
|-------|--------|---------|
| `tombstones` | Keep ≥ 90 days; cap 10,000 rows; purge oldest on overflow | Periodic housekeeping job (`TombstonePolicy`) |
| `notifications` | Keep last 100 by `received_at` | After insert burst |
| `outbox` | Purge `DONE`; reclaim orphaned `IN_FLIGHT` > 24 h to `FAILED` | Periodic worker tick |
| `outbox_dead_letter` | Retain 90 days post-quarantine, then delete | Periodic housekeeping job |
| `sync_cursor` | Single row, no GC | — |
| `encrypted_credentials` | Rotate/alias-managed by Keystore/Keychain | On token rotation |
