#!/bin/sh
# Create the release signing key for public distribution.
#
#   sh tools/make-release-key.sh
#
# Writes tools/release.keystore plus tools/release-credentials.txt (both git-ignored). The keystore is
# the app's permanent identity: Android refuses to update an installed app if the signature changes,
# so losing it means never shipping an update under the same package. Back both files up somewhere
# that is not this machine and not this repository. (If you later publish on Play with Play App
# Signing, the key you upload with is an "upload key" and Google can reset it for you - but keep it
# anyway.)
#
# Override the certificate subject with e.g.:
#   CERT_CN="Your Name" CERT_ORG="Your Org" CERT_CC=US sh tools/make-release-key.sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TB="$ROOT/toolchain"
KS="$ROOT/tools/release.keystore"
CRED="$ROOT/tools/release-credentials.txt"
ALIAS=${KEY_ALIAS:-score}
CN=${CERT_CN:-"Kevin Score Release"}
ORG=${CERT_ORG:-"Kevin"}
CC=${CERT_CC:-US}

[ -x "$TB/jdk/bin/keytool.exe" ] || { echo "missing JDK - run: sh tools/bootstrap.sh"; exit 1; }
if [ -f "$KS" ]; then
  echo "refusing to overwrite the existing $KS"
  echo "(delete it deliberately if you really mean to re-key, but read the header of this script first)"
  exit 1
fi

PW=$(python -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(32)))")

"$TB/jdk/bin/keytool.exe" -genkeypair \
  -keystore "$(cygpath -w "$KS")" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10950 \
  -dname "CN=$CN, O=$ORG, C=$CC" \
  -storepass "$PW" -keypass "$PW"

{
  echo "# release signing credentials - DO NOT COMMIT - back this up off-machine"
  echo "keystore=$(cygpath -m "$KS")"
  echo "storetype=PKCS12"
  echo "alias=$ALIAS"
  echo "storepass=$PW"
} > "$CRED"

echo
echo "created $(cygpath -m "$KS")"
echo "created $(cygpath -m "$CRED")"
echo
echo "signing password: $PW"
echo
echo "Store that password in a password manager and copy both files off this machine."
echo "Then:  SIGN=release sh build.sh"
