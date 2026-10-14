#!/bin/sh
# check-race.sh - the race resolution and the result payload, asserted on a plain JVM.
#
# The app's own behaviour needs a device: there is no Gradle here to host a unit test, and
# tools/verify-ui.sh drives an installed APK. Race.java and ResultPayload.java are pure - no Android
# and no org.json - so this compiles them with the project's own JDK and runs
# tools/race-check/RaceCheck.java against them. It covers the resolution order (a match override over
# the division, the bracket over a local goal, an absent race over everything), the provenance the
# payload records, and the tally marker that keeps a 14.1 tally from being read as a rack sheet.
#
# It checks nothing a person looks at: the race line, the goal line under a name and the override
# dialog are resources rendered by the platform, and need the emulator.
#
# Run with Git's shell:  sh tools/check-race.sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WIN=$(cygpath -m "$ROOT")
JDK="$ROOT/toolchain/jdk/bin"
OUT="$WIN/build/race-check"

[ -x "$JDK/javac.exe" ] || { echo "missing JDK - run: sh tools/bootstrap.sh"; exit 1; }

rm -rf "$WIN/build/race-check"
mkdir -p "$WIN/build/race-check/classes"

echo "== javac (source/target 8, as build.sh compiles the app) =="
"$JDK/javac.exe" -source 8 -target 8 -Xlint:-options \
  -encoding UTF-8 \
  -d "$OUT/classes" \
  "$WIN/app/java/com/kevin/score/Race.java" \
  "$WIN/app/java/com/kevin/score/ResultPayload.java" \
  "$WIN/tools/race-check/RaceCheck.java"

echo "== the assertions =="
"$JDK/java.exe" -cp "$OUT/classes" com.kevin.score.RaceCheck
