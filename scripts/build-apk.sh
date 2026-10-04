#!/bin/sh
# Builds a debug APK. Invoked as: sh scripts/build-apk.sh  (no exec bit required).
set -e
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
