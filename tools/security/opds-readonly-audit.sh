#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
prod=core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/OpdsRepository.kt
helper=core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository/OpdsAuthOrigin.kt
model=core/src/commonMain/kotlin/org/vaachak/reader/core/domain/model/OpdsEntity.kt
printf 'OPDS transport audit: source scan only; no device or user data accessed\n'
if grep -Fq 'OpdsAuthOrigin.sameOrigin(storedFeed.url, secureDownloadUrl)' "$prod" &&
   grep -Fq 'catalogPort == requestedPort' "$helper"; then
  echo 'PASS: Catalog Basic auth is scoped to HTTPS origin for downloads'
else
  echo 'FAIL: Cross-origin Basic auth guard absent or modified'; exit 1
fi
if grep -Fq 'normalizeHttpsUrl(downloadUrl)' "$prod"; then
  echo 'PASS: Download URLs pass the existing HTTPS validator'
else
  echo 'FAIL: Download HTTPS guard absent'; exit 1
fi
if grep -Fq 'val password: String?' "$model"; then
  echo 'OPEN RISK: OPDS catalog passwords remain persisted as Room columns.'
  echo 'RELEASE DECISION: No at-rest security clearance is claimed; see docs/security/P0_12_OPDS_CREDENTIAL_SECURITY_DECISION.md'
fi
