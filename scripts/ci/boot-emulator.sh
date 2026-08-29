#!/usr/bin/env bash
#
# Boots a headless Android emulator and returns once it is ready for adb.
#
# Written out by hand rather than delegated to an action because the action hides the emulator's
# own output, and that output was the only thing that ever explained a failure here: two runs died
# with a bare "Timeout waiting for emulator to boot" while the real message — a missing shared
# library — sat in a log nobody could see.
#
#   scripts/ci/boot-emulator.sh <api-level> <target>

set -euo pipefail

API_LEVEL=${1:?usage: boot-emulator.sh <api-level> <target>}
TARGET=${2:?usage: boot-emulator.sh <api-level> <target>}
ARCH=${EMULATOR_ARCH:-x86_64}
BOOT_TIMEOUT=${EMULATOR_BOOT_TIMEOUT:-600}
EMULATOR_LOG=${EMULATOR_LOG:-emulator.log}

# avdmanager honours XDG_CONFIG_HOME and writes to ~/.config/.android/avd; the emulator only looks
# in ANDROID_AVD_HOME, ANDROID_SDK_HOME/avd and ~/.android/avd. Naming it makes the two agree.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"

export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
SDKMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager | head -1)
AVDMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/avdmanager | head -1)
IMAGE="system-images;android-${API_LEVEL};${TARGET};${ARCH}"

echo "Installing $IMAGE..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "emulator" "$IMAGE" > /dev/null

echo "Creating the AVD in $ANDROID_AVD_HOME..."
echo no | "$AVDMANAGER" create avd --force --name ci --package "$IMAGE" > /dev/null

# The default userdata partition is ~7 GB, which a runner that has just built the app no longer
# has. Installing one APK needs a tiny fraction of that.
echo "disk.dataPartition.size=${EMULATOR_DATA_PARTITION:-3072M}" >> "$ANDROID_AVD_HOME/ci.avd/config.ini"
emulator -list-avds

echo "Starting the emulator..."
nohup emulator -avd ci \
    -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim \
    -no-snapshot -no-metrics -camera-back none \
    > "$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

deadline=$((SECONDS + BOOT_TIMEOUT))
while [ "$SECONDS" -lt "$deadline" ]; do
    # A dead process is reported immediately: waiting out a ten-minute timeout for something that
    # exited in two seconds is how the first diagnosis went wrong.
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "::error::The emulator exited after $SECONDS seconds"
        tail -60 "$EMULATOR_LOG" || true
        exit 1
    fi
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        echo "Emulator ready after ${SECONDS}s."
        # The emulator's own System UI stalls under load and throws a modal "isn't responding"
        # dialog over everything, which swallows taps and photographs itself instead of the app.
        adb shell settings put global hide_error_dialogs 1 || true
        adb shell settings put global window_animation_scale 0 || true
        adb shell settings put global transition_animation_scale 0 || true
        adb shell settings put global animator_duration_scale 0 || true
        exit 0
    fi
    sleep 5
done

echo "::error::The emulator did not finish booting within ${BOOT_TIMEOUT}s"
tail -60 "$EMULATOR_LOG" || true
exit 1
