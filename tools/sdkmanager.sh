#!/bin/sh
# Run sdkmanager with the project's JDK and SDK root.  usage: sh tools/sdkmanager.sh <packages...>
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TB="$ROOT/toolchain"
JAVA_HOME=$(cygpath -w "$TB/jdk")
export JAVA_HOME
SDK_WIN=$(cygpath -w "$TB/sdk")
exec "$TB/sdk/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK_WIN" "$@"
