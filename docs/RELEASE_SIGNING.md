# Local-only APK signing and manual GitHub Releases

**Policy:** All production signing takes place on the maintainer's local computer. GitHub Actions has no production signing key or signing password, does not sign a production release APK, and does not create or populate a GitHub Release. The release APK and SHA-256 checksum are uploaded manually by the maintainer. CI may still build normal **debug** APKs for tests (Android supplies a disposable debug certificate); these are not production releases.

## 1. Retire the old GitHub signing workflow FIRST

Remove `.github/workflows/release.yml` in the same PR as this documentation change, and merge that PR to `main` **before pushing any new `v*` tag**. The existing workflow decodes `secrets.KEYSTORE_BASE64`, reads `VAACHAK_KEYSTORE_PASSWORD`, `VAACHAK_KEY_ALIAS` and `VAACHAK_KEY_PASSWORD` from GitHub Secrets, builds a signed APK, and auto-publishes a release. None of these steps is needed under the new policy. Do not replace the workflow with one that silently signs or uploads an unsigned APK.

After the removal reaches `main`, delete the four unused signing secrets from repository **Settings → Secrets and variables → Actions**; also check environment or organization scopes if configured. Delete only the app-signing secrets, not `GITHUB_TOKEN` or other CI/analysis secrets. GitHub Secrets cannot be read back to validate their contents; confirm deletion using the GitHub UI. Removing them does not revoke any already exposed signing key. If compromise is suspected, follow an explicit key-rotation incident plan rather than changing certificates without migration planning.

## 2. Keep the existing production key safe

**For updates to already installed production Vaachak APKs, keep using the same existing signing key and certificate.** A new keystore with a different certificate will usually cause Android's package manager to reject an in-place update, even with the same package name. Back up the keystore offline in at least two protected locations. Never upload the keystore or its passwords to the repository, GitHub Secrets, releases, cloud build artifacts or support tickets.

The current Android application ID in `leisure/build.gradle.kts` is `org.vaachak.reader.leisure`; the debug variant has an additional `.debug` suffix and is not a production update. Increment release `versionCode` before shipping a new version, and verify the intended `versionName`.

## 3. Configure local Gradle signing (recommended)

The existing `:leisure` Gradle configuration accepts both JKS and PKCS#12 keystores. Put the key outside the repository and use an **absolute path**. In your private `~/.gradle/gradle.properties`, configure:

```properties
VAACHAK_KEYSTORE_FILE=/Users/YOUR_USER/private-signing/vaachak-release.jks
VAACHAK_KEYSTORE_PASSWORD=<your-private-keystore-password>
VAACHAK_KEY_ALIAS=<existing-production-key-alias>
VAACHAK_KEY_PASSWORD=<your-private-key-password>
```

These are placeholders, not real values. Change file permissions: `chmod 600 ~/.gradle/gradle.properties`. Do not commit the properties file or paste its contents into CI logs.

**Alternative local option:** use the same property names via local environment variables prefixed with `ORG_GRADLE_PROJECT_` (for example `ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_FILE`), populated by a local secret manager or interactive shell prompt. Do not pass passwords as command-line `-P...` arguments or commit them into shell scripts; command-line arguments can appear in process listings and history.

Existing support for both approaches is in `leisure/build.gradle.kts`; no application source changes are required to change signing ownership.

## 4. Build and inspect the release on the maintainer's Mac

From the reviewed release commit (not an unreviewed feature branch), run:

```bash
./gradlew :leisure:assembleRelease --no-daemon
```

The signed output is in `leisure/build/outputs/apk/release/` and is named `LeisureVaachak-v<version>-release.apk`. Replace `<version>` with the actual output version; do not upload a debug APK. Confirm the output exists and has the expected release version.

Locate `apksigner` in the Android SDK Build Tools (commonly under `~/Library/Android/sdk/build-tools/<installed-version>/apksigner`), then run:

```bash
/path/to/android-sdk/build-tools/VERSION/apksigner verify --verbose --print-certs \
  leisure/build/outputs/apk/release/LeisureVaachak-vVERSION-release.apk
```

Check **Verified**, signing scheme support and the **Signer certificate SHA-256 digest**. Compare that digest against the last known-good production release APK (or a trusted published fingerprint). The digest is of the *certificate*, not the APK file; both are relevant but different checks. Confirm the APK's application ID and version, test an in-place upgrade on a representative device with existing data, and smoke-test EPUB/TTS, settings, offline behavior, comics/audio if available, and private-Gateway behavior where applicable. Do not publish if the certificate changed unexpectedly or the upgrade fails.

Generate a checksum file from the actual APK directory after checking that it contains only the intended release APK(s):

```bash
cd leisure/build/outputs/apk/release
shasum -a 256 LeisureVaachak-vVERSION-release.apk > SHA256SUMS.txt
shasum -a 256 -c SHA256SUMS.txt
```

Use the actual filename in place of the illustration. Never publish passwords or a keystore to verify checksums.

## 5. Upload signed APK manually

1. Ensure the source commit to release has passing required checks on `main`, and the locally built APK uses that commit.
2. Open [GitHub Releases](https://github.com/vaachak-platform/vaachak-mobile/releases), select **Draft a new release**, and create/select the intended `v*` tag and target commit.
3. Attach the locally signed release APK and `SHA256SUMS.txt`. Include release notes, test/device coverage, version and upgrade notes.
4. Review the draft assets and certificate/checksum evidence; **publish manually**. Do not distribute a draft or unsigned APK as the official release.

GitHub release drafts and assets can also be managed through `gh` on your own computer if you prefer, but never pass signing keys to `gh` or GitHub Actions.

## CI responsibility after this change

Retain Android, lint, SonarCloud, CodeQL, MobSF and instrumented tests. The release job and tag-triggered auto-publisher are removed. CI-generated debug builds may have Android's ordinary debug signing for installation/testing, but **no production app-signing key is present in CI** and **no CI workflow signs or publishes official release APKs**.

## Manual release checklist

- [ ] Release commit reviewed and green; correct `versionCode` and `versionName`
- [ ] Existing production keystore securely available locally; certificate matches previous releases
- [ ] Release APK built locally with non-debuggable release variant
- [ ] APK signature verified using `apksigner`; upgrade tested without data loss
- [ ] Relevant Samsung/BOOX and offline/provider smoke tests run or limitations documented
- [ ] SHA256SUMS.txt generated and verified
- [ ] GitHub Release created as draft and signed APK uploaded manually
- [ ] Tag, source commit, release notes and assets reviewed before publishing
- [ ] Historical GitHub Actions signing secrets deleted **after** retired workflow is merged
