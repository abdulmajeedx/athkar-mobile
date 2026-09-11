#!/usr/bin/env bash
#
# Captures store screenshots from an already-booted emulator: installs the APK, cleans up the
# status bar, then photographs the home screen and every screen reachable from one tap on a
# labelled element. The UI dumps are kept next to the images so the labels can be read later.
#
#   scripts/ci/store-screenshots.sh <apk> <package> <out-dir>

set -euo pipefail

APK=${1:?usage: store-screenshots.sh <apk> <package> <out-dir>}
PKG=${2:?usage: store-screenshots.sh <apk> <package> <out-dir>}
OUT=${3:?usage: store-screenshots.sh <apk> <package> <out-dir>}
mkdir -p "$OUT"

adb install -r -g "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true
adb emu geo fix 46.6753 24.7136 || true

# A demo-mode status bar: fixed clock, full battery, no stray notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0900 > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4 > /dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false > /dev/null

shot() {
    adb exec-out screencap -p > "$OUT/$1.png"
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null
    echo "captured $1"
}

launch() {
    adb shell am force-stop "$PKG"
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
    sleep 6
}

launch
shot 00-home

# Every clickable element with a label on the home screen, top to bottom.
python3 - "$OUT/00-home.xml" > "$OUT/targets.txt" <<'EOF'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
seen = set()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    if 'clickable="true"' not in n:
        continue
    label = re.search(r'text="([^"]*)"', n).group(1) or re.search(r'content-desc="([^"]*)"', n).group(1)
    if not label or label in seen:
        continue
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    seen.add(label)
    print((x1 + x2) // 2, (y1 + y2) // 2, label)
EOF

i=1
while read -r x y label; do
    [ "$i" -gt 12 ] && break
    launch
    adb shell input tap "$x" "$y"
    sleep 4
    shot "$(printf '%02d' "$i")"
    echo "$(printf '%02d' "$i") $label" >> "$OUT/index.txt"
    i=$((i + 1))
done < "$OUT/targets.txt"

adb shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null || true
ls -la "$OUT"
