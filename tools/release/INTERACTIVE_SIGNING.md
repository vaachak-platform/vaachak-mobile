# Vaachak: interactive signed release build (GitHub distribution)

This patch **retains** `org.vaachak.reader.leisure`, your existing `release` signing config, and current app versioning. It does not create or replace any keystore, and does not store passwords in the repository, shell history, or Gradle properties files.

## Install from the repository root

```bash
cd /Users/piyushdaiya/Documents/projects/vaachak-platform/vaachak-mobile
unzip -n ~/Downloads/vaachak-interactive-signing.zip -d .
bash tools/release/install-interactive-signing.sh
git diff -- leisure/build.gradle.kts
```

The installer makes **two small changes** to `leisure/build.gradle.kts`:

1. Read an optional release keystore path from `ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_FILE` (with `VAACHAK_KEYSTORE_FILE` Gradle-property fallback) instead of requiring `leisure/vaachak-key.jks`.
2. Derive `storeType` from `.p12`/`.pfx`/`.pkcs12` versus `.jks`, so the existing PKCS12 file works directly.

If no path is supplied, Gradle still uses the original `leisure/vaachak-key.jks` behavior. The installer is safe to re-run; it checks the existing file and refuses unexpected versions instead of applying a broad substitution.

## Build each release

```bash
bash tools/release/build-interactive-release.sh
```

You will be prompted for:

- Keystore path (default: `~/.config/vaachak/signing/vaachak-release.p12`)
- Key alias (default: `vaachak-release`, the alias used by the earlier toolkit)
- Keystore password (hidden)
- Key password (hidden, Enter to reuse keystore password)

The script exports the values *only* to its temporary Gradle process, executes `./gradlew --no-daemon :leisure:assembleRelease`, and then shows the signed APK path and its SHA-256 checksum. The key never leaves its original location. There is no second signing step.

## Notes

- If your old GitHub APKs were signed with another key, **do not switch keys** for updates.
- Do not commit `.p12`, `.jks`, or plaintext credentials. The setup uses the original release key and leaves `applicationId` untouched.
- Build output is under `leisure/build/outputs/apk/release/`. Bump versionCode for future releases.
- For package verification, retrieve the certificate fingerprint without sharing secrets:

  ```bash
  keytool -list -v -keystore ~/.config/vaachak/signing/vaachak-release.p12 -storetype PKCS12 -alias vaachak-release
  ```

- Existing `~/.gradle/gradle.properties` signing values, if any, are not edited. The build wrapper's environment variables take precedence for this run.
- The wrapper requires an interactive terminal because passwords should not be passed on command lines.

## Undo

To undo the Gradle change, review its diff and restore only the lines added by this patch. If you have **no other** local changes in that file, `git restore leisure/build.gradle.kts` reverts it. You can also remove `tools/release/install-interactive-signing.sh` and `tools/release/build-interactive-release.sh`.

This ZIP is a signing workflow patch, **not yet** the Comics or Audiobooks code patch.
