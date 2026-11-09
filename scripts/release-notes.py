#!/usr/bin/env python3
"""Extract the matching changelog section; never release with empty notes."""
import pathlib
import re
import sys

text = (pathlib.Path(__file__).resolve().parents[1] / 'CHANGELOG.md').read_text()
match = re.search(r'^## \[' + re.escape(sys.argv[1]) + r'\][^\n]*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
if not match or not match[1].strip():
    raise SystemExit('Add release notes to CHANGELOG.md before tagging')
print(match[1].strip())
print('\n### Установка\n\nСкачайте APK из Assets и установите поверх предыдущей версии MJReader. '
      'Подпись сохраняется; удалять приложение не нужно. SHA-256 указан в SHA256SUMS. '
      'Android 8.0+. Версии 0.x — предварительные сборки.')
