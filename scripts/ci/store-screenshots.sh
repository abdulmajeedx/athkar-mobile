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
    # uiautomator occasionally answers "null root node" while a frame is still settling; retry.
    local attempt
    for attempt in 1 2 3 4 5 6; do
        adb shell rm -f /sdcard/ui.xml
        if adb shell uiautomator dump /sdcard/ui.xml | grep -q "dumped to" \
            && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null; then
            echo "captured $1"
            return 0
        fi
        echo "uiautomator dump failed for $1 (attempt $attempt), retrying"
        sleep 3
    done
    echo "giving up on the UI dump for $1; keeping the image only"
    echo '<hierarchy/>' > "$OUT/$1.xml"
}

launch() {
    adb shell am force-stop "$PKG"
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
    sleep 10
}

# Taps the centre of the first node whose text or content-desc contains the given label.
tap_label() {
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null || true
    adb pull /sdcard/ui.xml /tmp/tap.xml > /dev/null || return 1
    local xy
    xy=$(python3 - /tmp/tap.xml "$1" <<'PYEOF'
import re, sys
import xml.etree.ElementTree as ET
want = sys.argv[2]
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    t = (n.get("text") or n.get("content-desc") or "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if want in t and m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYEOF
)
    [ -n "$xy" ] || { echo "no node labelled '$1'"; return 1; }
    adb shell input tap $xy
}

dump_labels() {
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null || true
    adb pull /sdcard/ui.xml /tmp/labels.xml > /dev/null || return 0
    python3 - /tmp/labels.xml <<'PYEOF'
import sys
import xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    t = (n.get("text") or n.get("content-desc") or "").strip()
    if t:
        print(n.get("bounds"), "clickable" if n.get("clickable") == "true" else "-", n.get("class"), t[:60])
PYEOF
}

# Prayer times and qibla need a location. The emulator's GPS fix is not picked up reliably, so
# pick a city from the app's own list instead; that choice persists for the later launches.
launch
if tap_label "الصلاة"; then
    sleep 2
    if tap_label "اختيار مدينة"; then
        sleep 3
        echo "--- city picker ---"
        dump_labels
        tap_label "الرياض" || tap_label "مكة" || true
        sleep 6
        shot 01-prayer
        echo "01-prayer الصلاة" >> "$OUT/index.txt"
        tap_label "القبلة" || true
        sleep 5
        shot 02-qibla
        echo "02-qibla القبلة" >> "$OUT/index.txt"
    fi
fi

launch
sleep 5
shot 00-home

# Every clickable element that carries a label itself or through a descendant (Compose marks the
# clickable row, and the text lives in a child), top to bottom. Bottom-navigation tabs first so the
# prayer-times and qibla screens are always among the captured ones.
targets() {
    python3 - "$1" <<'PYEOF'
import re, sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
def label(node):
    for n in node.iter():
        t = (n.get("text") or n.get("content-desc") or "").strip()
        if t:
            return t
    return ""
seen = set()
rows = []
for node in root.iter("node"):
    if node.get("clickable") != "true":
        continue
    text = label(node)
    if not text or text in seen:
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    if y2 - y1 < 8 or x2 - x1 < 8:
        continue
    seen.add(text)
    rows.append(((x1 + x2) // 2, (y1 + y2) // 2, text.replace("\n", " ")[:40]))
for x, y, text in rows:
    if y <= 2100:
        print(x, y, text)
PYEOF
}
targets "$OUT/00-home.xml" > "$OUT/targets.txt"
echo "--- tap targets ---"
cat "$OUT/targets.txt"
echo "--- every labelled node on the home screen ---"
python3 - "$OUT/00-home.xml" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    t = (n.get("text") or n.get("content-desc") or "").strip()
    if t:
        print(n.get("bounds"), "clickable" if n.get("clickable") == "true" else "-", n.get("class"), t[:60])
PYEOF

# adb reads from stdin, so the target list is read through fd 3 rather than the loop's stdin.
i=1
while read -r -u 3 x y label; do
    [ "$i" -gt 12 ] && break
    n=$(printf '%02d' "$i")
    launch
    adb shell input tap "$x" "$y"
    sleep 4
    shot "$n"
    echo "$n $label" >> "$OUT/index.txt"
    # One level deeper: the first labelled element of the new screen that the home screen did not
    # have (e.g. the first dhikr of a chapter), skipping the bottom navigation.
    deeper=$(targets "$OUT/$n.xml" | awk -v home="$OUT/targets.txt" '
        BEGIN { while ((getline line < home) > 0) { sub(/^[0-9]+ [0-9]+ /, "", line); known[line] = 1 } }
        $2 < 2100 { l = $0; sub(/^[0-9]+ [0-9]+ /, "", l); if (!(l in known)) { print; exit } }')
    if [ -n "$deeper" ]; then
        set -- $deeper
        adb shell input tap "$1" "$2"
        sleep 4
        shot "$n-b"
        echo "$n-b $label > ${*:3}" >> "$OUT/index.txt"
    fi
    i=$((i + 1))
done 3< "$OUT/targets.txt"

adb shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null || true
ls -la "$OUT"
