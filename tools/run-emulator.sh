#!/bin/sh
# Boot the verification AVD.
# usage: sh tools/run-emulator.sh [window] [extra emulator args]
#   window - show the emulator's screen as a window on the desktop instead of running headless
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TB="$ROOT/toolchain"
export ANDROID_AVD_HOME=$(cygpath -w "$TB/avd")

HEADLESS="-no-window"
if [ "${1:-}" = window ]; then
  HEADLESS=""
  shift
fi

exec "$TB/sdk/emulator/emulator.exe" -avd score \
  $HEADLESS \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -no-metrics \
  -gpu swiftshader_indirect \
  -memory 3072 \
  "$@"
