#!/usr/bin/env bash
set -euo pipefail

# Static preflight only; prints no credentials, changes no app data and never uses adb.
# Run from any directory: bash tools/security/p0-security-preflight.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

status=0
ok() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; status=1; }

api=core/src/androidMain/kotlin/org/vaachak/reader/core/data/remote/SyncApi.kt
policy=core/src/androidMain/kotlin/org/vaachak/reader/core/data/remote/SyncEndpointPolicy.kt
secrets=core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/PrivateGatewaySecrets.kt
manifest=leisure/src/main/AndroidManifest.xml

if grep -Eq 'install\(Logging\)|LogLevel\.(ALL|BODY|HEADERS)' "$api"; then
  fail 'Sync HTTP client still enables logging of credential-bearing requests'
else
  ok 'SyncApi does not install credential-bearing HTTP logging'
fi

if grep -Fq 'SyncEndpointPolicy.normalizedBase(url)' "$api" &&
   grep -Fq 'followRedirects = false' "$api" &&
   grep -Fq '== "https"' "$policy"; then
  ok 'SyncApi routes URLs through HTTPS-only normalization'
else
  fail 'Sync HTTPS-only URL policy missing or not wired'
fi

if grep -Fq '.setUserAuthenticationRequired(true)' "$secrets" &&
   grep -Fq 'AUTH_DEVICE_CREDENTIAL' "$secrets"; then
  ok 'Keystore requires recent device-credential authentication'
else
  fail 'Keystore authentication requirement absent'
fi

if grep -Fq 'android:allowBackup="false"' "$manifest" &&
   grep -Fq '<cloud-backup>' leisure/src/main/res/xml/data_extraction_rules.xml &&
   grep -Fq '<device-transfer>' leisure/src/main/res/xml/data_extraction_rules.xml; then
  ok 'Application backup disabled and extraction exclusion rules present'
else
  fail 'Application backup/extraction policy needs review'
fi

# Do not mark this PASS: OPDS feed credentials are currently columns in Room.
if grep -Eq 'val password: String\? = null' core/src/commonMain/kotlin/org/vaachak/reader/core/domain/model/OpdsEntity.kt; then
  printf 'OPEN  OPDS/Calibre passwords are stored as Room fields; at-rest risk remains\n'
fi
printf '\nStatic-only checks do not prove secure transport, migration, redaction, or reboot behavior.\n'
exit "$status"
