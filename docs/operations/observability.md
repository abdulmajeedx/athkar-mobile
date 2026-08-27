# Athkar — Observability

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: Platform Infrastructure / SRE
Status: Active

This document defines what Athkar measures, how it is linked end-to-end, and which concrete tools
produce the numbers the release gates (`release-plan.md`) and incident runbooks (`runbooks.md`)
depend on. Cross-cutting rules: **no PII in telemetry**, a **bounded analytics event dictionary**,
and **build-time schema validation** so an undefined event or property cannot ship.

Related documents: `docs/operations/release-plan.md` (gates and thresholds),
`docs/operations/runbooks.md` (alerts and incidents), `docs/architecture/architecture.md` (sync
engine and outbox design), `docs/security/masvs-l2-checklist.md` (MASVS-DATA-2 governs the no-PII
rule).

---

## Table of contents

1. [Principles](#1-principles)
2. [Telemetry pipeline architecture](#2-telemetry-pipeline-architecture)
3. [Distributed tracing & sync latency](#3-distributed-tracing--sync-latency)
4. [Core operational dashboard (8 metrics)](#4-core-operational-dashboard-8-metrics)
5. [Tooling](#5-tooling)
6. [Analytics event dictionary](#6-analytics-event-dictionary)

---

## 1. Principles

1. **No PII, ever.** Telemetry properties contain no personal data: no dhikr text, no prayer values,
   no names, emails, phone numbers, or tokens; no OS device identifiers (IMEI, AAID/IDFA, IP
   addresses, MAC). Identity that *is* needed is an app-scoped random `install_uuid` and a
   `session_id`, both minted at install and never correlated back to an account. This is enforced by
   the build-time schema validation in §6.3 (property allow-list units) and by the MASVS-DATA-2
   guard (`masvs-l2-checklist.md`).
2. **Bounded events.** At most **20 distinct analytics events** may exist app-wide. Every event and
   every property is defined in one schema file; shipping an event absent from the schema fails the
   build (§6.3).
3. **Request-ID one-shot correlation.** One identifier (the OpenTelemetry trace ID) follows a
   request from app to server and back, linking client spans, gateway logs, and service spans.
4. **Dashboards over ad-hoc queries.** One canonical dashboard answers "is production healthy?" with
   8 metrics (§4). Anything a runbook needs is a panel here.
5. **Values come from stable formulas** so the same metric means the same thing in the dashboard,
   the rollout gates, and the runbooks.

---

## 2. Telemetry pipeline architecture

```
Android/iOS app
  ├─ OpenTelemetry SDK (spans: UI, sync.run, outbox.send, auth.refresh)
  │      └─ OTLP → Collector (app-side, batched, retried with backoff)
  ├─ Crash + perf      → Sentry (or Firebase Crashlytics) + Firebase Performance
  ├─ Analytics events  → Firebase Analytics ──▶ BigQuery export (90-day raw retention)
  │                           └─ SQL → Grafana metric tables (aggregates)
  └─ Control-plane fetch → signed block; applied + logged as `remote_config_applied`

Backend (Gateway / Auth / Sync / Billing / Notification)
  ├─ OpenTelemetry SDK per service (spans + logs), same trace ID space
  ├─ OTLP → Prometheus exporter (metrics) + Loki (logs)  OR  Datadog agent
  └─ Push delivery acks (FCM/APNs) → notification-delivery metric
```

- **Trace correlation (the request-id):** the app SDK creates a root span per network operation and
  injects the OpenTelemetry `traceparent` header (trace ID = the **request ID**) into the HTTPS
  call. The gateway reads it, stamps the same ID on access logs and downstream claims, and every
  server span for that call is a child. `trace_id` is also written as a log field by each service.
  Verification/measurement: a contract test in CI asserts that a backend log line for a synthetic
  sync request carries the client's `trace_id` (tool: OTel Collector + test harness; see
  `contracts/openapi/*`).
- **Retention:** spans and logs 30 days in the trace/log store; Prometheus 15 days; analytics raw
  events 90 days; crash data 90 days (see `release-plan.md` §7.3).
- **Sampling:** sync and outbox spans are low-volume (a few per user per day) and are exported
  **un-sampled**. UI lifecycle spans are sampled at 1 % of sessions except during an incident when
  the operator raises the sample to 10 % (tool: OTel `Sampler` + remote sampler config). Sampling is
  per-`trace_id`, never per-span, so percentiles stay correct.

---

## 3. Distributed tracing & sync latency

### 3.1 Span inventory (client)

| Span name | Parent | Payload highlights |
|---|---|---|
| `sync.run` | session root | reason (`launch`/`schedule`/`push_wakeup`/`manual`/`retry`), delta_pages, outbox_ops |
| `sync.delta.fetch` | `sync.run` | page_index, delta_count, `trace_id` link |
| `sync.outbox.send` | `sync.run` | op_type, attempts, http_status |
| `sync.auth.refresh` | `sync.run` | outcome (`ok`/`failed`/`family_revoked`) |
| `ui.open.<screen>` | session root | screen name (no user content) |
| `notification.received` | session root | push kind (`adhkar_reminder`/`prayer`) |

### 3.2 Span inventory (server)

| Span name | Maps to | Key attributes |
|---|---|---|
| `gateway.http` | every authenticated call | route, status, latency_ms |
| `auth.token.verify` | auth calls | outcome |
| `sync.service.ingest` | outbox ingest | batch_size, conflict_count |
| `sync.service.delta` | delta response | changes_returned, cursor_advanced |
| `billing.service.verify` | receipt/transaction | store, outcome |
| `notif.service.send` | push fan-out | provider, token_state |

### 3.3 Sync latency percentiles

Latency is measured **server-observed end-to-end** as: time from gateway receipt of a delta/ingest
request to the service the request hits completing its handler — the dominant component of what the
user perceives when sync runs, and the only component both sides can agree on.

- Reported as **p50 / p95 / p99**, plus a **p95 target of ≤ 2 s** for a page fetch with ≤ 200
  deltas on a nominal mobile network (this keeps the release gates meaningful; the metric is
  defined in `architecture.md`).
- Computed from the OTel spans for `gateway.http` (route `/sync/*`) summed across replicas, bucketed
  into Prometheus histograms (or Datadog distribution), and queried per build and per region/track.
- The **release plan** gate uses the same p95 value, now defined once and reused everywhere.

Measurement / what tool: OTel spans → Prometheus histogram `sync_request_duration_seconds`
(histogram buckets aligned to the p95 target) surfaced in Grafana panel "sync latency p95"; Datadog
alternative: `dd.trace_id` distribution + the SLO widget. Drill-down from any Dashboard cell to the
trace list of one `trace_id` is one click (tool: Grafana Tempo/`Explore` or Datadog APM).

---

## 4. Core operational dashboard (8 metrics)

One dashboard (`observability/athkar-overview.json` in Grafana, or the Datadog dashboard equivalent)
shows exactly these 8 metrics — no more, no less — split by platform, `app_version`, and rollout
track/stage. Each has: definition, formula, source, and alert threshold (alert routing lives in
`runbooks.md`).

| # | Metric | Definition / formula | Source (primary tool) | Alert threshold |
|---|---|---|---|---|
| 1 | **Crash-free sessions** | sessions without a fatal crash ÷ all sessions | Crashlytics / Sentry crash-free-session rate | **< 99.5 %** (halts rollout), **< 99.9 %** (target) |
| 2 | **ANR rate** | sessions with an ANR ÷ all sessions | Firebase Performance + Play Console ANRs; Sentry ANR side by side | **> 0.47 %** (halts rollout) |
| 3 | **Sync latency p95** | p95 of `sync_request_duration_seconds` | Prometheus/OTel histograms → Grafana | **> 2 s** for 20 min |
| 4 | **Sync success rate** | `sync_success` ÷ (`sync_success` + `sync_failure`) | Firebase Analytics events + server ingest metric cross-check | **< 97 %** for 20 min |
| 5 | **Outbox dead-letter failure rate** | `sync_dead_letter` events ÷ outbox attempts (logged by `outbox_over20` + server ingest counts) | Analytics (`sync_dead_letter`) + Grafana ratio | **> 1 %** per rolling hour |
| 6 | **Notification delivery rate** | provider accepted ÷ requested, and 'delivered/displayed' where provider reports ack, using token_state | FCM/APNs delivery reports + `Notification Service` metrics | **< 95 %** for 20 min, or token-invalid rate spiking |
| 7 | **Push-to-first-open** | time from provider accepted time to the `app_open` event attributable to that push | server send timestamp + client `notification.received`/`app_open` events (event dictionary) | p95 **> 10 min** over 1 h |
| 8 | **Auth failure rate** | failed auth/refresh attempts ÷ auth attempts | Auth Server logs → Prometheus rate; corroborated by `auth_signin_failure`/`auth_session_revoked` events | **> 5 %** for 20 min or any `family_revoked` burst |

Rules for the dashboard:

- **Baselines:** each panel shows the current value against its 7-day baseline and the release-gate
  threshold line, so a "drop > 3 %" or "spike > x" is visible without arithmetic.
- **Cohorts:** panels are filterable by rollout stage; cohorting reuses the stable `install_uuid`
  bucketing from `release-plan.md` §6.2.
- **One alert group per metric**; alert firing increments the on-call page (see `runbooks.md`).

---

## 5. Tooling

Athkar supports three tool stacks. The **default stack** is (A) mobile = Firebase + Sentry,
backend = Prometheus/Grafana/OTel. Stacks B and C are drop-in alternatives that meet the same
contracts (they must expose the same 8 metrics and the `trace_id` correlation).

### Stack A — Default: Firebase + Sentry + Prometheus/Grafana (self-hosted or managed)

| Concern | Tool |
|---|---|
| Crash, symbolication, ANR side-channel | Sentry (or Crashlytics for crash-free + symbolication) |
| Analytics events + `remote_config_applied` + BigQuery raw export | Firebase Analytics |
| App performance (ANR, network, traces) | Firebase Performance Monitoring |
| Remote config / kill switch channel | Firebase Remote Config (+ signed control plane, `release-plan.md` §4) |
| Distributed tracing (OTel) | Collector + Grafana Tempo |
| Metrics & dashboard (the 8 metrics) | Prometheus + Grafana |
| Logs (server) | Loki |

### Stack B — Datadog (single-vendor)

- Datadog Mobile SDK (RUM + tracing + logs + crashes) replaces Sentry/Firebase parts; Crash Reporting
  and ANR are native Datadog features.
- Distributed tracing: client OTel spans bridged to Datadog trace ID (Datadog `dd-trace` injection)
  keeps the same trace header; APM trace search exposes `trace_id`.
- The dashboard is a single Datadog dashboard with the same 8 panels and monitor thresholds.
- Covers all of mobile, server, and alerting; retains the 90-day analytics window via logs.

### Stack C — Firebase-only (fastest start)

- Crashlytics + Firebase Analytics/Performance + Remote Config, with events exported to BigQuery.
- Distributed tracing is approximated by the sync-RATT (round-trip aggregate time
  `sync_success.duration_ms` samples) since client-only Firebase lacks cross-service traces; the
  `request_id` correlation is then done by **log linkage** (`request_id` column) rather than OTel
  parent spans. Accepted only until sync p95 > 2 s becomes an active question — then migrate to A.

**Which stack where:** the release gates in `release-plan.md` explicitly call out the tool for each
threshold. Every tool must (a) expose the eight §4 formulas, (b) honor the no-PII schema in §6, and
(c) keep 90-day analytics + crash retention.

---

## 6. Analytics event dictionary

### 6.1 Rules (fixed naming scheme)

- Event names: `snake_case`, exactly **`<domain>_<action>`** (two segments), ASCII `[a-z0-9_]`,
  3–40 characters total. Examples: `sync_success`, `adhkar_session_complete`.
- **Cap: 20 events total**, app-wide, documented below. Adding a 21st requires an ARC and a
  replacement (an event cannot merely be renamed; old events stay reserved for the retention window).
- Common properties on **every** event (automatically attached, schema-constrained, zero PII):

  | Property | Type | Notes |
  |---|---|---|
  | `install_uuid` | string (UUIDv4, app-scoped) | pseudonymous; not a device identifier |
  | `session_id` | string (UUIDv4) | per app-session |
  | `app_version` | string `MAJOR.MINOR.PATCH` | |
  | `build_number` | int | |
  | `platform` | enum `android` / `ios` | |
  | `os_version` | string | e.g. `android 14` / `ios 17.4` |
  | `feature_flags` | string[] | active flag set at event time (crash ↔ flag linkage) |
  | `connection_state` | enum `online` / `offline` | as-known at event time |
  | `event_uuid` | string (UUIDv4) | dedupe key; makes ingestion idempotent |

### 6.2 The 20 events

| # | Event | Trigger | Properties (beyond common) |
|---|---|---|---|
| 1 | `app_first_launch` | first run after install | `referrer` (hashed) |
| 2 | `app_launch` | cold process start | `foreground_depth` (`cold`/`warm`) |
| 3 | `app_open` | app became foreground | `from_notification` (bool) |
| 4 | `app_background` | app backgrounded | `session_duration_ms` |
| 5 | `sync_started` | a sync run begins | `reason`, `outbox_pending_count` |
| 6 | `sync_success` | a sync run completes | `duration_ms`, `delta_count`, `outbox_flushed` |
| 7 | `sync_failure` | sync run fails (retryable or fatal) | `error_code`, `attempt`, `retryable` |
| 8 | `sync_dead_letter` | outbox row parked as DEAD_LETTER | `op_type` |
| 9 | `outbox_over20` | pending outbox exceeds 20 rows (see `architecture.md`) | `pending_count` |
| 10 | `adhkar_session_start` | user opens a dhikr session | `adhkar_list_id` (opaque non-PII id) |
| 11 | `adhkar_session_complete` | session finished | `completed_count`, `duration_ms` |
| 12 | `adhkar_session_abandon` | session left unfinished | `partial_count` |
| 13 | `reminder_scheduled` | reminder created/edited | `hour`, `enabled` |
| 14 | `notification_tapped` | user taps a delivered notification | `push_kind`, `delivery_to_open_ms` |
| 15 | `prayer_times_displayed` | prayer panel rendered with values | `calculation_source`, `data_origin` (`local`/`server`) |
| 16 | `auth_signin_success` | successful sign-in | `method` (`oauth`/`recovery`) |
| 17 | `auth_signin_failure` | failed sign-in | `error_code` |
| 18 | `auth_session_revoked` | token family revoked → forced re-auth | `reason` |
| 19 | `remote_config_applied` | a signed control block was applied | `kill_switch`, `feature_overrides_count`, `source` (`control_plane`/`remote_config`/`bundle`) |
| 20 | `deep_link_opened` | valid deep link handled | `link_type`, `validated` |

Derived metrics that use these events (cross-reference): sync success rate (6,7), dead-letter rate
(8 + attempts), primary-path conversion (10,11), notification delivery (14 + provider acks),
push-to-first-open (3 + 14), auth failure rate (16,17,18).

### 6.3 Build-time schema validation

- **Single schema file** `contracts/analytics/events.schema.json`: JSON Schema describing every
  event name, its allowed property set (name → type, required/optional), enum values, and unit
  constraints (e.g., durations in ms, ints non-negative). The common property set is a reusable
  `$defs` block so constraining it is automatic.
- **Validation at build time:** a Gradle task (`:app:validateAnalyticsSchema`) and an Xcode build
  phase (script `scripts/ci/validate-analytics.sh`) run a schema validator over the analytics SDK
  static table of event definitions and over a generated `AnalyticsEvents.kt`/`AnalyticsEvents.swift`
  sentinel for stub-typo detection:
  - any **undefined** event name → build failure ("event `foo_bar` is not in the dictionary");
  - any property not allowed for the event, or violating type/unit → build failure;
  - a codegen step emits typed event objects so a misspelled property fails to compile.
- **Runtime guard (defense in depth):** the analytics dispatcher drops an event whose name or
  property set fails the schema and logs a non-PII diagnostic; the drop counter is a dashboard
  panel. This guarantees the "rejecting undefined events" rule even on a stale branch.
- **No PII enforcement (MASVS-DATA-2):** the schema's property *unit* list has no free-text or
  string-content property other than enumerated/enum-typed values; CI scans emitted payload shapes
  and fails if a free-text/`string` open-ended property exists. Verification: capture telemetry in a
  harness and assert no PII-like pattern appears (see `masvs-l2-checklist.md` MASVS-DATA-2).

Measurement / what tool: `npx ajv-cli validate -s events.schema.json -d generated-events.json`
in CI (tool: CI script + YAML pipeline), plus the runtime guard (tool: analytics SDK + dashboard
panel `analytics.schema_drops`).

---

## 7. Dashboard ownership

- Owner: Platform Infrastructure/SRE. Content changes (adding panels) require an MR updating this
  document, `observability/athkar-overview.json`, and the runbook alerts in lockstep.
- The 8 core metrics may not be renamed or removed without revisiting `release-plan.md` gates and
  the corresponding runbooks.