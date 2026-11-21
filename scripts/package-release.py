#!/usr/bin/env python3
"""Verify the signed, non-debuggable APK and produce named release assets."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile
from version import ROOT, version

name, code = version()
sdk = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
signature = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
expected = (ROOT / 'docs/release-certificate.sha256').read_text().strip()
if f'certificate SHA-256 digest: {expected}' not in signature:
    raise SystemExit('Unexpected signing certificate: refusing to publish an incompatible APK')
badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
if f"versionName='{name}'" not in badging or f"versionCode='{code}'" not in badging or 'application-debuggable' in badging:
    raise SystemExit('APK version/debuggable flag does not match the release')
with zipfile.ZipFile(apk) as archive:
    if any(p.endswith(('.p12', '.keystore', '.jks', '.epub')) for p in archive.namelist()):
        raise SystemExit('Private/test material found in release APK')
destination = ROOT / 'dist/release'
destination.mkdir(parents=True, exist_ok=True)
target = destination / f'MJReader-{name}.apk'
shutil.copyfile(apk, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(destination / 'SHA256SUMS').write_text(digest + '  ' + target.name + '\n')
notes = subprocess.check_output([sys.executable, str(ROOT / 'scripts/release-notes.py'), name], encoding='utf-8')
(destination / 'NOTES.md').write_text(notes, encoding='utf-8')
(destination / 'update.json').write_text(json.dumps({
    'schemaVersion': 1,
    'version': name,
    'notes': notes[:12000],
    'url': f'https://github.com/MJSasha/mj-reader/releases/download/v{name}/{target.name}',
    'sha256': digest,
    'size': target.stat().st_size,
}, ensure_ascii=False) + '\n', encoding='utf-8')
print(f'Verified {target.name}: version {code}, expected signature, debuggable=false')
