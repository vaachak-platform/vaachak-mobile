# PR #72 — P0-12D2A targeted coverage repair

Baseline: `e737c94724fbd8cb3ca14bebb2e19cfb5ddabb43` (PR #83 merged).

The SonarCloud bot reported **79.5% coverage on new code**, required 80%, at 20:45 UTC on 2026-11-24. GitHub Actions run 45480769335, job 138752971001: build, lint, JVM tests, API 35 instrumentation and report verification passed, but the quality gate failed. Artifact 14962751645 contains the four JaCoCo XML reports. The package's `COVERAGE_TARGETS.txt` records uncovered executable lines found in those reports. These are measured pre-repair coverage gaps, not a predicted Sonar score.

Changes are four new test classes only, plus this document:

- `AndroidOpdsCredentialStorageTest`: verify delegation of scoped generation read/write/durability/retire to the actual Android adapter with a mocked secret backend; a failed durability barrier must not be reported as success.
- `OpdsScopedNetworkPolicyTest`: directly exercise the actual scoped OkHttp network interceptor (same origin, foreign host, foreign port, HTTP rejection); cancellation propagates and backend errors do not expose sensitive details. Mock chains are not TLS or redirect integration tests.
- `OpdsCredentialStoragePolicyCoverageTest`: canonical UUID/positive ID, invalid decoded credential pairs, oversized input, malformed URI/effective ports and cancellation. All five new tests plus 14 existing storage tests passed in an isolated Kotlin/JVM runner with only the JUnit annotation stubbed.
- `CatalogViewModelSessionCoverageTest`: in-flight book and cover requests keep their original session after a catalog switch; success refreshes the same catalog and failure deletes the partial fixture; stale book objects cannot start a new request; Gutendex pagination and late responses cannot replace the new OPDS view; public Gutendex book download uses only its own repository. Use the existing SDK 33 Robolectric runner and real Readium URI/manifest fixtures, not an unmocked android.net.Uri in a plain JVM test. No network or real accounts.

The ten MockK/OkHttp/Robolectric-dependent new tests have not been executed in the packaging environment. Run actual Gradle tests, then the parent PR #72 Sonar workflow. This package does not change production code, the coverage threshold, exclusions, workflow configuration, schema or dependency versions. A new green quality gate is required; no coverage increase is guaranteed before CI.

P0-12D2B lifecycle integration is deferred until this gate is restored. Legacy plaintext and the URL-only compatibility APIs remain unresolved; authenticated emulator, actual TLS redirect/cookie and real device acceptance remain open. Keep PR #72 draft and personal devices untouched.
