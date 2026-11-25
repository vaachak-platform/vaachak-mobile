#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 - <<'PY'
from pathlib import Path
import re
root = Path('.')
repo = root / 'core/src/androidMain/kotlin/org/vaachak/reader/core/data/repository'
prod = (repo / 'OpdsRepository.kt').read_text()
life = (repo / 'OpdsCredentialLifecycle.kt').read_text()
assert 'lifecycle.dispatch(session, secureUrl)' in prod
assert 'OpdsAuthOrigin.sameOrigin(session.catalogUrl, secureUrl)' in prod
assert 'clientForSession(if (sameOrigin) session else null)' in prod
assert 'CookieJar.NO_COOKIES' in prod and '.followSslRedirects(false)' in prod
assert 'storage.readForRequest(' in life and 'row.username != null || row.password != null' in life
assert 'storage.retire(' not in life, 'Physical retirement requires separate durability review'
assert 'resolveLegacyCatalogCredentials' not in prod and 'findEffectiveFeed' not in prod
for source in root.glob('*/src/**/*'):
    if source.suffix != '.kt' or not source.is_file():
        continue
    if len(source.parts) < 4 or source.parts[2] not in {'main', 'androidMain', 'commonMain', 'iosMain', 'iosArm64Main', 'iosSimulatorArm64Main'}:
        continue
    data = source.read_text()
    # Legacy fixture APIs may remain declared in Room; no application call site may use them.
    if re.search(r'\b(?:opdsDao|opdsRepository)\s*\.\s*(?:insertFeed|updateFeed|getFeedByUrl|deleteFeedById)\s*\(', data, re.I) or re.search(r'\.opdsDao\s*\(\s*\)\s*\.\s*(?:insertFeed|updateFeed|deleteFeed|deleteFeedById|getFeedByUrl)\s*\(', data):
        raise SystemExit(f'FAIL: legacy OPDS production call site: {source}')
print('PASS: source-level OPDS lifecycle, origin, and legacy API guards')
print('NOTE: retained legacy columns and retired ciphertext require final device and risk acceptance; this scan is not proof of erasure.')
PY
