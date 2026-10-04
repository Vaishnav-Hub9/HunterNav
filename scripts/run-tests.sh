#!/bin/sh
# Runs the unit test suite. Invoked as: sh scripts/run-tests.sh  (no exec bit required).
set -e
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest
echo "Report: app/build/reports/tests/testDebugUnitTest/index.html"
