#!/bin/sh
# Drive the installed app on the emulator or a real device and print a behavioural trace.
# usage: sh tools/verify-ui.sh [adb-serial] [screenshot-prefix]
set -eu
# Git Bash would rewrite /sdcard/... into its own install root; keep device paths literal.
export MSYS_NO_PATHCONV=1
ROOT=$(cygpath -m "$(cd "$(dirname "$0")/.." && pwd)")   # C:/... - native adb rejects backslash paths
ADB="$ROOT/toolchain/sdk/platform-tools/adb.exe"
SERIAL=${1:-}
PREFIX=${2:-}
OUT="$ROOT/build/verify"
UI="$OUT/ui.xml"
APP=com.kevin.score
mkdir -p "$OUT"

adbs() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

# On a rotated display adb's input coordinates follow the current orientation while uiautomator
# reports bounds in the natural one, so the two disagree and taps land on the wrong widget.
# Pin the device to portrait for the whole trace and put the original settings back at the end.
PRE_ROT=$(adbs shell settings get system user_rotation 2>/dev/null | tr -d '\r')
PRE_ACCEL=$(adbs shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')

restore_rotation() {
  adbs shell settings put system accelerometer_rotation "$PRE_ACCEL" >/dev/null 2>&1 || true
  adbs shell settings put system user_rotation "$PRE_ROT" >/dev/null 2>&1 || true
  echo "rotation restored: accelerometer_rotation=$PRE_ACCEL user_rotation=$PRE_ROT"
}

pin_portrait() {
  adbs shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
  adbs shell settings put system user_rotation 0 >/dev/null 2>&1 || true
  sleep 1
}

shoot() {
  adbs shell screencap -p "/sdcard/$1.png" >/dev/null 2>&1
  adbs pull "/sdcard/$1.png" "$OUT/$PREFIX$1.png" >/dev/null 2>&1
}

# Guards against the rotated-display trap: the dump space must match the framebuffer.
space_check() {
  dump
  root=$(grep -oE '<node[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' "$UI" | head -1 | sed -E 's/.*bounds="([^"]*)".*/\1/')
  shoot space
  dims=$(python -c "from PIL import Image;print('%dx%d' % Image.open(r'$OUT/${PREFIX}space.png').size)" 2>/dev/null || echo "?")
  echo "space check: uiautomator root $root vs screencap $dims"
}

dump() {
  i=0
  rm -f "$UI"
  while [ $i -lt 4 ]; do
    adbs shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adbs pull /sdcard/ui.xml "$UI" >/dev/null 2>&1 && grep -q "<node" "$UI"; then
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "uiautomator dump failed" >&2
  return 1
}

node() { grep -oE "<node[^>]*com\.kevin\.score:id/$1\"[^>]*>" "$UI" | head -1; }
node_text() { grep -oiE "<node[^>]*text=\"$1\"[^>]*>" "$UI" | head -1; }
node_class() { grep -oE "<node[^>]*class=\"android\.widget\.$1\"[^>]*>" "$UI" | head -1; }
attr() { echo "$1" | sed -E "s/.*$2=\"([^\"]*)\".*/\1/"; }

text_of() { n=$(node "$1"); [ -n "$n" ] && attr "$n" text || echo "<missing>"; }
bounds_of() { n=$(node "$1"); [ -n "$n" ] && attr "$n" bounds || echo "<missing>"; }
center_of() { echo "$1" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
edit_text() { n=$(node_class EditText); [ -n "$n" ] && attr "$n" text || echo "<no dialog>"; }

tap_id() { dump; set -- $(center_of "$(node "$1")"); adbs shell input tap "$1" "$2"; sleep 0.4; }
tap_label() { dump; set -- $(center_of "$(node_text "$1")"); adbs shell input tap "$1" "$2"; sleep 0.8; }
hold_id() { id=$1; ms=$2; dump; set -- $(center_of "$(node "$id")"); x=$1; y=$2; adbs shell input swipe "$x" "$y" "$x" "$y" "$ms"; sleep 0.8; }

# Vertical flick starting on a widget's own centre (tests the intercept path).
swipe_from() {
  id=$1
  dy=$2
  dump
  set -- $(center_of "$(node "$id")")
  cx=$1
  cy=$2
  adbs shell input swipe "$cx" "$cy" "$cx" $((cy + dy)) 250
  sleep 0.6
}

# Vertical flick on bare panel, away from every widget (tests the onTouchEvent path).
swipe_bare() {
  adbs shell input swipe "$1" "$2" "$1" "$3" 250
  sleep 0.6
}

report() {
  dump
  printf '%-32s home=%s away=%s | names=%s/%s\n' \
    "$1" "$(text_of home_score)" "$(text_of away_score)" "$(text_of home_name)" "$(text_of away_name)"
}

dialog_report() {
  dump
  printf '%-32s dialog editbox=%s\n' "$1" "$(edit_text)"
}

echo "== relaunch from a clean state =="
adbs shell am force-stop $APP
pin_portrait
adbs shell am start -W -n $APP/.MainActivity | grep -E "Status|TotalTime"
sleep 2
space_check

echo
echo "== scoring =="
report "initial"
tap_id home_score
report "tap home score (increments)"
tap_id home_score
report "tap home score again"
tap_id home_plus
report "home_plus"
tap_id home_minus
report "home_minus"
tap_id away_score
report "tap away score (increments)"
tap_id away_plus
report "away_plus"
tap_id away_reset
report "away_reset"
swipe_from home_score -260
report "swipe up on home score (+1)"
swipe_from home_score 260
report "swipe down on home score (-1)"
swipe_from away_score -260
report "swipe up on away score (+1)"
swipe_from away_score 260
report "swipe down on away score (-1)"
swipe_from home_plus -260
report "swipe up starting on the + tile (must be +1, no press)"
swipe_bare 16 900 640
report "swipe up on bare panel (+1)"
swipe_bare 16 640 900
report "swipe down on bare panel (-1)"
tap_id home_minus; tap_id home_minus; tap_id home_minus; tap_id home_minus
report "home_minus x4 (floor at 0)"
tap_id home_plus; tap_id home_plus; tap_id home_plus
report "home_plus x3"
echo "portrait geometry: home_score $(bounds_of home_score) away_score $(bounds_of away_score)"
shoot 02-portrait

echo
echo "== rename dwell =="
hold_id home_name 300
report "hold 300ms (below dwell)"
hold_id home_name 1500
shoot 03-rename-dialog
dialog_report "hold 1500ms (dwell reached)"
adbs shell input text "HAWKS"
sleep 0.5
shoot 04-rename-typed
dialog_report "typed HAWKS"
tap_label save
report "saved rename"
shoot 05-renamed

echo
echo "== rotation =="
adbs shell settings put system accelerometer_rotation 0
adbs shell settings put system user_rotation 1
sleep 3
report "rotated to landscape"
echo "landscape geometry: home_score $(bounds_of home_score) away_score $(bounds_of away_score)"
shoot 06-landscape
adbs shell settings put system user_rotation 0
sleep 3
report "rotated back to portrait"
shoot 07-portrait-again

echo
echo "== crash log (expect empty) =="
adbs logcat -d -s AndroidRuntime:E 2>&1 | grep -v "^---" | tail -5
restore_rotation
echo "== screenshots =="
ls -l "$OUT"/*.png
