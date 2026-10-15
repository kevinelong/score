#!/bin/sh
# Build a signed debug APK with the Android SDK tools directly (no Gradle, no AndroidX, no deps).
# Run with Git's shell:  sh build.sh
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
WIN=$(cygpath -m "$ROOT")          # C:/... form for native Windows tools (javac, aapt2, d8, ...)
TB="$ROOT/toolchain"
BT="$TB/sdk/build-tools/34.0.0"
AAR="$WIN/toolchain/sdk/platforms/android-34/android.jar"

JAVA_HOME=$(cygpath -w "$TB/jdk")
export JAVA_HOME
PATH="$TB/jdk/bin:$PATH"
export PATH

APP="$ROOT/app"
B="$ROOT/build"
OUT="$ROOT/out"

[ -x "$TB/jdk/bin/javac.exe" ] || { echo "missing JDK - run: sh tools/bootstrap.sh"; exit 1; }
[ -f "$BT/aapt2.exe" ]        || { echo "missing build-tools - run: sh tools/bootstrap.sh"; exit 1; }

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$OUT"

echo "== launcher icons =="
[ -f "$APP/res/mipmap-xxhdpi/ic_launcher.png" ] || python "$WIN/tools/make_icons.py"

echo "== aapt2 compile =="
"$BT/aapt2.exe" compile --dir "$WIN/app/res" -o "$WIN/build/res.zip"

echo "== aapt2 link =="
"$BT/aapt2.exe" link \
  -o "$WIN/build/base.apk" \
  -I "$AAR" \
  --manifest "$WIN/app/AndroidManifest.xml" \
  --java "$WIN/build/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 6 \
  --version-name 1.5 \
  "$WIN/build/res.zip"

echo "== javac =="
: > "$B/sources.txt"
for f in "$APP"/java/com/kevin/score/*.java "$B"/gen/com/kevin/score/*.java; do
  [ -f "$f" ] || continue
  cygpath -m "$f" >> "$B/sources.txt"
done
wc -l < "$B/sources.txt" | tr -d ' ' | sed 's/^/source files: /'
javac -source 8 -target 8 \
  -bootclasspath "$AAR" -classpath "$AAR" \
  -encoding UTF-8 -Xlint:-options \
  -d "$WIN/build/classes" \
  @"$WIN/build/sources.txt"

echo "== d8 =="
"$TB/jdk/bin/jar.exe" cf "$WIN/build/classes.jar" -C "$WIN/build/classes" .
"$BT/d8.bat" --lib "$AAR" --min-api 21 --output "$WIN/build/dex" "$WIN/build/classes.jar"
ls -l "$B/dex"

echo "== package =="
python "$WIN/tools/add_dex.py" "$WIN/build/base.apk" "$WIN/build/unsigned.apk" "$WIN/build/dex/classes.dex"
"$BT/zipalign.exe" -p -f 4 "$WIN/build/unsigned.apk" "$WIN/build/aligned.apk"

echo "== sign =="
MODE=${SIGN:-debug}
if [ "$MODE" = release ]; then
  KS="$ROOT/tools/release.keystore"
  CRED="$ROOT/tools/release-credentials.txt"
  [ -f "$KS" ] || { echo "no release keystore - run: sh tools/make-release-key.sh"; exit 1; }
  [ -f "$CRED" ] || { echo "no $CRED - run: sh tools/make-release-key.sh"; exit 1; }
  STOREPASS=$(sed -n 's/^storepass=//p' "$CRED")
  KEYALIAS=$(sed -n 's/^alias=//p' "$CRED")
  APK=score-release.apk
  echo "signing with the release key (alias $KEYALIAS)"
else
  KS="$ROOT/tools/debug.keystore"
  STOREPASS=android
  KEYALIAS=androiddebugkey
  APK=score.apk
  if [ ! -f "$KS" ]; then
    "$TB/jdk/bin/keytool.exe" -genkeypair \
      -keystore "$(cygpath -w "$KS")" \
      -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
  fi
fi

"$BT/apksigner.bat" sign \
  --ks "$(cygpath -w "$KS")" \
  --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
  --ks-key-alias "$KEYALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$WIN/out/$APK" \
  "$WIN/build/aligned.apk"

echo "== verify =="
"$BT/apksigner.bat" verify --print-certs "$WIN/out/$APK"
echo "-- debuggable / testOnly in the manifest (both must be absent for release) --"
"$BT/aapt2.exe" dump xmltree --file AndroidManifest.xml "$WIN/out/$APK" | grep -iE "debuggable|testOnly" || echo "  neither present"
"$BT/aapt2.exe" dump badging "$WIN/out/$APK" | head -6
ls -l "$OUT/$APK"
echo "== built: $WIN/out/$APK =="
