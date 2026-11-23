# PR #58 — Android Emulator AVD Profile CI Fix

## Failure observed

SonarCloud Analysis / Build, test, instrument and analyze on the PR #58 GitHub-hosted Ubuntu 22.04 runner failed after successful JVM build, lint, and unit tests:

```text
avdmanager create avd --force -n test \
  --package 'system-images;android-35;google_apis;x86_64' \
  --device 'pixel_7_pro'
Error: No device found matching --device pixel_7_pro.
```

This is a **CI Android SDK hardware-profile lookup failure**, not a Vaachak code or test assertion failure. The Android emulator was never launched; SonarCloud analysis did not run in this workflow execution.

## Fix

In `.github/workflows/sonar.yml`, remove only this input from the `reactivecircus/android-emulator-runner@v2` step:

```yaml
profile: pixel_7_pro
```

`profile` is an optional Android Emulator Runner input. Omitting it instructs `avdmanager` to create an AVD for the installed API 35 `google_apis` `x86_64` image **without specifying a device ID**. This avoids dependence on a Pixel hardware profile that may be absent from command-line SDK installations. KVM, debug coverage, JaCoCo verification, Sonar report paths, and quality-gate enforcement are unchanged.

## Acceptance criteria

1. The emulator runner creates and boots an API 35 AVD on GitHub Actions.
2. `:core:createDebugAndroidTestCoverageReport` and `:leisure:createDebugAndroidTestCoverageReport` both pass.
3. The workflow verifies four non-empty coverage XML reports (two JVM, two instrumented).
4. SonarCloud imports the reports and assesses PR #58 against the existing quality gate.
5. **Do not merge** until new-code coverage is at least 80%, Security and Reliability both rate A, and new-code duplication is no more than 3%, with no unresolved mandatory checks.
6. Preserve BOOX support when reviewing the intentional Android Keystore user-authentication design; any security finding requires deliberate review or a compatible remediation.

## Validation

Local smoke test of the YAML, before push:

```bash
git diff --check
git diff -- .github/workflows/sonar.yml
grep -n -A12 'Android instrumentation and JaCoCo' .github/workflows/sonar.yml
```

After push, use `gh pr checks 58` and inspect the latest SonarCloud Analysis run. If the emulator creates successfully but a new issue appears, investigate that precise failure rather than changing the quality-gate thresholds.

## Scope and limits

This delivery changes only the emulator hardware-profile selection and adds documentation. It does not modify app code, test source, Gradle dependencies, coverage exclusions, credentials, or release configuration. The GitHub Actions runner must execute the fixed workflow to establish end-to-end success.
