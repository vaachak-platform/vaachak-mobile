#!/usr/bin/env bash
# Interactive wrapper around Vaachak's existing Gradle release signing configuration.
# Never store or print the passwords.
set -euo pipefail
umask 077
if [[ ! -t 0 ]]; then
  echo 'ERROR: run this script in a terminal so the password prompts remain private.' >&2
  exit 1
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
if [[ ! -f "$repo_root/gradlew" ]]; then
  echo "ERROR: Gradle wrapper not found in $repo_root" >&2
  exit 1
fi
if ! grep -q 'VAACHAK_KEYSTORE_FILE' "$repo_root/leisure/build.gradle.kts"; then
  echo 'ERROR: first run bash tools/release/install-interactive-signing.sh' >&2
  exit 1
fi

DEFAULT_KEYSTORE="$HOME/.config/vaachak/signing/vaachak-release.p12"
printf 'Keystore path [%s]: ' "$DEFAULT_KEYSTORE"
IFS= read -r key_path
key_path="${key_path:-$DEFAULT_KEYSTORE}"
# Resolve optional tilde form, without eval.
case "$key_path" in
  '~') key_path="$HOME" ;;
  '~/'*) key_path="$HOME/${key_path#\~/}" ;;
esac
if [[ ! -f "$key_path" || ! -r "$key_path" ]]; then
  echo "ERROR: keystore not found or not readable: $key_path" >&2
  exit 1
fi
# Canonical path permits spaces and relative paths entered by users.
key_parent="$(cd "$(dirname "$key_path")" && pwd -P)"
key_path="$key_parent/$(basename "$key_path")"
printf 'Key alias [vaachak-release]: '
IFS= read -r key_alias
key_alias="${key_alias:-vaachak-release}"
if [[ -z "$key_alias" ]]; then
  echo 'ERROR: key alias cannot be empty.' >&2
  exit 1
fi
IFS= read -r -s -p 'Keystore password: ' store_password
printf '\n'
if [[ -z "$store_password" ]]; then
  echo 'ERROR: keystore password cannot be empty.' >&2
  exit 1
fi
IFS= read -r -s -p 'Key password [Enter = keystore password]: ' key_password
printf '\n'
key_password="${key_password:-$store_password}"

printf '\nBuilding signed Vaachak release APK...\n'
# Child-process-only environment avoids command-line args, ~/.gradle credentials,
# and shell-history persistence. The script process exits after Gradle finishes.
(
  export ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_FILE="$key_path"
  export ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_PASSWORD="$store_password"
  export ORG_GRADLE_PROJECT_VAACHAK_KEY_ALIAS="$key_alias"
  export ORG_GRADLE_PROJECT_VAACHAK_KEY_PASSWORD="$key_password"
  cd "$repo_root"
  ./gradlew --no-daemon :leisure:assembleRelease
)
unset store_password key_password
printf '\nRelease APK files:\n'
apk_dir="$repo_root/leisure/build/outputs/apk/release"
if [[ ! -d "$apk_dir" ]]; then
  echo "ERROR: build succeeded but APK directory not found: $apk_dir" >&2
  exit 1
fi
found=0
while IFS= read -r -d '' apk; do
  found=1
  printf '%s\n' "$apk"
  shasum -a 256 "$apk"
done < <(find "$apk_dir" -maxdepth 1 -type f -name '*.apk' -print0)
if [[ "$found" -eq 0 ]]; then
  echo 'WARNING: no release APK found. Review Gradle build outputs.' >&2
fi
printf '\nDo not re-sign the output APK. Keep the original .p12 and password backed up securely.\n'
