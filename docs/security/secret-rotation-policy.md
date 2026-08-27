# Athkar Secrets & Certificate Rotation Policy

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Document version: 1.0
Owner: Security Engineering / Platform Infrastructure

This policy defines rotation cadence, execution procedures, no-outage guarantees, and the
revocation/rollback path for every cryptographic secret and certificate that Athkar relies
on across the mobile clients and supporting backend.

---

## 1. Secret inventory

| Secret / material | Where used | Owner | Primary store |
|---|---|---|---|
| TLS certificate (leaf + chain) | HTTPS serving of Athkar API + static content | Infrastructure | Backend TLS terminator / CDN |
| TLS public-key pins (SPKI) | Client-side certificate pinning of API host | Platform | Embedded in client + remote-config override |
| API keys (server → upstream data providers) | Prayer-times / geo / weather data feeds server-side | Backend | Secret manager (e.g., KMS / Vault) |
| OAuth signing key | Token issuance and validation | Identity service | HSM / KMS backed |
| Mobile signing keys (Android upload/release, Apple) | Code signing of shipped binaries | Release engineering | Play App Signing / Apple notarization, plus CI keystore |
| DB encryption key (SQLCipher) | App at-rest DB | Client | iOS Keychain / Android Keystore (StrongBox) — rotated on re-key, not by server |

---

## 2. Rotation cadence summary

| Material | Cadence | Notes |
|---|---|---|
| TLS certificate (leaf) | Every 90 days (max 398-day, aligned to CA constraints) | Renewal, not pin change, unless host changes |
| TLS public-key pins | Every 60 days | Two pins active during overlap; see §3 |
| API keys | Every 90 days, or immediately on suspicion | Overlap of old+new where the provider allows dual validity |
| OAuth signing key | Every 180 days; dual-key overlap | See §5 |
| Mobile signing keys | Only on compromise or key-deprecation (Play/Apple governed) | Elevated, human-gated procedure; see §6 |
| DB encryption key (client-managed) | On user re-enrollment / re-key event only | Never a scheduled fleet rotation — user-scoped |

---

## 3. TLS pin rotation (60-day cycle with overlap)

### The two-pin model

At any time the client trusts exactly **two public-key (SPKI) pins**:

- `Pin A` — the currently-served certificate's public key.
- `Pin B` — the next/backup key that will become the server's certificate.

Because both are published in the client and via remote config, the server can move from the
certificate whose public key is pin A to the certificate whose public key is pin B with no
outage: the client accepts either.

### Rotation procedure (no fleet outage)

1. Generate a new key pair in a secure HSM/KMS. Do **not** yet issue a cert for it.
2. Add the new SPKI as a second accepted pin alongside the current one, via both a client
   release and the remote-config override (`tls.pins.add`). Verify the new pin is accepted
   in staging/CI (rotation-overlap test).
3. Issue a new TLS certificate signed against the new key pair and deploy it to the terminator
   (CDN/load-balancer). Because the old pin is still active, clients that still cache only the
   old pin keep working — this is the overlap window.
4. Serve for the overlap window (recommended 14 days, minimum until the new pin's adoption
   telemetry reaches >99% of clients).
5. Deprecate the old pin via remote config (`tls.pins.remove`) after overlap, and confirm
   client telemetry no longer references it.
6. Repeat the cycle every ~60 days. The next pin becomes the "next" pin at the start of each
   cycle.

### Emergency kill switch

The remote-config override `tls.pins.override` supports three states:

- `trusted` (default) — pins as published above.
- `override:<pins>` — replace pins with an emergency-issued pin set pushed instantly without
  an app release (used when the current pin's private key is suspected compromised).
- `deny` — block all Athkar network traffic until an official release or override is pushed
  (used for active compromise / data-incident containment).

The override is signed and versioned in remote config; invalid overrides are ignored.

### Abort/rollback

- If adoption telemetry is low, extend the overlap before removing the old pin (no outage —
  both pins remain valid).
- If a newly generated key is corrupted or lost before the cert is served, simply keep the
  old pin set and restart the cycle — the old cert remains valid.
- Rollback of a `deny` state: only re-enable traffic after the compromise is contained and
  fresh pins are published.

---

## 4. API key rotation (no-outage overlap)

- Server-to-upstream API keys are stored in the secret manager and injected at runtime; never
  in app config or source.
- Rotation: issue a new key with the upstream provider, declare dual validity, deploy the new
  key to a staging slice, move traffic incrementally (canary → 10% → progressive → 100%),
  then disable the old key after a full observation window.
- Client-visible API keys: the app uses **no static API key**; all authenticated endpoints use
  OAuth tokens minted server-side, so client-visible key rotation is **not** an app-surface
  concern.
- Revocation: immediate disable in the secret manager + upstream portal on suspicion; canary
  rollback of the serving version while the old key is re-enabled from backup only if
  declared safe.

---

## 5. OAuth signing key rotation

- The identity service signs tokens with a key held in an HSM/KMS. Rotation uses a
  **dual-key (kid) scheme** so tokens signed with the old key stay valid while the new key
  comes online; the correct key is selected per-token by `kid`.
- Cadence: every 180 days, or immediately on a key/HSM compromise event.
- Procedure:
  1. Generate a new signing key and publish it in the key registry with a new `kid`.
  2. Configure the validator to accept both old and new `kid` for the overlap window.
  3. Begin signing new tokens with the new key; old keys validate by `kid`.
  4. After the longest refresh-token lifetime + margin, deprecate the old `kid` from
     validation.
  5. Rotate refresh-token secrets independently using the reuse-detection family-revoke path
     from MASVS-AUTH-4 when this event is triggered by compromise.
- Outage guarantee: because both `kid`-keys validate during overlap, no request is rejected
  during transition. Rollback = instantly re-enable the old `kid` in the validator.

---

## 6. Mobile signing-key management (Play App Signing / Apple)

### Android — Play App Signing

- The developer-facing **upload key** is used only to upload; the **final signing key** is
  held by Google Play and never enters the build pipeline.
- The upload key lives in a hardware-backed CI keystore with access restricted to the release
  pipeline service account; the passphrase is in the secret manager and gated by secret owner
  approval.
- Rotation of the upload key: generate a new upload key, upload the new public certificate to
  Play Console, sign the next release with the new key. No client re-enrollment is needed
  because end users validate against the Play-managed final key.
- Revocation: if the upload key leaks, immediately generate a new one and update Play Console;
  the Play-managed final key is unaffected.

### Apple — App Store Connect / notary

- iOS app signing uses Apple-managed distribution certificates + a provisioning profile in
  CI. Rivet the distribution certificate `fingerprint` so only the approved cert signs.
- Rotation: issue a new distribution certificate in the Apple portal, configure CI to use it,
  regenerate provisioning profiles, and sign the next release; retire the old cert after it
  expires or no profile references it.
- Revocation: revoke a compromised cert in the portal immediately — this invalidates signed
  artifacts until a new cert is provisioned (an elevated, human-gated incident, not a
  scheduled event).

General rule for both platforms: signing-key changes are never a scheduled background job;
they require an approved change request, a rehearsed runbook, and a blameless postmortem if
they cause a release delay.

---

## 7. Execution guardrails (apply to all rotations)

1. **Dual/overlap keys first.** Every rotation that touches transport (TLS pins, API keys,
   OAuth signing, lint/oversight) keeps the old and new valid simultaneously until adoption
   or lifetime is exhausted. No hard-cutover.
2. **Canary + staged rollout.** Push to a staging/canary slice and a low-traffic tag before
   global rollout; gate on error-rate and pin-adoption telemetry.
3. **Automated adoption telemetry.** Track per-version pin adoption and token-validator
   `kid` usage so a "safe to deprecate" decision is evidence-based.
4. **Signed remote-config overrides.** All client-facing pin overrides are signed/versioned
   and validated before use; a tampered override is ignored.
5. **Rehearsed runbook.** Every rotation type has a dry-run script executed in staging before
   production.
6. **Approved change control.** Scheduled rotations run under a low-risk change ticket;
   emergency rotations run under the incident process with a post-incident review.

---

## 8. Revocation & rollback procedure

| Scenario | Trigger | Immediate action | Rollback | Recovery test |
|---|---|---|---|---|
| TLS pin compromise | Private key of pinned cert leaked/suspected | Set `tls.pins.override` to an emergency pin set or `deny` via remote config | Re-pin to a fresh key; remove the compromised key from acceptance | Re-run negative MITM + rotation-overlap + kill-switch tests (STRIDE §2) |
| API key leak | Key appears in logs/code/public repo/provider alert | Disable key in secret manager + upstream portal immediately | Re-enable only if the provider declares it safe and access logs show no misuse | Integration test that legacy key is rejected after revocation point |
| OAuth key compromise | HSM incident or validation anomaly | Remove old `kid` from validator; bump urgency of full rotation | Re-add old `kid` instantly (dual-key design) to restore continuity | Token issued under old `kid` rejected; new `kid` accepted |
| Mobile signing-key leak (Android upload) | Secret-manager audit / access anomaly | Generate new upload key & update Play Console; revoke old | Re-issue key and re-enroll release pipeline; end users unaffected | Signed artifact validates against the new upload key + Play final key |
| Mobile signing-key leak (Apple) | Portal/cert audit | Revoke distribution cert; regenerate profile | Provision a new cert; no rollback of a revoked cert | Fresh build signed/notarized; revoked cert rejected |

### General rollback principles

- **Rollback must never leave the fleet without valid material.** The two-key/overlap design
  guarantees continuity during both forward rotation and rollback.
- **Emergency actions are reversible only to the extent the old material is still secret.**
  Once leaked material is public, do not roll back to it — roll **forward** to new material.
- **Every incident ends with** a root-cause write-up, an updated runbook, a refreshed
  rotation schedule, and re-run of the corresponding STRIDE tests and MASVS checklist rows.

---

## 9. Ownership & review

- Rotation cadence and incidents are owned by Security Engineering; release-pipeline signing
  is owned by Release Engineering; TLS and API-key material by Infrastructure/Backend.
- This policy is reviewed quarterly and after every security incident.
- All rotations are logged (who, what, when, why) for auditor review.
