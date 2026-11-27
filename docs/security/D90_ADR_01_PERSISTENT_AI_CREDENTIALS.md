# D90-ADR-01 — Persistent AI Credentials with Authenticated Management

**Decision date:** 2026-11-26\
**Status:** Product behavior approved by owner; implementation, security review and release acceptance pending.\
**Delivery:** D90, `feat/ai-provider-compatibility`, one consolidated PR and gate.\
**Baseline:** D89 `9611076d402e5220b7c9e1db96816465190ed814`; D90 initial checkpoint: 249 Core JVM + 148 Leisure JVM + 23 Worker tests passed, zero failures or skips. These results precede this policy change.

## R1 — Owner-approved amendment (2026-11-26)

Supersedes the first-save, replacement and removal native-authentication requirements below, including the corresponding D90-02 acceptance criteria. Routine credential management uses the current eligible foreground, unlocked secure device and active persisted reader vault. First save is explicit; replace/remove require an in-app confirmation with a non-mutating Keep/dismiss path. No extra profile PIN prompt or new profile setting. Existing optional profile PIN checks remain at profile selection; the active profile persists across app restart, so this is not a per-launch or repository-level PIN authentication boundary. A person with access to an unlocked device and active profile can replace/remove its credentials. This is an explicitly accepted private-use trade-off, not a cryptographic equivalent of recent native authentication.

Legacy migration alone retains native DEVICE_CREDENTIAL authentication, including cancel/deny/interruption tests. Preserve exact vault/slot/action/revision/host binding, one-use grants, eligibility, vault mutation locking, durable encrypted storage, fail-closed cutover, old sync/OPDS policy and no plaintext reveal. Existing VAI3 data remains compatible. No CI thresholds or exclusions change. The earlier 602/602 and 88.09% candidate evidence is historical; rerun consolidated tests for R1 and check actual device behavior. Manual legacy cancellation was not established on API 35 native UI; retain automated migration cancellation and recovery acceptance and document that manual limitation. Do not mark outstanding release acceptance PASS without evidence.

## 1. Context and decision

The existing shared `PrivateGatewaySecrets` v2 key uses `AUTH_DEVICE_CREDENTIAL` with a 60-second authorization interval on Android 11+. When decryption is unavailable, older direct-provider settings code can return an empty string; the UI then implies a saved token is missing. The deployed Cloudflare Worker returned a valid JPEG and the app rendered images intermittently. Gemini direct REST inference also succeeded independently. These observations establish the need for credential lifecycle repair, not proof of ciphertext deletion. The D90 initial candidate propagates unavailable errors on direct AI requests and preserves blank fields in the dedicated editor, but retains the old key policy.

**Approved behavior:** save an AI credential once, authenticate the management operation using Android's native authentication UI, then use the saved credential without an extra prompt during normal foreground reading on an unlocked, secure device. No 60-second or 15-minute recurring AI authorization timer. Do not require manual lock/unlock or repeat token entry. Persistent storage is not an indefinite plaintext session.

This supersedes the AI-only time-window requirement in the previous P0 credential migration and D90 candidate. It applies to Gemini, Cloudflare and Private Gateway only. The existing sync-password and OPDS credential policies, aliases, file formats, migration and acceptance obligations are unchanged. No change to minSdk 30, compileSdk 36 or targetSdk 36.

## 2. User-visible contract

| Operation | Required behavior |
|---|---|
| First save, replace, explicit confirmed remove | Native strong biometric or secure device credential authentication for the specific operation. Cancellation/failure changes nothing. |
| Reveal existing secret, if implemented | Separate explicit authentication; default design is not to reveal saved plaintext. |
| Keep existing secret / edit nonsecret settings | No management prompt solely for retaining a credential; do not decrypt/rewrite it just to save unrelated settings. |
| Explain, Who, Recap, Recall, Visualize, explicit provider diagnostic | Use saved credential without additional authentication on an eligible unlocked foreground device; preserve provider selection and offline checks. |
| App background, device lock, vault switch | Invalidate in-flight access and pending management grants; do not erase saved credentials. No new network AI action while locked/backgrounded. On return to eligible foreground state, routine use resumes without additional prompt. |
| Force-stop, process death, ordinary restart, reboot followed by normal device unlock, supported signed APK upgrade | Credentials remain encrypted and usable without additional app authentication after normal unlock, subject to key availability. |
| No secure device lock, before first unlock after boot, or unsupported protection | Fail closed for AI credential storage/use; show actionable status. Offline reading, media and on-device TTS remain usable. |
| Revoked API token, invalidated/lost key, corrupted ciphertext | Distinct recovery guidance; no silent deletion, fallback or automatic replacement. Re-entry only when genuinely necessary. |

Samsung S25 Ultra (observed Android 17/API 37): prefer enrolled strong fingerprint; face only if OS classifies it as Class 3 for management; secure PIN/pattern/password fallback. BOOX Leaf3C (Android 11/API 30): native PIN-only authentication; no biometrics assumed. Normal device unlock is distinct from credential-management authorization. One APK. API 35 emulator is already available; API 30 emulator and physical-device validation remain required.

## 3. Security model and implementation constraints

- Separate versioned AI-only Android Keystore AES-256-GCM key/envelope from shared authenticated v2. Bind ciphertext to vault and provider using authenticated associated data; generate unique randomized IVs and use atomic durable file writes in credential-protected `noBackupFilesDir`. No key material, token, excerpt or personal endpoint in logs, backups, build artifacts, diagnostics or test fixtures. Do not use device-protected storage or Direct Boot access for secrets. Verify actual security level rather than claiming hardware backing on all devices.
- Native `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` on API 30+ (or OS-qualified device credential only). A successful result authorizes one pending management operation bound to persisted vault ID, slot, action, revision and current foreground host. Grant is short-lived, one-use, not persisted; cancel on lock, background, context change or rotation unless a securely retained operation is explicitly revalidated. Never collect the PIN in app UI. Prompt callbacks must not be treated as arbitrary reusable permission. Protect repository entry points, not just button visibility.
- A routine-use key cannot retain the old per-use or time-limited user-authentication constraint and simultaneously guarantee prompt-free routine reading. The chosen design must explicitly document the resulting loss of Keystore-enforced recent authentication for AI use. On API 35+, prefer `setUnlockedDeviceRequired(true)` for the separate AI key, with secure-screen-lock and foreground checks. On API 30–34, do not blindly copy that setting: evaluate the API 30 decryption protection and known Android 12–14 defects; test normal unlock, lock removal and key availability. A compatibility design relying on credential-protected storage, Keystore and application-level lock checks instead of hardware-enforced locked-state restrictions is a distinct residual risk requiring explicit review/acceptance before use. If the required policy cannot be demonstrated on BOOX, block release rather than silently weaken it. Exact API-specific implementation is not approved by this ADR.
- Independently enforce secure screen lock, user unlocked since boot, current device/keyguard unlocked, foreground host, expected persisted vault, selected provider, unchanged credential revision and Offline Mode at request acquisition and dispatch. Cancel in-flight work on invalidation; already transmitted requests cannot be recalled. No hidden background inference, no cross-provider fallback, no automatic replay of inference. Do not maintain a long-lived plaintext cache; minimize secret lifetime and avoid copying it into observable UI state. Managed JVM strings cannot be promised to be securely zeroized.
- Nonsecret state: Missing, Saved/Ready (local eligibility only; not proof of provider health), Saved/Unavailable (including locked or migration required), Recovery required, provider rejection. Keep/Replace/Remove are explicit; blank field means Keep. Presence lookup must not decrypt. Removing secure screen lock disables credential use; preserve ciphertext and require authenticated recovery or replacement. Keep authentication and provider failure states distinct.

## 4. Threat model and residual risk

| Threat | Control / remaining limitation |
|---|---|
| Lost device, storage extraction, backup/export | Keystore-backed authenticated encryption, credential-protected private storage, backup exclusions, device lock and platform protections. Device-specific enforcement must be verified. |
| Unlocked phone accessed by another person | Routine use is intentionally permitted; management actions require OS authentication. Device owner must lock the phone when unattended. |
| Compromised app process/root/OS | Attacker may invoke available keys or capture decrypted provider tokens. A native UI management prompt is not equivalent to cryptographic authentication on every decryption. This is the principal accepted product trade-off, with detailed implementation risk review still required. |
| Cross-vault or cross-provider transfer | Vault/provider AAD, independent slots, persisted-vault snapshot, configuration revision and cancellation; no implicit fallback. |
| Credential theft and quota abuse | Scoped provider keys where supported, restricted endpoints/TLS, rate limits/budgets where available, revocation and replacement. No guarantee of global spend limits from Worker local rate limiting. |
| Migration interruption or downgrade | Durable staged replacement, verification, legacy retention until safe cutover, explicit recovery and rollback testing. |

The owner approved prompt-free routine use in this private reading app, not a blanket bypass of encryption or authentication. The previous SonarCloud `kotlin:S6288` finding was addressed using the shared v2 recent-authentication key. Any new hotspot or finding from the AI-only key change must be individually reviewed against this threat model and actual source/test evidence, with an explicit documented security disposition. Do not pre-mark findings safe, suppress rules, add exclusions, weaken thresholds or claim identical security to v2. Official new-code coverage >=80%; local target >=85%; existing CI and security gates unchanged.

## 5. Migration and recovery

1. Inspect and distinguish existing VAG1, VAG2, legacy plaintext and new versioned AI envelopes. Never reinterpret sync VAS2 or OPDS data as AI credentials. Pin the full operation to the persisted vault and provider slot.
2. Existing v2 data may require one final native PIN/pattern/password authorization under the original key policy; biometric alone cannot authorize that old key. Explain this once, without forcing manual lock/unlock. If authentication is cancelled or unavailable, preserve old encrypted files and settings for retry; do not use legacy plaintext as a network fallback.
3. Stage the AI-only replacement; encrypt, durably write, read back and verify against the original before committing version/slot metadata. Preserve old data until verified cutover. Make interrupted migration recoverable and idempotent, with deterministic precedence and no silent stale-token fallback. Do not delete shared aliases while any vault, sync or OPDS references them.
4. Explicit authenticated Replace/Remove must retire superseded copies safely, prevent resurrection after restart or migration, and invalidate any in-flight request. Define rollback compatibility with older APKs; an older APK may not read the new version. Do not restore stale revoked credentials or promise arbitrary downgrade support. Test with synthetic upgrade/rollback fixtures and same-signing-key upgrades; no uninstall/data clear of owner devices.
5. Missing/invalidated keys require explicit recovery or re-entry. No plaintext fallback, unauthenticated replacement of old encrypted data, or automatic destructive reset.

## 6. D90 release evidence — all pending for this policy

| ID | Required acceptance | Evidence |
|---|---|---|
| D90-01 | Nonsecret state, Keep/Replace/confirmed Remove, no blank/locked deletion, migration interruption/recovery, isolation and no stale resurrection | Unit + instrumentation + devices |
| D90-02 | Native management authentication success/cancel/deny; strong fingerprint and secure PIN fallback; BOOX PIN; no repeat prompt at >60 seconds and >15 minutes, foreground return, normal lock/unlock, process restart, reboot after unlock, and signed upgrade | API 30/35 instrumentation and Samsung/BOOX manual |
| D90-06 | AI-only key policy and actual at-rest storage/backup verification; locked/background/no-secure-lock denial, version-specific compatibility, revocation, old sync/OPDS unchanged, hotspot disposition | KeyInfo where applicable, device tests, source/security review |
| D90-03/04/05 | Gemini selected text model and all four actions; Cloudflare independent Visualize, bounded decode; explicit diagnostic inference; privacy, error and cancellation contract | Existing D90 gate; unchanged |

Include offline EPUB, comics, audiobook, TTS, private Gateway Tailscale and original P0/P1 regression acceptance. Do not count the 420 initial passing tests as proof of this new design. Run the consolidated gate on the final source inventory and retain test counts, actual device/API/build details and redacted results. No real provider keys, PINs, screenshots exposing secrets or book excerpts in evidence. D90 remains incomplete and D91 production release blocked until mandatory acceptance succeeds.

## 7. References

Repository: `docs/P0_SECURE_CREDENTIAL_MIGRATION.md`, `docs/security/P0_08_P0_09_SECURITY_ACCEPTANCE.md`, `docs/security/PR72_FINAL_DEVICE_SECURITY_ACCEPTANCE.md`, `docs/product/VAACHAK_LEISURE_REQUIREMENTS.md`, `docs/product/ACCEPTANCE_MATRIX.csv`, `docs/product/D90_AI_PROVIDER_REVIEW_CANDIDATE.md`. Historical records remain unchanged; this ADR supersedes only their AI authorization-time requirement for D90.

- Android Keystore system: https://developer.android.com/privacy-and-security/keystore
- KeyGenParameterSpec.Builder (`setUnlockedDeviceRequired`, `setUserAuthenticationParameters`): https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder
- Native biometric authentication: https://developer.android.com/identity/sign-in/biometric-auth
- Credential-protected storage / Direct Boot: https://developer.android.com/privacy-and-security/direct-boot

## 8. Isolated storage evidence and integration candidate

The synthetic VAI3 probe passed three tests each on BOOX Leaf3C (API 30, 65.809 s), API 35 emulator (65.364 s), and Samsung S25 Ultra (API 37, 65.234 s). 408 JVM and 23 Worker tests passed on the preceding isolated-storage source checkpoint. These 440 executions predate the integrated source and do not close any acceptance row. Actual device lock, native prompt handoff, reboot, process death, signed upgrade, recovery and provider qualification remain pending.

The integration candidate uses the framework native prompt, separate short-lived management grant, same-host foreground return, screen-off/user-leave revocation and a per-slot DataStore cutover marker. The OS PIN UI can temporarily stop its host; only its pending challenge may survive this handoff, not routine inference authorization. Native lifecycle behavior must be tested on all three devices. A completed new envelope is verified before retirement of exact legacy ciphertext; partial writes/cleanup block inference, and interrupted retirement is explicitly recoverable. Removal publishes a blocking marker before deleting copies. Downgrade to an older APK is unsupported after VAI3 cutover; do not downgrade across partial migration. No real credentials have been migrated by the isolated tests.

Cloudflare success bodies are bounded to 8 MiB image bytes (bounded base64 JSON envelope separately), typed JPEG/PNG/WebP, maximum 2048 px per axis and 4,194,304 pixels. The reader decodes off the UI thread; unsupported/invalid images fail with fixed errors. The candidate supports raw image responses and two explicit base64 JSON forms. Synthetic image diagnostics may incur provider quota. No raw provider error body is materialized by the dedicated transport. Android compilation, instrumentation, coverage and security findings remain unverified for this integration candidate.
