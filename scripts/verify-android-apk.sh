#!/usr/bin/env bash
# Check actual DEX definitions: references alone do not make an APK runnable.
set -euo pipefail
apk=${1:?Usage: verify-android-apk.sh path-to-apk}
[[ -f "$apk" ]] || { echo "APK does not exist: $apk" >&2; exit 1; }
analyzer=${APKANALYZER:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/cmdline-tools/latest/bin/apkanalyzer}
[[ -x "$analyzer" ]] || { echo 'Set APKANALYZER or ANDROID_HOME to an installed Android SDK.' >&2; exit 1; }
classes=$(mktemp)
trap 'rm -f "$classes"' EXIT
"$analyzer" dex packages --defined-only "$apk" > "$classes"
# Manifest startup components and the integrated native dashboard must be packaged.
required_classes=(
  androidx.core.content.FileProvider
  androidx.appcompat.app.AppCompatDelegate
  com.example.talktoai.KRApplication
  com.example.talktoai.KuiklyRenderActivity
  com.example.talktoai.dashboard.TalkDashboardView
  com.talktoai.marketui.dashboard.DashboardView
)
missing=0
for class_name in "${required_classes[@]}"; do
  if ! awk -v required="$class_name" '$1 == "C" && $2 == "d" && $NF == required { found=1 } END { exit !found }' "$classes"; then
    echo "Missing DEX class definition: $class_name" >&2
    missing=1
  fi
done
[[ "$missing" == 0 ]] || exit 1
echo "APK startup/dashboard class definitions verified: $apk"
