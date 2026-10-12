#!/bin/sh
# Install a portable Go toolchain, needed to build and run the reference tournament server
# (kevinelong/15ball-scoresheet) while wiring up the merged app's "open a match by URL" mode.
#
#   sh tools/bootstrap-go.sh
#
# Same pattern as tools/bootstrap.sh: nothing system-wide, no admin, no WSL, no Docker - just an
# unzipped toolchain under toolchain/ and a cached download in dl/, both git-ignored so re-runs work
# offline and a `rm -rf toolchain` fully reverses it.
#
# Git-Bash note, learned the hard way: this script deliberately does NOT put go on PATH. MSYS does not
# resolve a "C:/..." PATH entry when looking for a command, so `go` then reports "command not found"
# even though the binary is there. Invoke it by absolute path:
#
#   toolchain/go/bin/go.exe build ./...
#
# To keep module and build caches inside the project instead of the user profile, export these first:
#   GOPATH=$ROOT/toolchain/gopath
#   GOMODCACHE=$ROOT/toolchain/gopath/pkg/mod
#   GOCACHE=$ROOT/toolchain/gocache
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TB="$ROOT/toolchain"
DL="$ROOT/dl"
GO_VERSION=${GO_VERSION:-go1.27.2}

command -v unzip >/dev/null || { echo "unzip missing (needs Git for Windows shell)"; exit 1; }

ZIP="$DL/$GO_VERSION.windows-amd64.zip"
[ -f "$ZIP" ] || curl -fL --retry 3 -o "$ZIP" "https://go.dev/dl/$GO_VERSION.windows-amd64.zip"

if [ ! -f "$TB/go/bin/go.exe" ]; then
  rm -rf "$TB/go.tmp"
  mkdir -p "$TB/go.tmp"
  unzip -q "$ZIP" -d "$TB/go.tmp"
  mv "$TB/go.tmp/go" "$TB/go"
  rm -rf "$TB/go.tmp"
fi

echo "go installed at: $(cygpath -m "$TB/go/bin/go.exe")"
GOPATH="$TB/gopath" GOCACHE="$TB/gocache" GOMODCACHE="$TB/gopath/pkg/mod" \
  "$TB/go/bin/go.exe" version
echo
echo "next:   cd C:/Users/kevin/score-ref/15ball-scoresheet/server"
echo "        C:/Users/kevin/score/toolchain/go/bin/go.exe build ./..."
