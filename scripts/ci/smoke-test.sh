#!/usr/bin/env bash
#
# Installs an APK on the attached device, launches it, and fails if it does not survive.
#
# This exists because a suite of unit tests can be entirely green while the app crashes before it
# draws a frame: nothing in them ever calls Application.onCreate. The check is deliberately crude —
# start it, wait, is it still there — because that is the exact question the tests cannot answer.
#
#   scripts/ci/smoke-test.sh <apk> <applicationId> [seconds-to-observe]

set -euo pipefail

APK=${1:?usage: smoke-test.sh <apk> <applicationId> [seconds]}
APP_ID=${2:?usage: smoke-test.sh <apk> <applicationId> [seconds]}
OBSERVE_SECONDS=${3:-20}
LOGCAT_OUT=${SMOKE_LOGCAT_OUT:-logcat.txt}

fail() {
    echo "::error::$1"
    echo "----- last 200 lines of logcat -----"
    tail -200 "$LOGCAT_OUT" 2>/dev/null || true
    exit 1
}

echo "Waiting for the device to finish booting..."
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'

# -r replaces any previous install, -g pre-grants runtime permissions so a permission dialog cannot
# be mistaken for the app failing to start.
echo "Installing $APK..."
adb install -r -g "$APK"

# The launcher activity is read from the APK rather than hardcoded, so renaming or moving it cannot
# leave this script silently starting nothing.
ACTIVITY=$(aapt2 dump badging "$APK" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -1)
[ -n "$ACTIVITY" ] || fail "No launchable activity declared in $APK"
echo "Launching $APP_ID/$ACTIVITY..."

adb logcat -c || true
adb shell am start -W -n "$APP_ID/$ACTIVITY" || fail "am start failed"

echo "Observing for ${OBSERVE_SECONDS}s..."
sleep "$OBSERVE_SECONDS"

adb logcat -d > "$LOGCAT_OUT" 2>/dev/null || true

# Three independent signals, because each one alone has a blind spot: a crash on a background thread
# never kills the process, a process can die without logging FATAL, and an ANR leaves it alive but
# useless.
# Scoped to our process. An emulator image runs a dozen Google apps that crash happily on their
# own — Gmail dies every boot for want of Play Services accounts — and an unscoped grep fails every
# release on somebody else's stack trace. Each FATAL block names its process on the following line.
CRASH_BLOCK=$(awk -v app="$APP_ID" '
    /FATAL EXCEPTION/ { collecting = 1; block = $0 "\n"; owned = 0; next }
    collecting {
        block = block $0 "\n"
        if ($0 ~ ("Process: " app ",")) { owned = 1 }
        if ($0 !~ /AndroidRuntime/) { if (owned) { printf "%s", block } collecting = 0 }
    }
    END { if (collecting && owned) printf "%s", block }
' "$LOGCAT_OUT")

if [ -n "$CRASH_BLOCK" ]; then
    echo "----- fatal exception in $APP_ID -----"
    printf '%s\n' "$CRASH_BLOCK" | head -60
    fail "The app crashed on launch"
fi

if grep -qE "ANR in $APP_ID" "$LOGCAT_OUT"; then
    fail "The app was not responding on launch"
fi

PID=$(adb shell pidof "$APP_ID" | tr -d '\r\n' || true)
[ -n "$PID" ] || fail "The app is no longer running (no process for $APP_ID)"

RESUMED=$(adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" || true)
case "$RESUMED" in
    *"$APP_ID"*) : ;;
    *) fail "The app is running but is not the foreground activity: ${RESUMED:-<none>}" ;;
esac

echo "Smoke test passed: $APP_ID launched and stayed in the foreground for ${OBSERVE_SECONDS}s (pid $PID)."
