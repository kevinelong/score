#!/bin/sh
# check-release.sh - verify the built APKs against what the documentation claims.
#
# Four places state facts about the same bytes: build.sh passes the version to aapt2,
# README.md publishes a size and a SHA-256 for each artifact, score-dist/README.md
# publishes the version and the full hash of the APK it distributes, and the GitHub
# release notes repeat that hash. Nothing checked them together, and the version and
# digest claims in this repository have gone stale before. This reads the artifacts
# themselves - not the source - and fails when any of the four disagrees.
#
#   sh tools/check-release.sh            # offline: the built APKs vs the documents
#   sh tools/check-release.sh --rebuild  # also rebuild and prove the digests repeat
#   sh tools/check-release.sh --online   # also fetch the published release asset
#
# --rebuild needs the release keystore (tools/release.keystore) and rewrites out/;
# it produces the same bytes byte-for-byte, which is the claim it is testing.
#
# Run with Git's shell:  sh tools/check-release.sh
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WIN=$(cygpath -m "$ROOT")
AAPT2="$ROOT/toolchain/sdk/build-tools/34.0.0/aapt2.exe"
README="$ROOT/README.md"
BUILD="$ROOT/build.sh"
DIST="${SCORE_DIST:-$ROOT/../score-dist}"
DEBUG_APK="$ROOT/out/score.apk"
RELEASE_APK="$ROOT/out/score-release.apk"

REPO_SLUG="kevinelong/score-android"
ONLINE=0
REBUILD=0
for arg in "$@"; do
    case "$arg" in
        --online)  ONLINE=1 ;;
        --rebuild) REBUILD=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

FAILS=0
ok()   { printf 'ok    %s\n' "$1"; }
bad()  { printf 'FAIL  %s\n' "$1"; FAILS=$((FAILS + 1)); }
same() { # <label> <documented> <artifact>
    if [ "$2" = "$3" ]; then ok "$1: $3"; else bad "$1: documented '$2', artifact '$3'"; fi
}
note() { printf '      %s\n' "$1"; }

[ -f "$AAPT2" ] || { echo "missing aapt2 - run: sh tools/bootstrap.sh" >&2; exit 1; }

sha256() { sha256sum "$1" | cut -d' ' -f1 | tr -d '\\'; }
size()   { wc -c < "$1" | tr -d ' \r'; }

# ------------------------------------------------------------------ the artifacts
badging() { "$AAPT2" dump badging "$1" 2>/dev/null; }

check_artifact() { # <apk> <readme-row-name>
    apk=$1
    label=$2
    if [ ! -f "$apk" ]; then
        note "$label not built - skipping (run: sh build.sh)"
        return
    fi
    hash=$(sha256 "$apk")
    bytes=$(size "$apk")
    row=$(grep -F "| \`$label\` |" "$README" || true)
    if [ -z "$row" ]; then
        bad "$label: no row for it in README.md"
        return
    fi
    doc_bytes=$(printf '%s\n' "$row" | sed -n 's/.*| \([0-9][0-9,]*\) bytes.*/\1/p' | tr -d ',')
    doc_pre=$(printf '%s\n' "$row" | sed -n 's/.*`\([0-9a-f][0-9a-f]*\)….*/\1/p')
    doc_suf=$(printf '%s\n' "$row" | sed -n 's/.*…\([0-9a-f]*\)`.*/\1/p')
    # The README publishes a leading and a trailing fragment, and not always of the
    # same length, so compare as many characters as it actually prints.
    art_pre=$(printf '%s' "$hash" | cut -c"1-${#doc_pre}")
    art_suf=$(printf '%s' "$hash" | tail -c "${#doc_suf}")
    same "$label size" "$doc_bytes" "$bytes"
    same "$label sha256 (first ${#doc_pre})" "$doc_pre" "$art_pre"
    same "$label sha256 (last ${#doc_suf})"  "$doc_suf" "$art_suf"
}

check_artifact "$DEBUG_APK" 'out/score.apk'
check_artifact "$RELEASE_APK" 'out/score-release.apk'

# ------------------------------------------------- version: build.sh vs the APK
if [ -x "$AAPT2" ] && [ -f "$RELEASE_APK" ]; then
    dump=$(badging "$WIN/out/score-release.apk")
    pkg=$(printf '%s\n' "$dump" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
    vc=$(printf '%s\n' "$dump" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
    vn=$(printf '%s\n' "$dump" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")
    minsdk=$(printf '%s\n' "$dump" | sed -n "s/^sdkVersion:'\([0-9]*\)'.*/\1/p")
    targetsdk=$(printf '%s\n' "$dump" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p")
    perms=$(printf '%s\n' "$dump" | grep -c "^uses-permission:" || true)

    bs_vc=$(sed -n 's/.*--version-code \([0-9][0-9]*\).*/\1/p' "$BUILD" | head -1)
    bs_vn=$(sed -n 's/.*--version-name \([^ \\]*\).*/\1/p' "$BUILD" | head -1)
    same "build.sh versionCode vs APK" "$bs_vc" "$vc"
    same "build.sh versionName vs APK" "$bs_vn" "$vn"
    same "package name" "com.kevin.score" "$pkg"
    same "minSdk" "21" "$minsdk"
    same "targetSdk" "34" "$targetsdk"
    same "permissions (INTERNET only)" "1" "$perms"
    note "badging: $pkg versionCode=$vc versionName=$vn minSdk=$minsdk targetSdk=$targetsdk"
fi

# ------------------------------------------- the distribution repo and its notes
if [ -f "$DIST/README.md" ] && [ -f "$RELEASE_APK" ]; then
    dver=$(grep -E '^\| Version \|' "$DIST/README.md" | sed -n 's/.*| \([0-9][0-9.]*\) (versionCode \([0-9]*\)).*/\1/p')
    dvc=$(grep -E '^\| Version \|' "$DIST/README.md" | sed -n 's/.*versionCode \([0-9]*\).*/\1/p')
    dbytes=$(grep -E '^\| Size \|' "$DIST/README.md" | sed -n 's/.*| \([0-9][0-9,]*\) bytes.*/\1/p' | tr -d ',')
    dsha=$(grep -E '^\| SHA-256 \|' "$DIST/README.md" | sed -n 's/.*`\([0-9a-f]*\)`.*/\1/p')
    lhash=$(sha256 "$RELEASE_APK")
    lbytes=$(size "$RELEASE_APK")
    same "score-dist README version vs build.sh" "$bs_vn" "$dver"
    same "score-dist README versionCode vs build.sh" "$bs_vc" "$dvc"
    same "score-dist README size vs the built release APK" "$dbytes" "$lbytes"
    same "score-dist README sha256 vs the built release APK" "$dsha" "$lhash"
    if [ -f "$DIST/score-release.apk" ]; then
        same "the APK committed to score-dist" "$dsha" "$(sha256 "$DIST/score-release.apk")"
        same "score-dist APK size" "$dbytes" "$(size "$DIST/score-release.apk")"
    fi
else
    note "score-dist not found at $DIST - skipping the distribution checks"
fi

# ------------------------------------------------------- the claim, re-established
if [ "$REBUILD" = 1 ]; then
    if [ ! -f "$ROOT/tools/release.keystore" ]; then
        bad "--rebuild needs tools/release.keystore"
    else
        before_release=$(sha256 "$RELEASE_APK")
        sh "$ROOT/build.sh" >/dev/null 2>&1 || bad "debug rebuild failed"
        d1=$(sha256 "$DEBUG_APK")
        sh "$ROOT/build.sh" >/dev/null 2>&1 || bad "debug rebuild failed"
        d2=$(sha256 "$DEBUG_APK")
        same "debug rebuild repeats (1st vs 2nd)" "$d1" "$d2"
        SIGN=release sh "$ROOT/build.sh" >/dev/null 2>&1 || bad "release rebuild failed"
        r1=$(sha256 "$RELEASE_APK")
        SIGN=release sh "$ROOT/build.sh" >/dev/null 2>&1 || bad "release rebuild failed"
        r2=$(sha256 "$RELEASE_APK")
        same "release rebuild repeats (1st vs 2nd)" "$r1" "$r2"
        same "release rebuild reproduces the shipped bytes" "$before_release" "$r2"
    fi
fi

# --------------------------------------------------------- what the release served
if [ "$ONLINE" = 1 ]; then
    api="https://api.github.com/repos/$REPO_SLUG/releases/latest"
    json=$(curl -sS "$api" 2>/dev/null || true)
    if [ -z "$json" ]; then
        bad "--online: could not read $api"
    elif command -v python3 >/dev/null 2>&1; then
        printf '%s' "$json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
a = (d.get("assets") or [{}])[0]
body = d.get("body") or ""
digest = (a.get("digest") or "").removeprefix("sha256:")
line = ""
for ln in body.splitlines():
    if "SHA-256 of score-release.apk:" in ln:
        line = ln.split(":")[-1].strip()
print(d.get("tag_name", ""))
print(a.get("size", ""))
print(digest)
print(line)
' > /tmp/score-release-online.$$ 2>/dev/null
        tag=$(sed -n 1p /tmp/score-release-online.$$)
        asize=$(sed -n 2p /tmp/score-release-online.$$)
        adigest=$(sed -n 3p /tmp/score-release-online.$$)
        abody=$(sed -n 4p /tmp/score-release-online.$$)
        rm -f /tmp/score-release-online.$$
        note "published release: $tag, asset $asize bytes"
        same "release asset size vs local build" "$(size "$RELEASE_APK")" "$asize"
        same "release asset digest vs local build" "$(sha256 "$RELEASE_APK")" "$adigest"
        same "release notes hash vs the asset" "$adigest" "$abody"
    else
        note "--online needs python3 to read the API response - skipped"
    fi
fi

echo
if [ "$FAILS" = 0 ]; then
    echo "release claims agree with the artifacts"
else
    echo "$FAILS claim(s) disagree with the artifacts"
    exit 1
fi
