# Athkar STRIDE Threat Model

App: Athkar (Arabic/English Islamic dhikr + prayer-times, offline-first)
Document version: 1.0
Scope: Native Android + iOS mobile clients and the supporting backend surfaces reachable from the device.

The model below applies the STRIDE methodology to six attack surfaces most relevant to a
production offline-first mobile app that stores the user's daily dhikr data, prayer-tonight
configuration, and authentication session material on-device.

---

## How to read this document

Each attack surface lists one or more threat entries. Every entry defines:

- **STRIDE category** — the threat taxonomy letter (Spoofing, Tampering, Repudiation,
  Information Disclosure, Denial of Service, Elevation of Privilege) and a short rationale.
- **Attack scenario** — a concrete, realistic exploitation path.
- **Control** — the technical mitigation that prevents or bounds the attack.
- **Test** — the verification that proves the control holds.

---

## Summary table

| Surface | STRIDE | Attack scenario (condensed) | Control (condensed) | Test |
|---|---|---|---|---|
| Device theft | I (Information Disclosure) | Attacker steals device, boots it, reads at-rest data | Full-disk via FileVault/Android FBE + Keychain/Keystore-only secrets with `WhenUnlockedThisDeviceOnly` / StrongBox, no plaintext at rest | Regression test that secrets are unreachable after reboot before first unlock / from a second OS install |
| Network interception | I, S | Attacker on same Wi-Fi runs rogue AP/MITM and strips TLS | TLS 1.3 with the platform network stack + certificate pinning (2 public-key pins, 60-day rotation) | Disable pin -> connection refused; valid pin -> success; pin-rotation overlap succeeds |
| Repackaged/modified app | T, R | Attacker re-signs a patched APK/IPA and redistributes | Play App Signing / Apple signing + runtime self-integrity checks + signing-key pinning | Tamper an APK; installer/key attestation revokes it; tamper detection fires at runtime |
| Backup/cloud leakage | I | iCloud/Google backup restores app data onto attacker-controlled device | Exclude data from iCloud/ADB/Google backups + SQLCipher AES-256-GCM for backup payloads | Verify device-transfer/backup excludes app directory; restored DB is unreadable ciphertext |
| Deep-link abuse | S | Malicious link invokes app with forged deep-link parameters | Universal Links / App Links bound to company domain + parameter allow-listing + state validation | Malicious non-bound app fails to open; deep link only accepts validated parameters |
| Inter-app channel attacks | S, E | Malicious app sends crafted intents/URL-schemes to exfil or spawn actions | Intent validation, `exported=false`, signature-level permission, return-result validation | Crafted malicious intent is rejected; only verified sender can trigger privileged actions |

---

## 1. Device theft

### STRIDE: Information Disclosure

An offline-first app stores meaningful personal data (dhikr history, streaks, prayer
preferences, and the OAuth refresh token that can mint new access tokens). Losing physical
possession of the device is therefore a real and high-impact disclosure path.

### Attack scenario

1. An attacker steals the phone while it is locked and unattended.
2. They attempt to read the app sandbox directly, or they flash a custom ROM / use a
   device-transfer or cloud-restore flow that reconstructs the app sandbox on a device
   they control.
3. Without proper at-rest protection, the app's SQLite database, auth tokens, and
   preferences are readable.

### Control

- **Keychain / Android Keystore with hardware-backed keys.**
  - iOS: auth tokens and DB keys are stored in the Keychain with
    `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. This class binds the secret to the
    device (not migratable to a backup) and only releases it while the device is unlocked.
  - Android: symmetric keys live in **Android Keystore** with **StrongBox**-backed keys
    (when the device provides a StrongBox secure element) and `setUserAuthenticationRequired`
    where appropriate. Keystore keys never leave the secure hardware.
- **SQLCipher / AES-256-GCM database.** The on-device SQLite database is encrypted with
  SQLCipher using AES-256-GCM. The encryption key is itself stored only in
  Keychain/Keystore and is never written to disk or to source.
- **No secrets in plaintext stores.** SharedPreferences / UserDefaults, plists, files, and
  source code contain zero secrets. All secrets are retrieved at runtime from the secure
  store.
- **Full-disk encryption baseline.** The OS-level encryption (Android file-based encryption
  / iOS data protection) is relied on for the general sandbox while the app's own crypto
  protects the DB independently.

### Test

- **Keychain/Keystore reachability test (CI + device farm):** on a physical or emulated
  device, write a secret, reboot, and verify that the secret is only readable after the
  first unlock and that `ThisDeviceOnly` material is not present after the device is wiped.
- **DB at-rest test:** encrypt the DB, then read the SQLite file bytes on disk; assert the
  content is ciphertext (no plaintext string from the schema is present) and that opening
  the file without the key yields "file is not a database".
- **Backup-export test:** perform an iCloud/Android backup and confirm the app data blob is
  either excluded, encrypted, or its Keychain/Keystore material is absent.

---

## 2. Network interception

### STRIDE: Information Disclosure, Spoofing

The app is offline-first but still fetches prayer-times data and OAuth tokens over the
network when connectivity is available. Network traffic is exposed on shared Wi-Fi, mobile
carrier links, and hostile proxies.

### Attack scenario

1. An attacker operates a rogue access point (or ARP-poisoning / captive-portal) on the
   same network the user joins.
2. They attempt a TLS man-in-the-middle using a forged or mis-issued certificate for the
   Athkar API domain.
3. Lacking certificate pinning, the app trusts the fake certificate; the attacker then
   reads or modifies prayer-time responses and the OAuth exchange.

### Control

- **TLS 1.3 enforced end-to-end.** The app requires TLS 1.3 to Athkar API hosts and rejects
  TLS 1.2 and below. (TLS 1.3 provides forward secrecy and removes legacy cipher suites.)
- **Certificate / public-key pinning with two public-key pins.** The app pins the expected
  SPKI (Subject Public Key Info) pins of the TLS certificates. Two pins are always active:
  - the current primary pin, and
  - a backup/next pin, so that rotation does not break the fleet (see
    `secret-rotation-policy.md`).
- **60-day pin rotation with overlap.** Pins rotate every ~60 days while both old and new
  pins remain valid during the overlap window, guaranteeing no window of connectivity loss.
- **Emergency kill switch via remote config.** A remote-config flag (`tls.pins.override`)
  lets the operations team invalidate a compromised pin and push an updated pin set or a
  `null` (block all traffic) state without shipping an app update.
- **No TLS stripping fallback.** There is no plaintext-HTTP fallback anywhere; HSTS-grade
  behavior is enforced app-side independently of server headers.

### Test

- **Negative pin test:** configure a MITM proxy (e.g., mitmproxy) presenting its own root CA;
  assert the connection is refused with a certificate/pinning error and no data is exchanged.
- **Positive pin test:** present the correct pinned SPKI; assert the exchange succeeds.
- **Rotation-overlap test:** set pin A alone and validate; then add pin B alongside A and
  validate that a server presenting B still succeeds while A is still cached; then remove A.
- **Kill-switch test:** set the remote-config override to a bogus pin and confirm all
  network calls fail fast with the documented error; restore and confirm recovery.
- **Protocol test:** force a TLS 1.2 handshake and assert the app rejects it.

---

## 3. Repackaged / modified app

### STRIDE: Tampering, Repudiation

The most common mobile-delivery attack is redistributing a modified build signed with a
stolen or new key, then convincing users to sideload it (e.g., a "modded Athkar with
unlimited streak").

### Attack scenario

1. An attacker unpacks the IPA/APK, modifies the bytecode (adds exfiltration of the OAuth
   token, disables root checks, injects arbitrary JavaScript), re-signs it with their own
   key, and publishes or side-loads it.
2. A user installs the modified binary. The attacker's code now runs inside the app's
   identity and can call backend APIs with legitimate-looking but untrusted behavior.
3. Backend logs and billing end up attributable to the victim (reputation/repudiation harm),
   and the attacker gains the user's session material.

### Control

- **Trusted signing chains.**
  - iOS: Apple notarization + App Store distribution guarantee that the binary is the
    exact one submitted, signed by a key controlled by the company.
  - Android: **Play App Signing** (or a locked signing key) means the release key the
    platform trusts is never the developer-facing key exposed to the attacker.
- **Runtime self-integrity / anti-tampering checks.**
  - Verify the app's own code-signature / checksum at startup against a value derived from
    the trusted binary (iOS `SecStaticCodeCheckValidity`; Android signature hash compared
    against the value embedded at build time and re-verified against the runtime APK).
  - Emit a tamper-detection event when verification fails and refuse to continue with
    privileged features.
- **Remote-config signing-cadence control.** Signing keys are rotated on the cadence
  defined in `secret-rotation-policy.md`; a leaked developer key is revoked by uploading a
  new key to Play/App Store Connect and rotating.
- **No secrets embedded in the binary.** Even if the binary is unpacked, it contains no
  recoverable secrets — all keys are injected at runtime (see Device theft), so a repackaged
  build gains nothing of value by itself.

### Test

- **Re-sign test:** modify one byte/instruction, re-sign, and verify that (a) the platform
  rejects the non-matching signature where applicable, and (b) the runtime integrity check
  flags the change and disables privileged flows.
- **Signature-hash test:** compute the expected signature hash at build time, tamper with
  the binary, and assert the runtime comparison fails.
- **Static-scan test (CI):** run the produced binary through `apksigner verify` (Android)
  and `codesign --verify` (iOS) as a gate in the release pipeline.

---

## 4. Backup / cloud leakage

### STRIDE: Information Disclosure

Automatic OS backups and device-to-device transfers replicate the app's data directory and
Keychain/Keystore material to clouds or other devices the user does not fully control.

### Attack scenario

1. The user's Google Drive or iCloud account is compromised, or the user backs up to a
   service and the backup lease is shared.
2. The backup contains a plaintext copy of the Athkar database and preferences because the
   app did not opt out of backup inclusion.
3. The attacker restores the backup onto their own device/emulator and reads the data, or
   extracts the database directly from the backup archive.

### Control

- **Backup exclusion.**
  - iOS: set `NSURLIsExcludedFromBackupKey` on the app's data directory so it is never
    copied to iCloud/iTunes backups; encrypt everything else that must persist.
  - Android: set `android:allowBackup="false"` (and disable `fullBackupContent` /
    `dataExtractionRules`), and add the app to the device-transfer exclusion so a transfer
    does not carry the database.
- **SQLCipher AES-256-GCM.** Because some OS/file-level exclusions can be bypassed by
  specialized restore tooling, the data at rest is independently encrypted anyway. A backup
  that does capture the DB file only captures ciphertext.
- **Keychain material is non-migratable.** Tokens/keys use `ThisDeviceOnly` semantics so
  they do not travel with a restore to another device, making a restored-on-attacker-device
  copy unable to decrypt the DB it copied.

### Test

- **Backup-content test:** run the OS backup (iCloud/ADB/`adb backup`) and assert the
  Athkar data directory is absent from the archive.
- **Transfer test:** perform a device-to-device transfer and confirm the app DB and keychain
  are not carried over.
- **Restore-decrypt test:** attempt to open the captured SQLite file without the Keychain
  key; assert it is unreadable ciphertext.

---

## 5. Deep-link abuse

### STRIDE: Spoofing

Athkar registers deep links (e.g., to open a specific prayer-timing card, complete a dhikr
set, or resume a session). Malicious web/app links can invoke these handlers with forged
arguments.

### Attack scenario

1. An attacker controls a domain or sends a link that claims to be Athkar ("share a prayer
   reminder").
2. On Android/iOS, a generic scheme (e.g., `athkar://...`) is registered so any app or web
   page can route into the handler with attacker-controlled parameters.
3. The handler parses parameters it does not validate, so the attacker exfiltrates state or
   triggers a destructive action with a "trusted" origin.

### Control

- **Web-based intent resolution (Universal Links / App Links).**
  - iOS: `associated-domains` with `applinks` bound to the company domain; the OS only
    opens the asset-link-verified domain.
  - Android: App Links declared via `android:autoVerify="true"` + `assetlinks.json` on the
    company origin, plus the legacy `<intent-filter>` scheme handle kept but treated as
    untrusted.
- **Parameter allow-listing and validation.** Every deep-link parameter is validated against
  a strict allow-list (types, ranges, allowed values). Unknown, malformed, or out-of-range
  parameters are rejected, never defaulted open.
- **No privileged actions via links.** Deep links only carry non-sensitive navigation
  (e.g., open a screen). Session-mutating or privilege-escalating actions require inline
  re-authentication and app-owned confirmation.
- **Origin binding.** Actions taken because a link opened are tagged with the validated
  origin and never trusted for security-critical decisions.

### Test

- **Domain-verification test:** attempt to open the app from a non-bound domain/page;
  assert the OS refuses to route it to the app.
- **Allow-list test:** fuzz deep-link parameters with overlong strings, negative numbers,
  out-of-enum values, and injection payloads; assert they are rejected and no crash/exfil
  occurs.
- **Privileged-action test:** craft a valid-looking link that attempts a state mutation;
  assert it fails without inline authentication.

---

## 6. Inter-app channel attacks

### STRIDE: Spoofing, Elevation of Privilege

Android intents, iOS URL-scheme registrations, and shared activities are live channels by
which a collocated malicious app can interact with Athkar, potentially spoofing a caller or
triggering privileged behavior.

### Attack scenario

1. A malicious installed app sends a crafted `Intent` (e.g., an implicit intent with the
   Athkar content authority, or a `startActivityForResult` masquerading as a legitimate
   caller) to Athkar.
2. Athkar's broadcast/activity/service is implicitly exported (or exported without a
   permission check) and it processes the intent, believing it comes from the system or a
   trusted app.
3. The attacker leverages the handling to read protected state, trigger actions with
   elevated privileges, or harvest returned result data.

### Control

- **Explicit non-export where possible.** All Components (Activity/Service/BroadcastReceiver
  and equivalent iOS URL-scheme handlers) default to not exported; each is exported only when
  an actual external consumer exists and that decision is reviewed.
- **Signature/caller validation.**
  - Android: exported components require `signature`-level permissions so only apps signed
    with the Athkar key (i.e., first-party) can invoke them; third parties are rejected.
  - Validate the caller package UID / signing certificate at runtime before honoring any
    intent.
- **Return-result validation.** In `startActivityForResult` style flows, validate that the
  returned data and `resultCode` come from the expected, verified component and are not
  blindly trusted.
- **No sensitive data in intent extras that cross the boundary** unless required, and never
  secrets.
- **App-scheme rejection on iOS.** Generic `athkar://`-style scheme handles are non-exported
  to unknown apps; privileged interaction is done through verified App Intents /
  application intents where possible.

### Test

- **Malicious-intent test:** from a test-only throwaway app, dispatch a crafted implicit
  intent to an Athkar exported component; assert it is rejected or requires the
  signature-level permission and no data is returned.
- **Exported-audit test (CI):** statically scan the manifest for `exported="true"` and
  assert no secret-receiving or privilege-escalating component is implicitly exported.
- **Caller-validation test:** mock a calling package with a different signing key and assert
  the Athkar component refuses the call and logs a security event.
- **Result-validation test:** have the harness return a malformed result and assert Athkar
  discards it.

---

## Threat-priority summary

| Surface | STRIDE | Likelihood | Impact | Priority |
|---|---|---|---|---|
| Device theft | I | Medium | High | P0 |
| Network interception | I, S | Medium | High | P0 |
| Repackaged app | T, R | Medium | High | P1 |
| Backup/cloud leakage | I | Low-Medium | High | P1 |
| Deep-link abuse | S | Low | Medium | P2 |
| Inter-app channels | S, E | Low | Medium | P2 |

Controls marked Priority P0 are mandatory for the Level 2 (L2) profile per
`masvs-l2-checklist.md` and must never be regressed in CI.
