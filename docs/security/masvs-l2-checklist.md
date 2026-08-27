# Athkar OWASP MASVS v2 Level 2 (L2) Checklist

App: Athkar (Arabic/English dhikr + prayer-times, offline-first)
Standard: OWASP MASVS v2 — Level 2 (L2, "structural/defense-in-depth" profile)

L2 assumes the app operates in an adversarial mobile environment and requires that every
platform-level security control is enforced and independently testable. The focus is on the
MASVS-STG (Security Testing & Resilience) requirements plus the directly relevant security
controls from the L2 control families.

Status legend: `Implemented` = enforced and verified · `Review` = implemented but requires
evidence/re-audit · `Planned` = scheduled · `N/A` = not applicable to this app/profile.

---

## Control ID reference

The IDs below follow MASVS v2 families:

- MASVS-STORAGE — data storage and privacy
- MASVS-CRYPTO — cryptography
- MASVS-AUTH — authentication
- MASVS-NETWORK — network communication
- MASVS-PLATFORM — platform interaction (app, webview, deep links, intents)
- MASVS-DATA — data usage / inputs / reporter channels
- MASVS-CODE — code quality and build settings
- MASVS-RESILIENCE — resilience (tamper, root, runtime)

---

## Checklist

| Control ID | Category | Status | Implementation guide (where enforced + how to verify) |
|---|---|---|---|
| MASVS-STORAGE-1 | Storage | Implemented | No sensitive data stored in source code, SharedPreferences, UserDefaults, plists, or plaintext files. All at-rest app data is inside the encrypted SQLCipher DB; verification: static scan for key/value stores and a grep of the source tree and built binary for secret literals. |
| MASVS-STORAGE-2 | Storage | Review | Data written to app-private storage is minimized and tagged volatile where it must not persist; verification: audit data-write call sites and confirm no sensitive plaintext is written to the sandbox filesystem. |
| MASVS-STORAGE-3 | Storage | Implemented | No sensitive data is written to OS logs (logcat / os_log / NSLog). Verification: run the app under a log-capture harness while exercising flows and assert no tokens, dhikr records, or identifiers appear; CI gate on log lines containing secret patterns. |
| MASVS-STORAGE-4 | Storage | Implemented | SQLCipher (AES-256-GCM) used for the on-device database; the DB key is stored only in iOS Keychain / Android Keystore (StrongBox when available). Verification: inspect the DB file on disk as ciphertext; test reopen without the Keystore key fails with "file is not a database". |
| MASVS-STORAGE-5 | Storage | Implemented | iOS Keychain secrets set with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`; Android Keystore keys with hardware/StrongBox backing and `setUserAuthenticationRequired` where applicable. Verification: Keychain/Keystore inspection + reboot/unlock test as described in `threat-model-stride.md` §1. |
| MASVS-STORAGE-6 | Storage | Implemented | Backup exclusion: iOS `NSURLIsExcludedFromBackupKey`; Android `android:allowBackup="false"` + `dataExtractionRules` exclusion; device-transfer excluded. Verification: run iCloud/ADB/Google backup and assert the Athkar data directory is absent; transfer test confirms it does not carry over. |
| MASVS-STORAGE-7 | Storage | N/A | No clipboard/screenshot/kill-list sensitive-handling requirement beyond defaults; app disables content snapshots/screenshots of sensitive screens if added. Verification: confirm screenshot policy on screens that would expose prayer/preferences if this becomes required. |
| MASVS-CRYPTO-1 | Crypto | Implemented | No custom cryptography; only platform-provided primitives (CryptoKit / Security framework / javax.crypto / Android Keystore). No hardcoded keys or IVs. Verification: code review + static analysis; dependency scan for crypto libraries. |
| MASVS-CRYPTO-2 | Crypto | Implemented | All symmetric encryption uses AES-256-GCM (SQLCipher default mode) with keys in secure hardware; authentication tags verified on every decrypt. Verification: unit test with tampered ciphertext must fail authentication. |
| MASVS-CRYPTO-3 | Crypto | Review | Data-protection-class / key-access policies correct; `ThisDeviceOnly` enforced so keys never migrate. Verification: audit Keychain access-group and accessibility attribute values; test cross-device restore. |
| MASVS-CRYPTO-4 | Crypto | N/A | Random number generation uses platform CSPRNG (`SecRandomCopyBytes` / `SecureRandom`); no predictable seed usage. Verification: static scan for weak RNG usage; functional test for nonce uniqueness under load. |
| MASVS-AUTH-1 | Auth | Implemented | OAuth2.1 + PKCE (S256) is used for auth flows; no client secrets embedded in the binary. Verification: intercept the OAuth flow in a test harness and confirm `code_challenge`/`code_verifier` PKCE and no static secret present. |
| MASVS-AUTH-2 | Auth | Review | Authentication session material (tokens) is stored only in the secure store (Keychain/Keystore), never in plaintext storage. Verification: locate all token read/write sites and assert they route through the secure store only. |
| MASVS-AUTH-3 | Auth | Review | Local authentication (PIN/biometric) optional but, when enabled, bound to a Keychain/Keystore-backed credential with `kSecAccessControlBiometryCurrentSet` / `setUserAuthenticationRequired`. Verification: enable biometrics and confirm the protected item only unlocks with the biometric challenge (not a passcode-only bypass where disallowed). |
| MASVS-AUTH-4 | Auth | Review | OAuth2.1 rotating refresh tokens with **reuse detection**: when the server sees a replayed refresh token, the whole refresh-token family is revoked and the client re-authenticates. Verification: test replay of an old refresh token and assert family revocation + forced re-auth; automated in CI/integration tests. |
| MASVS-AUTH-5 | Auth | N/A | Server-side session invalidation available (logout revokes tokens server-side). Verification: logout then attempt API call with the revoked token, expect 401. |
| MASVS-AUTH-6 | Auth | Review | No authentication material is transmitted via non-TLS, deep links, or inter-app channels. Verification: interception test shows token only ever over pinned TLS; deep-link fuzz finds no token channel. |
| MASVS-AUTH-7 | Auth | N/A | Short-lived access tokens with device-bound refresh tokens using `ThisDeviceOnly`/Keystore; reuse detection as above boundaries the blast radius. Verification: inspect expiry config; verify device-bound semantics survive re-keying. |
| MASVS-NETWORK-1 | Network | Implemented | All network traffic over TLS 1.3; TLS 1.2/1.1 rejected. No plaintext HTTP fallback. Verification: force a TLS 1.2 handshake in the harness; assert rejection; confirm no `http://` endpoints in config. |
| MASVS-NETWORK-2 | Network | Implemented | Certificate/public-key pinning: two SPKI pins active (current + next), 60-day rotation with overlap, and an emergency kill switch via remote config (`tls.pins.override`). Verification: negative MITM test fails; positive pin succeeds; rotation-overlap test passes; kill-switch test blocks all traffic and recovers (see `threat-model-stride.md` §2 and `secret-rotation-policy.md`). |
| MASVS-NETWORK-3 | Network | Review | No third-party/advertising SDKs on the primary signed flow; any SDK present is treated as untrusted and cannot reach the token holder. Verification: network-policy test confirming an SDK cannot read or transmit tokens; dependency audit for exfiltration endpoints. |
| MASVS-PLATFORM-1 | Platform | Implemented | App-declared permissions minimized; no overbroad permission on components. Verification: manifest audit (Android) / Info.plist usage-description audit (iOS); confirm least-privilege. |
| MASVS-PLATFORM-2 | Platform | N/A | WebViews: the app does not embed third-party web content for sensitive flows; if a WebView is ever used it is sandboxed, `javaScriptEnabled` controlled, and no bridging of secrets. Verification: if a WebView exists, test JS bridge exposure. |
| MASVS-PLATFORM-3 | Platform | N/A | No sensitive data loaded into a WebView for dhikr/prayer flows; offline content shipped locally and loaded without network injection. Verification: static/content review. |
| MASVS-PLATFORM-4 | Platform | N/A | No use of object deserialization of untrusted external data. Verification: static scan for `readObject`/`NSKeyedUnarchiver` of untrusted input. |
| MASVS-PLATFORM-5 | Platform | Implemented | Deep links handled via Universal Links (iOS `associated-domains`/`applinks`) and App Links (Android `autoVerify` + `assetlinks.json`); parameters allow-listed and validated; no privileged action triggered by a link alone. Verification: domain-binding test + parameter fuzz test (see `threat-model-stride.md` §5). |
| MASVS-PLATFORM-6 | Platform | Implemented | Components non-exported by default; remote-most IPC requires signature-level permissions and caller validation. Verification: exported-audit scan + malicious-intent test (see `threat-model-stride.md` §6). |
| MASVS-DATA-1 | Data | Review | Official app-input (user typing, preferencing) validated for type, length, and range; no injection into SQL/HTML/scripts; prayer/unix-time inputs range-checked. Verification: input-fuzz harness on user-facing and deep-link inputs; assert no crash/injection. |
| MASVS-DATA-2 | Data | Implemented | No sensitive personal data (dhikr history, identifiers, auth material) in telemetry/analytics. Verification: capture analytics payloads in the harness over normal usage and assert no PII/identifiers/tokens present; CI assertion on payload shape. |
| MASVS-CODE-1 | Code | Review | Release builds: debugging disabled, non-debuggable flag set, no temp/debug endpoints, no test credentials. Verification: release-build inspection confirms `android:debuggable="false"` / debugger attach refused. |
| MASVS-CODE-2 | Code | Implemented | No hardcoded secrets/keys/credentials anywhere in code or configs. Verification: secret-scan in CI (gitleaks-style) on source and built binary; block on findings. |
| MASVS-CODE-3 | Code | N/A | No third-party libraries beyond a small, audited allow-list; dependency scan (OSV/Dependabot) run each build. Verification: dependency audit in CI; upgrade policy for known CVEs. |
| MASVS-CODE-4 | Code | Review | All input and output encoding handled at the boundary to prevent injection; verified by fuzzing in MASVS-DATA-1 and platform audits above. |
| MASVS-RESILIENCE-1 | Resilience | Implemented | Root/jailbreak detection at startup: detects known markers (su binaries, Magisk/SuperSU, Jailbreak paths, Cydia, substrate/Substrate-injected, Frida/exposure of debuggers). On detection, app can run in degraded mode but refuses to reveal stored credentials or mint tokens. Verification: run the app on a rooted/jailbroken test device and assert the degradation path engages and no token leaves the Keystore. |
| MASVS-RESILIENCE-2 | Resilience | Implemented | Tamper detection / self-integrity: the app verifies its own code signature/hash at runtime against the trusted build value (iOS `SecStaticCodeCheckValidity`; Android signature-hash comparison) and emits a security event on failure. Verification: modify one byte, re-sign, observe integrity check failure and privileged-flow lockdown (see `threat-model-stride.md` §3). |
| MASVS-RESILIENCE-3 | Resilience | Review | Anti-debugging / anti-Frida detection where feasible without breaking App Store/Play policy; kept lightweight and non-blocking to release. Verification: Frida/`lldb` attach test logs the detection event; no crash. |
| MASVS-RESILIENCE-4 | Resilience | Review | Any local authentication is bound to hardware-backed keys and cannot be bypassed by app restart / restore. Verification: reboot + restore test confirms the protected item still requires the biometric/PIN challenge. |
| MASVS-RESILIENCE-5 | Resilience | N/A | App-integrity/anti-tamper is enough; no jailbreak-evasion arms race beyond L2. Verification: RASP flip during signed-release path confirmed disabled. |

---

## Verification notes

- Every `Implemented` row has a CI regression test. Every `Review` row has a documented
  re-audit owner and a target date before the next release.
- The network and storage rows (MASVS-STORAGE-3/4/5/6, MASVS-NETWORK-1/2,
  MASVS-RESILIENCE-1/2) are mandatory gates for a release; a passing suite is part of the
  definition of done.
- Root/jailbreak detection must not hard-fail legitimate users — degraded-mode behavior is
  tested on both clean and rooted fixtures.
