# Athkar — Incident Runbooks

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: On-call Engineering (Ops) / SRE
Status: Active

This document is the operational playbook for production incidents. Every threshold referenced
here is defined and measured in `docs/operations/observability.md` (§4) with the tool named there;
release-halting gates and rollback mechanics are in `docs/operations/release-plan.md`.

---

## Table of contents

1. [Incident response process](#1-incident-response-process)
2. [Generic rollback checklist](#2-generic-rollback-checklist)
3. [Runbooks](#3-runbooks)

---

## 1. Incident response process

### 1.1 Severity definitions

| Severity | Name | Definition | Examples (this app) | Target response (ack) |
|---|---|---|---|---|
| **SEV1** | Critical | App unusable fleet-wide, or a security/data-integrity compromise in progress, or store-critical billing/auth broken. User harm at scale. | Kill-switch needed; OAuth family revocation at scale; sync down for > 90 % of clients; billing verification failing for all | 15 min |
| **SEV2** | High | Major feature or metric degraded > 30 min; rollout gate failing; incident affecting a large but partial cohort. | Crash-free < 99.5 %; ANR > 0.47 %; sync p95 > 2 s sustained; dead-letter rate > 1 %/h | 30 min |
| **SEV3** | Medium | Degradation with a workaround, small cohort, or single service that does not touch user data. | Notification delivery dip < 95 % briefly; pin rotation adoption < 100 %; single-row billing failures | 2 h |
| **SEV4** | Low | Cosmetic/non-urgent; tracked as an issue. | Analytics schema drop counters; stale dashboard panel | next business day |

Severity can escalate in both directions; when in doubt, **declare the higher severity** — opening
SEV1 and downgrading is cheaper than the reverse.

### 1.2 Roles

| Role | Who | Duties |
|---|---|---|
| **Incident Commander (IC)** | most senior on-call | Declares severity, assigns roles, drives phases, owns communication, delegates decision to runbook authors otherwise |
| **Scribe** | any engineer | Writes the timeline (decisions, actions, timestamps) in the incident channel; preserves the "crime scene" metrics window |
| **Primary responder** | on-call engineer | Executes the runbook steps, pushes flags/rollbacks, watches the 8-metric dashboard |
| **Liaison** | IC-appointed | Status page, stakeholder and store-affairs updates |
| **Declaring / deputies** | owners of the affected doc | For security incidents: Security Engineering; for store: release engineer |

### 1.3 On-call

- Single on-call rotation, 24×7, backed by a second responder. Pager path: Grafana/Datadog/Sentinel
  alert → PagerDuty/on-call endpoint → IC page. Acknowledgement SLA per severity table above.
- The on-call engineer has **write access at the source**: remote-config and control-plane overrides
  (`kill_switch`, `tls.pins.*`), flag toggles, and (SEV1) backend deploy pinning, so runbook steps
  are executable without a second hop.

### 1.4 The five phases

1. **Detect** — alert fires from `observability.md` alert groups, a rollout gate halts, or a user/
   store report. Log observed numbers verbatim.
2. **Triage** — IC assigns severity, names the affected version(s)/cohort(s), and checks whether the
   rollout is involved (if the gate halted, it already is).
3. **Contain** — apply the runbook's first steps, prefer *remote* controls (flag OFF, kill switch,
   stage reduction) before *code* changes. Time-to-contain is the primary metric.
4. **Mitigate & verify** — fix root cause, re-run the affected metric; confirm via §1.5 "verify fix"
   before declaring recovery.
5. **Recover & postmortem** — restore normal rollout/gates, close the incident, then a **blameless
   postmortem**: timeline, root cause, prevention items, runbook updates, and re-running the
   affected measure in CI.

Communication: all incidents get a status-page entry when SEV1/SEV2 exceeds 15 min; store-facing
incidents include the App Store/Play operator notes. No unilateral silence during an incident.

---

## 2. Generic rollback checklist

Used by most runbooks when a code-level or data-level rollback is required; mechanics and
prioritization in `release-plan.md` §8.

1. **Stop the bleed first** with remote controls: feature flag OFF → stage reduction → kill switch.
2. Preserve the **crime scene**: freeze the 8-metric dashboard window and the trace/log window for
   the affected version and cohort.
3. Identify the exact release (`app_version` + `build_number`) and active `feature_flags` from
   telemetry §7.2 of the release plan.
4. Decide: **kill/flag** (config) vs **stage-reduce** (exposure) vs **unpublish/hotfix** (binary).
5. If shipping a hotfix: new monotonic build number, dSYM/mapping uploaded before promotion,
   re-enter at canary/1 %, re-gate per §6.2 of `release-plan.md`.
6. Data-touching incidents: restore from the local snapshot taken every 200 edits, or re-enroll the
   affected cohort toward a healthy replica; never rewrite tombstones.
7. Verify at each step: metric back within gate thresholds for the cohort; alert groups clear.
8. File the postmortem and update this runbook.

---

## 3. Runbooks

### RB-01 Crash spike

Triggering alert: crash-free sessions **< 99.5 %** (target 99.9 %); new crash signature > 0.1 % of
sessions/h (alert ≤ 1 h, `release-plan.md` §7.3). Rollout gate halts if a release is in flight.

**Symptoms (with tool):**
- `observability.md` dashboard panels 1 and 2 vs baseline (Grafana / Crashlytics / Sentry).
- Sentry/Crashlytics issue list shows an issue with an unusual signature/rate for a
  `app_version`+`feature_flags` set; releases from `Observability`-linked traces.
- Play Console "ANRs & crashes" + crash correlation if Android.
- Possibly a correlated rollout stage (the gate halts and pages).

**Severity:** SEV1 if crash-free < 98 % or store-blocking; else SEV2.

**Response steps:**
1. Confirm and quantify: read crash count/session rate and growth per 15-min window (tool: the §4
   dashboard panels, export the cohort window).
2. Identify the signature, the exact `app_version`/`build_number` and `feature_flags` on the crash
   extras (`release-plan.md` §7.2). Symbolicate is implicit (dSYMs already uploaded); if symbols are
   missing, this is itself a process bug — file an issue.
3. Replay or reason on the signature: grep the code for the stack frames; use the local State
   Transition Log reproduction harness (`architecture.md` ADR-02) if the crash is a pure-reducer
   assertion.
4. **Contain via remote controls first:** if the crash is flag-gated (any new feature is, §3 of
   `release-plan.md`), flip the feature flag OFF for the affected version; re-evaluate. If the crash
   is not feature-scoped, reduce the staged rollout to the last proven stage.
5. If the crashing version is fully rolled out, decide hotfix vs downgrade path per `release-plan.md`
   §8.1.
6. Patch: land the fix on `main` (trunk), tag PATCH/MINOR, run the full pipeline (build → tests →
   lint → contracts → security → dSYM upload), ship via canary → 1 %.

**Verify fix:**
- Crash-free sessions for the patched cohort ≥ 99.9 % and no recurrence of the signature in 24 h
  (tool: Sentry/Crashlytics issue status "resolved" + dashboard panel 1).
- The new-crash alert group is clear for the rolled-out cohort.

**Rollback:**
- Remote: keep the flag OFF for the bad build. Binary: unpublish/pause the bad Android release in
  Play Console (`release-plan.md` §8.1 step 3); iOS: hotspot PATCH with expedited review. Re-enter
  rollout at 1 % and re-gate.
- Do **not** disable crash reporting while diagnosing; reducing information is itself an outage.

---

### RB-02 ANR spike

Triggering alert: ANR rate **> 0.47 %** of sessions (dashboard panel 2); typically correlated with
a release or a server-driven change (e.g., a heavy sync payload, a stuck WorkManager job).

**Symptoms (with tool):**
- Panel 2 > 0.47 % per 15-min window; Firebase Performance "ANR" traces per function; Play Console
  ANRs; Sentry ANR integration if enabled.
- Traces point at a main-thread function (often DB read now > 16 ms, serialization, a busy
  WorkManager chain, or the notification scheduler).
- Check sync behavior: an ANR spike often follows `sync.run` doing out-of-band DB work on the main
  thread.

**Severity:** SEV2 (SEV1 if > 2 % and app appears frozen).

**Response steps:**
1. Confirm the ANR rate and cohort/version (dashboard panel 2 export).
2. Collect the ANR stack traces (Firebase/Play) and map to code; check the hot methods match a code
   change in the release or a backend behavior change (e.g., slow server responses making the client
   block).
3. **Contain:** reduce stage / disable the suspect feature flag (flags gate every feature).
   Typical immediate relief: raise sync backoff to reduce work during a server issue; kill switch not
   warranted unless the whole sync path is implicated.
4. Fix the offending path (move work off the main thread; batch DB writes; cap sync work via
   `SyncPolicy`). Add/keep the **Macrobenchmark / Marco** guard from `architecture.md` ADR-03 so a
   local read p95 > 16 ms fails CI.
5. Ship hotfix per the pipeline and re-enter the rollout at 1 %.

**Verify fix:**
- ANR rate back < 0.47 % for the cohort and stays for ≥ 12 h; no ANR-frame regression in the
  Macrobenchmark CI lane (p95 < 16 ms).

**Rollback:**
- If the ANR was introduced by a data-path change, restore from the snapshot migration path
  (`release-plan.md` §8.1 step 6) and re-enroll affected devices; otherwise the standard
  phase: flag → stage → unpublish → hotfix.

---

### RB-03 Sync outage

Triggering alert: sync success rate **< 97 %** or sync latency p95 **> 2 s** for 20 min
(panels 3 and 4); users' offline queues keep growing (`outbox_over20`).

**Symptoms (with tool):**
- Panels 3 and 4 out of band, split by backend region and client build; `trace_id` drill-down shows
  `gateway.http`/`sync.service.*` spans failing or slow.
- Elevated HTTP 5xx/4xx in gateway metrics (Prometheus `gateway_http_requests_total{status=~"5.."}`);
  auth failures upstream (`auth.token.verify`).
- Client-side: `sync_failure` events rising, `sync_success` flat; outbox `pending_count` grows.

**Severity:** SEV1 if > 90 % of clients affected or sync fully down; else SEV2.

**Response steps:**
1. Confirm via panels 3/4 and the trace drill-down; capture the failing `trace_id`s.
2. Classify: gateway-level (5xx/rule), service-level (Sync/Auth), or client-level (a new build
   broken). Check the just-released client version and its flags (release gate would already have
   halted if it is a client regression).
3. **Contain:** if the current release is implicated, reduce stage/flip flag. For server faults:
   pin server deployments to the last known-good build (stateless services — safe per `architecture.md`);
   re-point at healthy replicas. Apply the API-gateway `app.min_supported_build` version gate if
   blocking an old (EOL) client is the right move (only after auditing that blocking helps).
4. For auth-induced sync failure, see RB-07 before touching anything else.
5. Once the underlying fault is fixed, clients recover by **idempotent retry** (backoff up to 8
   attempts, then dead-letter per `architecture.md`); no server-side data repair is needed for a pure
   connectivity outage.

**Verify fix:**
- Panels 3 and 4 back in band for 30 min; outbox drain: `outbox_over20` counts and dead-letter rate
  return to baseline; a fresh cohort sync succeeds with `trace_id` correlation confirmed.

**Rollback:**
- Server: redeploy/rollback the offending service version (registry revert). Client: if a build
  caused it, flag/stage/unpublish + hotfix PATCH. Data: none — resync converges by design; let a
  full delta pass complete before starting any further wave.

---

### RB-04 Dead-letter queue buildup

Triggering alert: outbox dead-letter failure rate **> 1 %** per rolling hour (panel 5), or regular
`sync_dead_letter` events (`outbox_over20` preceding them).

**Symptoms (with tool):**
- Panel 5 above threshold; `sync_dead_letter` event rate up (Analytics/BigQuery → Grafana).
- Server ingest logs show `sync.service.ingest` rejecting rows (conflict/validation/500) or
  identical idempotency-key UUIDv7 replays orphaned.
- User-visible: "some changes could not be synced" in-app notice; the manual-merge screen appears
  for `ManualMergeFields` (see `architecture.md`).

**Severity:** SEV2 (SEV3 if < 10 accounts affected but a schema/merge regression).

**Response steps:**
1. Confirm rate, op_type(s), and the versions/cohort (panel 5 breakdown).
2. Examine failing payloads (non-PII: op_type, error_code) to separate: (a) server rejects a valid
   op (server bug or schema drift — contract tests should have caught it), (b) client sends a
   malformed op (client bug), (c) genuine conflict needing the manual-merge path, (d) a poisoned row
   that can never succeed (e.g., referencing a tombstoned entity).
3. **Contain:** if the dead-letters are caused by a deployed server change, pin/roll back the server
   build; if caused by a client build, flip its feature flags / reduce stage. True per-user conflict
   cases: keep the manual-merge flow; these are not a fleet incident.
4. Handle the parked rows: rows that can succeed after the fix are **re-driven** (flip from
   DEAD_LETTER back to PENDING) once per incident, after CI regression proves the op is accepted
   again. Rows that cannot succeed are logically cleared with a server-side tombstone of the op,
   never by deleting user data.
5. Add a unit test reproducing the dead-letter op type; wire it into `OutboxAndCursorTest.kt`
   (`architecture.md`).

**Verify fix:**
- Dead-letter rate returns < 1 %/h; re-driven rows flush (outbox drain observed on the dashboard);
   no new dead-letters in 24 h for the cohort.

**Rollback:**
- If a client op format is involved, the server should reject it **forward-compatibly** (ignore +
   log, not dead-letter), and the client fixed in PATCH. If a server merge/policy regression is
   involved, revert that diff. Site rollback of the delivered status of parked rows is not needed —
   re-drive replaces it.

---

### RB-05 Notification delivery drop

Triggering alert: notification delivery rate **< 95 %** (panel 6) or push-to-first-open p95
**> 10 min** (panel 7) sustained 20+ min.

**Symptoms (with tool):**
- Panel 6/7 alarmed; Notification Service metrics show provider rejects (FCM/APNs `token_state`
   errors: `UNREGISTERED`, `BadDeviceToken`, `ExpiredToken`, provider 5xx/quota).
- Token churn: high invalid-token rate implies clients never refreshed push tokens (registration
   bug) or `aps-environment`/FCM sender-id drifted after a re-sign.
- App-side signs (panel 3/4) coincidence: push is only a *wake-up hint* (`architecture.md`), so a
   delivery drop shows up as lower push-to-first-open and stale reminders, rarely as a sync failure.

**Severity:** SEV3 (SEV2 if reminders effectively stop for > 30 % of users).

**Response steps:**
1. Read provider delivery reports (FCM message metrics / APNs feedback) and the Notification Service
   reject breakdown; verify `token_state` categories.
2. Check registration integrity: is the app refreshing the push token post-reinstall/re-sign? Is the
   APNs auth key (`.p8`) or FCM sender still valid — a rotated/expired push credential is a classic
   cause (see `secret-rotation-policy.md` for the secret it is).
3. **Contain:** server-side, auto-invalidate `UNREGISTERED`/`ExpiredToken` rows so send loops stop
   burning quota; adjust the reminder schedule if provider throttling is active. Client-side, trigger
   token re-registration on a release (it is already a release-process step) and on `app_open`.
4. If caused by a newly rotated APNs/FCM credential, rotate again cleanly (dual-validity window) and
   re-test with a device.
5. If it is a client build regression, flag/stage/unpublish that build and ship the PATCH.

**Verify fix:**
- Panel 6 ≥ 95 %, panel 7 p95 < 10 min; a device on the affected cohort receives a reminder and the
  `notification_tapped`/`app_open` chain shows the expected delivery → open latency.

**Rollback:**
- Temporarily schedule via local-only reminders (app-side, no provider) by flipping a reminder
  feature flag OFF + a "local schedule only" control-plane value; restore provider scheduling once
  delivery metrics recover. Binary rollback per standard section 2 if a build caused it.

---

### RB-06 Certificate pin rotation failure

Triggering alert: TLS connection failures that follow a pin change — sync success rate drops while
clients report pinning/SSL errors; `trace_id` value fails handshake. Also any `tls.pins.*` override
set test failing (`secret-rotation-policy.md` §3; STRIDE §2 tests).

**Symptoms (with tool):**
- Network stack errors (`certificate validation failed` / `SPKI mismatch`) in crash/telemetry
   (Sentry/Logs) rising after a pin rotation event; panels 3/4 degrade because sync cannot reach the
   gateway.
- `remote_config_applied` shows `tls.pins.override` adoption; client telemetry still references the
   old pin (`secret-rotation-policy.md` §3 step 5 deprecation check).
- Usually SEV2+ *always*: it is a connectivity regression affecting the fleet.

**Severity:** SEV2 (SEV1 if > 50 % of clients can't reach the API).

**Response steps:**
1. Confirm which pin set clients hold vs what the server presents (inspect server cert SPKI; compare
   with the published pins in the last release and the control block).
2. Classify: (a) new cert's SPKI missing from the client's pin list — a rotation-order bug
   (the overlap rule was skipped); (b) old pin removed too early; (c) a compromise-initiated
   `deny` state already active (see RB-09).
3. **Contain:**
   - Case (a)/(b): push an emergency **`tls.pins.override`** via remote config that adds the missing
     SPKI as a second pin (overlap), or re-point the server at the cert whose SPKI is still pinned —
     *do not* wait for an app release.
   - Case (c): if `deny` was the agent and the current pins are compromised, roll **forward** to an
     emergency fresh pin pair from HSM material that was never exposed, then remove the compromised
     pin (never "roll back" to a leaked pin — `secret-rotation-policy.md` §8).
4. After the override is applied, re-run the STRIDE §2 test matrix in staging: negative MITM fails,
   positive pin succeeds, rotate-overlap succeeds.
5. Restore the *declared* rotation cadence and update the rotation log.

**Verify fix:**
- Panels 3/4 recover for clients with the old and the new pin (both must work in overlap);
   adoption telemetry shows > 99 % of online clients on the new pin set within the overlap window;
   the negative-pin test still fails (pinning is intact, not disabled).

**Rollback:**
- Pin changes are the one place where "rollback" means **add the previously-served pin back into the
   active set** (dual-pin overlap), not "revert to old cert": the two-pin model tolerates it with
   zero outage. If a compromised cert is involved, roll forward to a fresh key pair, never back to
   the compromised one.

---

### RB-07 OAuth token reuse detection / family revocation

Triggering alert: auth failure rate **> 5 %** (panel 8) or a burst of `auth_session_revoked`
events; server reuse-detection logs an alert on replay of a refresh token (MASVS-AUTH-4).

**Symptoms (with tool):**
- Panel 8 spike; `auth_signin_failure`/`auth_session_revoked` events up; Auth Server logs show
  `refresh_token_reuse` for specific `session` families.
- Users report "signed out / re-enter password" en masse (that is the designed behavior of family
  revocation) — differentiate a *legitimate* large wave (release forcing re-auth) from an *attack*
  pattern (a cluster of reuse detections from unusual geos or earlier than expected).
- Instrumentation: OTel `auth.token.verify` + `auth.refresh` spans for `family_revoked` outcome.

**Severity:** SEV2 (SEV1 if it is a confirmed token-theft/compromise at scale where attackers mint
sessions).

**Response steps:**
1. Confirm the reuse events are real: inspect server-side RSA/hashing of the presented refresh
   token vs stored, per-family; count families affected; check for a concurrent same-family burst
   (second device legitimately refreshing both = false positive, see step 5).
2. **Contain an attack:**
   - Immediately revoke the affected families (already automatic) and:
     - rotate the OAuth **signing key kid** if the token signature itself is suspect
       (`secret-rotation-policy.md` §5 dual-key, accelerable);
     - check for client-side exfiltration (repackaged app, rooted device) per STRIDE §3/§1;
     - enable `deny`-tier network controls only if API issuance is actively compromised (RB-09).
   - Affected users re-auth manually; the app's `auth_session_revoked` UI path already forces a
     re-check (a feature of MASVS-AUTH-4).
3. **Contain a false positive:** if reuse was triggered by legitimate multi-device concurrent
   refresh (e.g., a new device joins while another refreshes on a bad network), the firing class is
   too strict: raise the tolerance (short grace window for refreshes already-in-flight per family)
   and ship the fixed policy; redeploy, then re-test the replay scenario in CI to prove real replay
   *still* revokes.
4. Post incident: review the reuse-detection thresholds, trip-wire coverage, and audit the rotation
   log; re-run the CI replay test (`masvs-l2-checklist.md` MASVS-AUTH-4).

**Verify fix:**
- Panel 8 back < 5 %; a **replayed old refresh token still triggers family revocation** in the CI
   harness; a legitimate two-device simultaneous refresh does **not** (if that was the regression);
   affected users re-authenticated successfully.

**Rollback:**
- For an attack response, rollback = rotate forward (new kid, families re-issued); never restore a
   revoked family from data without a security review. For a false-positive regression, rollback is
   reverting the detection-code diff and redeploying, then re-validating with §5 of
   `secret-rotation-policy.md`.

---

### RB-08 Billing / receipt verification failure

Triggering alert: dashboard billing error rate → enroll as an SLO panel; App Store Server V2 /
Play RTDN (Real-Time Developer Notifications) webhooks failing; receipt/transaction verification
endpoint erroring; users report "paid feature locked after purchase".

**Symptoms (with tool):**
- `billing.service.verify` spans failing (5xx/timeout) or webhook endpoints returning 4xx/5xx;
   provider webhook deliveries are retried by the store; our consumer may be stuck.
- Verification failures split by store and reason (`invalidSignature`, `expiredTransaction`,
   `tamperedReceipt`, provider outage).
- Client-visible: entitlement state never updates → ads/limits shown despite purchase
   (`entitlement sync` path via sync).

**Severity:** SEV2 (SEV1 if purchases at scale are stuck/lost or store integration is down).

**Response steps:**
1. Confirm which leg fails: provider webhook delivery (check App Store Server V2 receipts / RTDN
   delivery logs and the provider status page), our verification endpoint, or our entitlement
   storage/worker.
2. **Contain:**
   - Provider/outage leg: our service should retry webhooks with exponential backoff and mark
     entitlements "pending" (queueing is safe). Ensure the queue drains — do not drop events.
   - Our-endpoint leg: pin/roll back the Billing Service deployment (stateless retry is safe).
   - Entitlement-storage leg: verify the entitlements table and the outbox that carries entitlement
     grant to clients; re-drive grants idempotently (UUIDv7 keys).
3. For tampered-receipt pattern: confirm it is attempted fraud (STRIDE-framed) and *reject + log*
   only; never issue entitlement. Keep reason codes in telemetry so support can triage.
4. Reconciliation: after recovery, reconcile **play-time server state vs store truth** from the
   provider's purchase-retrieval APIs for the affected window; issue entitlements or refunds per
   provider policy (dot-product of `entitlement_grant` ops).

**Verify fix:**
- A synthetic purchase goes: store → webhook → verification → entitlement grant → client sync
  (observed end-to-end with `trace_id`); billing error panel back to zero for the window; no double
  grant (idempotency key held).

**Rollback:**
- Server code: revert the deployment. Grants already issued: additive re-grant is safe; **never**
  revoke entitlements to "undo" a double-grant without a monetary/legal review. If store revocation
  is needed (fraud), use the provider's refund/revoke API, not the data layer direct.

---

### RB-09 Remote-config kill switch activation (and deactivation)

This runbook covers **operating** the kill switch defined in `release-plan.md` §4. It is the
containment weapon for RB-01/03/06/07-class and security incidents.

**Symptoms prompting activation:** a confirmed SEV1 with remote reachability (fleet-wide crash —
attempted stop even if it does not hurt), active compromise observed via network (pin override
needed), a poisoned remote feature rapidly harming users, or a directive to blank network/remote
capability while a hotfix builds.

**Severity:** activation itself is a response, not a symptom; the *underlying* incident defines
severity.

**Response steps (activate):**
1. Confirm the incident qualifies: remote-gated impact that a fresh build cannot stop soon enough.
2. Publish the signed control block with `app.kill_switch = true` (and any `severity`), and mirror
   it in Firebase Remote Config as the second channel. Both are signed; invalid blocks are ignored
   (`release-plan.md` §4, `secret-rotation-policy.md` §7 rule 4).
3. If the concern is network-layer (pinning/compromise), also set `tls.pins.override` — either to an
   emergency pin set or `deny` (`secret-rotation-policy.md` §3).
4. Set the API-gateway **`app.min_supported_build` ceiling** (refuse traffic from any build older
   than the safety point) as the server-side backstop for offline stragglers.
5. Watch `remote_config_applied` and panels 3–8; confirm the harmful path is dead and the blast
   radius did not widen. Communicate a brief "we have disabled X" notice on the status page unless a
   stealth response is required (attack), in which case keep it internal until the incident is over.
6. When ready to recover: ship the fix, then deactivate in **reverse order** — remove the gateway
   ceiling first only after the new build passes gates, then publish `kill_switch = false`, then
   `tls.pins.override` back to `trusted` (or the rotated pins).

**Verify fix (for the activation it was meant to contain):**
- The target behavior is positively confirmed disabled on a live device (the app applies the block
   within < 5 min per `remote_config_applied`); the incident metric(s) stopped moving (panels
   1–8 reached their goal).
- After deactivation: panels 1–8 green for 24 h; no blind-spot (need to also confirm the new control
   block actually re-enabled previously-degraded functions on several device builds).

**Rollback (of the kill switch itself = deactivation):**
- If the activation causes *more* harm than it prevents (e.g., blocking an app whose critical local
   affordances were mistakenly tied to the network), publish `kill_switch = false` immediately —
   the signed control block is versioned, so a newer block supersedes an older one; old devices
   simply keep the last block they saw until they connect. A wrong `deny` for pin compromise is
   rectified only by publishing a fresh trusted/emergency pin set — never by leaving `deny` in
   place (STRIDE §2, `secret-rotation-policy.md` §3 "Rollback of a deny state").
- Never delete control blocks mid-incident; keep the full history for the postmortem and audit.

---

## Appendix: alert → runbook map

| Alert group (`observability.md` §4) | Runbook |
|---|---|
| 1 crash-free < 99.5 % / new crash > 0.1 % | RB-01 |
| 2 ANR > 0.47 % | RB-02 |
| 3 sync p95 > 2 s | RB-03 |
| 4 sync success < 97 % | RB-03 |
| 5 dead-letter > 1 %/h | RB-04 |
| 6 notification delivery < 95 % | RB-05 |
| 7 push-to-first-open p95 > 10 min | RB-05 |
| 8 auth failure > 5 % / family-revoke burst | RB-07 |
| `tls.pins.*` override test failures | RB-06 |
| billing verification / RTDN failures | RB-08 |
| (operational) kill switch needed | RB-09 |