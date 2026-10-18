#!/usr/bin/env bash
# Installs a release APK on the running emulator, launches it and waits for the
# first-run welcome screen. Fails if the app crashes, dies, or never shows the
# screen. Matches the screen by view id, not text, so it holds in any locale.
set -euo pipefail

APK="$1"
PKG="io.pezkuwichain.wallet"
OUT="${SMOKE_OUT:-smoke-out}"
TIMEOUT=120
mkdir -p "$OUT"

fail() {
  echo "::error::$1"
  adb exec-out screencap -p > "$OUT/screen.png" || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null || true
  adb logcat -d > "$OUT/logcat.txt" || true
  exit 1
}

adb install -r "$APK" >/dev/null || fail "install failed"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || fail "launch failed"

for _ in $(seq 1 $((TIMEOUT / 3))); do
  sleep 3
  if adb logcat -d -b crash | grep -q "Process: $PKG"; then
    fail "the app crashed on launch"
  fi
  adb shell pidof "$PKG" >/dev/null || fail "the app process is gone"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || continue
  if adb exec-out cat /sdcard/ui.xml | grep -q "$PKG:id/welcomeCreateWalletButtonNAIL" &&
     adb exec-out cat /sdcard/ui.xml | grep -q "$PKG:id/welcomeRestoreWalletButton"; then
    echo "welcome screen shown, both create and restore buttons present"
    adb exec-out screencap -p > "$OUT/screen.png" || true
    exit 0
  fi
done
fail "the welcome screen did not appear within ${TIMEOUT}s"
