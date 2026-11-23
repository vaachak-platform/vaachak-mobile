# PR #58 — Android emulator runner Gradle script fix

## Failure observed

The Android 35 emulator successfully created and launched in GitHub Actions, but
`reactivecircus/android-emulator-runner@v2` invoked `sh -c ./gradlew \\` and Gradle
failed with `Task '\\' not found in root project 'vaachak-mobile'`.

## Root cause

The runner parses the `script` input into separate line commands before invoking
`sh -c`. Backslash line continuations in this input are **not** equivalent to
backslashes in a GitHub Actions `run: |` shell block. The first command passes
its trailing backslash to Gradle as a literal task argument.

## Repair

The complete `.github/workflows/sonar.yml` in this package replaces the emulator
runner's multi-line `script: |` with **one physical-line** invocation:

```yaml
script: ./gradlew :core:createDebugAndroidTestCoverageReport :leisure:createDebugAndroidTestCoverageReport --stacktrace --console=plain --warning-mode=summary --no-problems-report --no-daemon --no-configuration-cache
```

All existing compile, unit-test, Lint and Detekt tasks remain enabled. The API 35
emulator configuration and the four JaCoCo report paths remain unchanged.
The XML verification step still fails if any unit or instrumented coverage file
is absent or empty, and the SonarCloud quality gate remains mandatory.

## Apply to PR #58

From the feature branch repository root:

```bash
unzip -o ~/Downloads/vaachak-pr58-emulator-script-single-line-fix-full-files.zip -d .
git diff --check
git diff -- .github/workflows/sonar.yml
git add .github/workflows/sonar.yml docs/SONAR_PR58_EMULATOR_RUNNER_SCRIPT_FIX.md
git diff --cached --check
git commit -m "fix: pass instrumented Gradle tasks as one emulator command"
git push origin feature/ereader-adaptive-settings
```

## Success criteria

1. API 35 emulator boots.
2. `:core:createDebugAndroidTestCoverageReport` and
   `:leisure:createDebugAndroidTestCoverageReport` both complete successfully.
3. The workflow verifies both JVM and instrumented JaCoCo XML for each module.
4. SonarCloud imports the four XML reports and evaluates the unchanged gate:
   at least 80% coverage on new code; Security A; Reliability A; no more than
   3% duplication on new code.
5. PR #58 remains unmerged until all required checks pass. Security findings
   are reviewed independently rather than masked by coverage improvements.

## Validation scope

The ZIP and YAML syntax have been checked statically. The hosted emulator and
GitHub Actions job cannot be executed here; passing CI is still required.
