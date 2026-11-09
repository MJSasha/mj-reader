#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/version.py
python3 -m unittest discover -s checks -p 'test_*.py'
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" app/src/main/java/com/mjreader/ReadingText.java checks/ReadingTextCheck.java
java -cp "$work" ReadingTextCheck
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest lintDebug
