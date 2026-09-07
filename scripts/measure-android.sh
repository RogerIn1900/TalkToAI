#!/bin/bash
set -euo pipefail
# Repeatable debug/AVD diagnostic sampling, not a release-device benchmark.
serial=emulator-5554
package=com.example.talktoai
destination=${1:?Usage: measure-android.sh output-directory}
mkdir -p "$destination"
adb -s "$serial" shell getprop > "$destination/device.txt"
git rev-parse HEAD > "$destination/commit.txt"
for sample in 1 2 3 4 5; do
  adb -s "$serial" shell am force-stop "$package"
  adb -s "$serial" shell am start -W -n "$package/.KuiklyRenderActivity" > "$destination/start-$sample.txt"
  sleep 3
done
adb -s "$serial" shell dumpsys meminfo "$package" > "$destination/memory.txt"
adb -s "$serial" shell dumpsys gfxinfo "$package" reset > /dev/null
for gesture in 1 2 3 4 5; do
  adb -s "$serial" shell input swipe 540 700 540 1700 500
  adb -s "$serial" shell input swipe 540 1700 540 700 500
done
adb -s "$serial" shell dumpsys gfxinfo "$package" > "$destination/frames.txt"
adb -s "$serial" shell logcat -d -b events | rg 'com\.example\.talktoai' > "$destination/app-events.txt" || true
adb -s "$serial" exec-out screencap -p > "$destination/screen.png"
