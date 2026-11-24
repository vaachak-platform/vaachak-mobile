# P0-12 final security integration — candidate, not release approval

Baseline f603321d13fe61c21af2051a4cceda882ec1b7ab. This single stacked change combines D2B, E and F. See the accompanying FINAL_ACCEPTANCE.md for the consolidated evidence ledger.

## Implementation

- A singleton Android lifecycle owns catalog creation, replacement, Keep, removal, deletion, migration, recovery and scoped request dispatch. Its mutex serializes operations through response headers. An already-issued response body may finish with its original captured selection.
- Pending creation is hidden. Publish only after verified durable authenticated ciphertext; transactional generation pointers protect active metadata. Recovery reconciles interrupted reservations. Anonymous creation needs no PIN.
- Keep preserves credentials; Replace is explicit with masked password; Remove and Delete require confirmation. The editor never prefills the legacy pair or decrypts existing generations. Failed saves remain open. Explicit migration processes legacy rows and reports the remaining count. Incomplete or locked rows remain intact, never used as network credentials.
- Stable ID and credential revision guard browsing/editing. URL-only compatibility requests are anonymous and cookie-free. Scoped cookies are per browser and origin. Initially foreign requests use the anonymous client. HTTP downgrade is prohibited. Public Gutendex routing is restricted to the known HTTPS gutendex.com/books endpoint without saved credentials; custom mirrors no longer automatically use the public API path. Check custom catalogs during acceptance.
- Room v4 schema is unchanged. Nullable legacy columns remain for migration; production repository writes no new plaintext pairs. Fixture DAO APIs remain. No changes to crypto/key authorization, backup policy, coverage exclusions, dependencies or workflows.

## Evidence and residual risk

53 isolated Kotlin tests passed with coroutine runtime and in-memory DAO/annotation substitutes. This is not generated Room/Hilt, Android Keystore, Compose or OkHttp runtime validation. Full Gradle validation, new JVM TLS/Room tests and emulator instrumentation are required. Synthetic v2/v3 fixtures do not replace a released-app v3 migration fixture.

Retired ciphertext is logically inaccessible but retained intentionally: irreversible deletion before proving database commit durability could destroy a still-referenced credential after interruption. Require explicit owner acceptance of retained ciphertext or complete the durable retirement protocol before release. Legacy SQLite pages/WAL, checkpoints, backups and flash remanence need assessment; no forensic-erasure claim. SEC-03, SEC-04 (60-second authorization and unattended sync deferral), device evidence and final PR #72 CI remain open. Use only a disposable emulator and synthetic accounts, never install over personal data. Keep PR #72 draft until the consolidated ledger is accepted by the owner.
