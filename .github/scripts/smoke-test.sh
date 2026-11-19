#!/usr/bin/env bash
# Emulator smoke test for FilmPilot: launch, shoot frames with the emulated camera,
# check they land in Pictures/FilmPilot, open the sheets, and fail on any crash.
set -u
PKG=com.filmpilot.app
OUT=filmpilot-screenshots
mkdir -p "$OUT"
FAILED=0

step()  { echo; echo "=== $*"; }
fail()  { echo "FAIL: $*"; FAILED=1; }
shot()  { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump()  { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
check_crash() {
  if adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then
    fail "crash during: $1"; adb logcat -d -b crash | tail -60
  fi
}
# Tap the centre of the first UI node whose content-desc starts with $1.
tap_desc() {
  dump
  local node nums
  node=$(grep -o "<node [^>]*content-desc=\"$1[^\"]*\"[^>]*>" "$OUT/ui.xml" | head -1)
  [ -z "$node" ] && { echo "no node with description '$1'"; return 1; }
  nums=($(echo "$node" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+'))
  adb shell input tap $(( (nums[0] + nums[2]) / 2 )) $(( (nums[1] + nums[3]) / 2 ))
}
photo_count() {
  adb shell ls /sdcard/Pictures/FilmPilot 2>/dev/null | tr -d '\r' | grep -c '\.jpg$' || true
}

step "Install (granting camera permission)"
adb install -r -g FilmPilot.apk || { echo "install failed"; exit 1; }
adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
adb shell wm dismiss-keyguard; sleep 1

step "Launch"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
shot 01-viewfinder
check_crash "launch"

step "Shoot 3 frames"
BEFORE=$(photo_count)
for i in 1 2 3; do
  tap_desc "Shutter" || fail "shutter button not found"
  sleep 5
done
# Developing a full-size frame on a software-rendered emulator can take a while.
for _ in $(seq 1 12); do
  [ "$(( $(photo_count) - BEFORE ))" -ge 3 ] && break
  sleep 5
done
shot 02-after-shots
AFTER=$(photo_count)
echo "photos in Pictures/FilmPilot: before=$BEFORE after=$AFTER"
[ "$((AFTER - BEFORE))" -ge 3 ] || fail "expected 3 new photos in Pictures/FilmPilot"
dump
grep -q "3 of 24 shots taken" "$OUT/ui.xml" || fail "shot counter should read 3 of 24"
check_crash "shooting"

step "Pull one developed photo"
NAME=$(adb shell ls /sdcard/Pictures/FilmPilot 2>/dev/null | tr -d '\r' | grep '\.jpg$' | head -1)
[ -n "$NAME" ] && adb pull "/sdcard/Pictures/FilmPilot/$NAME" "$OUT/developed-$NAME" >/dev/null 2>&1

step "Adjust sheet"
tap_desc "Adjust" || fail "adjust button not found"
sleep 2; shot 03-adjust
adb shell input keyevent KEYCODE_BACK; sleep 1
check_crash "adjust sheet"

step "Film picker"
tap_desc "Film:" || fail "film chip not found"
sleep 2; shot 04-film-picker
adb shell input keyevent KEYCODE_BACK; sleep 1
check_crash "film picker"

step "Restart keeps the roll counter"
adb shell am force-stop "$PKG"; sleep 1
adb shell am start -W -n "$PKG/.MainActivity"; sleep 5
dump
grep -q "3 of 24 shots taken" "$OUT/ui.xml" || fail "counter should survive a restart"
shot 05-restarted
check_crash "restart"

adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
echo
if [ "$FAILED" -ne 0 ]; then echo "Smoke test FAILED"; exit 1; fi
echo "Smoke test passed"
