# PR #58: Sonar quality-gate follow-up

Source of truth: `vaachak-mobile` PR #58, with the prior remediation files applied. This ZIP contains **complete source-file replacements**, not a patch script. Apply on top of the previously delivered Sonar remediation and do not merge until CI passes.

## Changes in this delivery

- Removes the 9-parameter `SettingsViewModel.saveAiConfig` overload, replacing it with `AiConfigSaveRequest` (with nested `AiGatewayUpdate`) plus a completion callback. The legacy four-parameter overload is preserved for existing callers.
- Updates `AiConfigScreen`, `AiSettingsCompatibilityTest` and `SettingsViewModelTest` to use the typed request. This is intended to resolve Sonar's excessive-parameter finding, not the independent coverage or security findings.
- Changes no on-device navigation, comic preferences, credential persistence or E Ink behavior.

## Local validation

```bash
./gradlew :core:testDebugUnitTest :leisure:testDebugUnitTest \
  :core:jacocoDebugUnitTestReport :leisure:jacocoDebugUnitTestReport \
  :leisure:assembleDebug
```

## Coverage remains a separate blocker

The most recent SonarCloud report shows **27.5% coverage on new code**, well below 80%. The previous remediation added unit tests but the changed Compose `SettingsScreen.kt` is substantial and requires real UI execution and coverage reporting. Do not pretend pure JVM tests exercise composables, exclude legitimate new code, or weaken the gate.

On the PR's SonarCloud dashboard open `Measures > Coverage > New Code`, inspect per-file **new lines to cover** and **uncovered lines**. Prioritize tests for the files with the most new uncovered lines. If using Android instrumented Compose tests, ensure their JaCoCo report is aggregated and imported by Sonar; the current CI only imports `testDebugUnitTest` JaCoCo reports.

For API-based issue/coverage extraction, authenticate privately with a Sonar token. Never commit or upload the token:

```bash
read -r -s -p 'Sonar token: ' SONAR_TOKEN; echo
curl -fsS -H "Authorization: Bearer ${SONAR_TOKEN}" -G \
 'https://sonarcloud.io/api/measures/component_tree' \
 --data-urlencode 'component=vaachak-platform_vaachak-mobile' \
 --data-urlencode 'pullRequest=58' \
 --data-urlencode 'metricKeys=new_coverage,new_lines_to_cover,new_uncovered_lines' \
 --data-urlencode 'qualifiers=FIL' --data-urlencode 'ps=500' \
 -o "$HOME/Downloads/vaachak-pr58-file-coverage.json"
curl -fsS -H "Authorization: Bearer ${SONAR_TOKEN}" -G \
 'https://sonarcloud.io/api/issues/search' \
 --data-urlencode 'componentKeys=vaachak-platform_vaachak-mobile' \
 --data-urlencode 'pullRequest=58' \
 --data-urlencode 'issueStatuses=OPEN,CONFIRMED' \
 --data-urlencode 'ps=500' \
 -o "$HOME/Downloads/vaachak-pr58-open-issues.json"
unset SONAR_TOKEN
```

If the response is paginated, retrieve subsequent pages. Review JSON files for any sensitive source snippets before sharing.

## Security rating C versus keystore hotspot

The `PrivateGatewaySecrets.kt` annotation on `setUserAuthenticationRequired(false)` is a **security hotspot**: a human must review whether an always-usable key is appropriate. It may be defensible for e-ink readers without a secure lock, but that depends on the threat model. The AES key is Android Keystore-backed, ciphertext is in no-backup storage, and gateway API tokens should remain revocable and limited. An attacker with an unlocked device or app session can still use the configured gateway. This **does not automatically resolve Security Rating C**; Sonar rates actual vulnerabilities separately from hotspot review. Inspect `Vulnerability` issues in SonarCloud and remediate any actual flaws. Do not change the Keystore setting solely to silence the hotspot or mark it safe without review.

## PR acceptance gate

- Local builds/tests green.
- Security Rating A after reviewing/fixing the actual vulnerability list; security hotspot reviewed with documented risk decision.
- Reliability A.
- >= 80% coverage on *new* code, measured by SonarCloud using actual executed tests.
- Samsung S25 Ultra / BOOX retest only if user-visible behavior changes.
