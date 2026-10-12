#!/bin/sh
# Create the throwaway AVD used to verify the app.  usage: sh tools/make-avd.sh
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TB="$ROOT/toolchain"
JAVA_HOME=$(cygpath -w "$TB/jdk")
export JAVA_HOME
export ANDROID_SDK_ROOT=$(cygpath -w "$TB/sdk")
export ANDROID_AVD_HOME=$(cygpath -w "$TB/avd")
mkdir -p "$TB/avd"

AVDMANAGER="$TB/sdk/cmdline-tools/latest/bin/avdmanager.bat"

"$AVDMANAGER" delete avd -n score 2>/dev/null || true
echo no | "$AVDMANAGER" create avd \
  -n score \
  -k "system-images;android-34;default;x86_64" \
  -d pixel_5 \
  -p "$(cygpath -m "$TB/avd/score.avd")" \
  --force

printf 'hw.keyboard=yes\n' >> "$TB/avd/score.avd/config.ini"
echo "== avd list =="
"$AVDMANAGER" list avd
