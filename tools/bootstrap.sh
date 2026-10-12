#!/bin/sh
# Bootstrap a self-contained Android build toolchain (no Gradle, no Android Studio).
# Installs: portable JDK 17, Android cmdline-tools, platform android-34, build-tools 34.0.0.
# Run with Git's shell:  sh tools/bootstrap.sh
set -eu

ROOT=${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
TB="$ROOT/toolchain"
DL="$ROOT/dl"
mkdir -p "$TB" "$DL"

JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

echo "== paths =="
echo "ROOT=$ROOT"
command -v cygpath >/dev/null || { echo "cygpath missing (needs Git for Windows shell)"; exit 1; }

echo "== downloads =="
[ -f "$DL/jdk17.zip" ]         || curl -fL --retry 3 -o "$DL/jdk17.zip" "$JDK_URL"
[ -f "$DL/cmdline-tools.zip" ] || curl -fL --retry 3 -o "$DL/cmdline-tools.zip" "$CLT_URL"
ls -l "$DL"

echo "== jdk =="
if [ ! -x "$TB/jdk/bin/java.exe" ]; then
  rm -rf "$TB/jdk.tmp"; mkdir -p "$TB/jdk.tmp"
  unzip -q "$DL/jdk17.zip" -d "$TB/jdk.tmp"
  mv "$TB"/jdk.tmp/jdk-* "$TB/jdk"
  rm -rf "$TB/jdk.tmp"
fi
JAVA_HOME=$(cygpath -w "$TB/jdk")
export JAVA_HOME
"$TB/jdk/bin/java" -version

echo "== cmdline-tools =="
SDK="$TB/sdk"
if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  rm -rf "$TB/clt.tmp"; mkdir -p "$TB/clt.tmp"
  unzip -q "$DL/cmdline-tools.zip" -d "$TB/clt.tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TB/clt.tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TB/clt.tmp"
fi

SM="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
SDK_WIN=$(cygpath -w "$SDK")

echo "== licenses =="
if command -v yes >/dev/null; then
  yes | "$SM" --sdk_root="$SDK_WIN" --licenses > "$TB/licenses.log" 2>&1 || true
else
  i=0; while [ $i -lt 100 ]; do echo y; i=$((i+1)); done | "$SM" --sdk_root="$SDK_WIN" --licenses > "$TB/licenses.log" 2>&1 || true
fi

echo "== sdk packages =="
"$SM" --sdk_root="$SDK_WIN" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "== installed =="
ls "$SDK/build-tools/" "$SDK/platforms/"
ls "$SDK/build-tools/34.0.0/"
ls -l "$SDK/platforms/android-34/android.jar"
echo "== done =="
