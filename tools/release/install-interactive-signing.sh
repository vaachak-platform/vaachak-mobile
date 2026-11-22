#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
script="$repo_root/leisure/build.gradle.kts"
if [[ ! -f "$script" ]]; then
  echo "ERROR: leisure/build.gradle.kts not found in $repo_root" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required for the conservative Gradle patch." >&2
  exit 1
fi
python3 - "$script" <<'PYPATCH'
from pathlib import Path
import sys
p = Path(sys.argv[1])
original = p.read_text()
text = original
old_keystore = 'val releaseKeystoreFile = file("vaachak-key.jks")'
new_keystore = (
    'val releaseKeystorePath =\n'
    '    System.getenv("ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_FILE")\n'
    '        ?: providers.gradleProperty("VAACHAK_KEYSTORE_FILE").orNull\n'
    '        ?: "vaachak-key.jks"\n'
    'val releaseKeystoreFile = file(releaseKeystorePath)'
)
old_storefile = '            storeFile = releaseKeystoreFile'
storetype_line = '            storeType = if (releaseKeystoreFile.extension.lowercase() in setOf("p12", "pfx", "pkcs12")) "PKCS12" else "JKS"'
if new_keystore not in text:
    if text.count(old_keystore) != 1:
        sys.exit('ERROR: expected Gradle keystore declaration not found exactly once. No changes made.')
    text = text.replace(old_keystore, new_keystore, 1)
if storetype_line not in text:
    if text.count(old_storefile) != 1:
        sys.exit('ERROR: expected Gradle storeFile assignment not found exactly once. No changes made.')
    idx = text.index(old_storefile)
    signing_block = text[idx:idx+300]
    if 'storeType =' in signing_block:
        sys.exit('ERROR: an existing storeType assignment was found; review Gradle manually. No changes made.')
    text = text.replace(old_storefile, old_storefile + '\n' + storetype_line, 1)
if text == original:
    print('Already installed; no changes needed.')
else:
    p.write_text(text)
    print('Patched leisure/build.gradle.kts successfully.')
    print('Review: git diff -- leisure/build.gradle.kts')
PYPATCH
