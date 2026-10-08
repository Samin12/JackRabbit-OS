#!/usr/bin/env bash
# Builds the Apple Watch app (with its complications) for a watch simulator, installs it and launches it.
#   apple/dev/run-watch.sh [watch-udid] [-- launch arguments]
#     default watch: the one paired with the booted iPhone simulator
#     e.g. apple/dev/run-watch.sh -- -SamRabbitPage needs        (open on a page: status needs working upnext quick)
#          apple/dev/run-watch.sh -- -SamRabbitRoute phone       (every request through the iPhone)
# The watch gets its pairing from the iPhone app: pair the iPhone simulator with the bridge first
# (README), and pair the watch simulator with that iPhone: xcrun simctl pair <watch-udid> <iphone-udid>.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
UDID=""
if [ $# -gt 0 ] && [ "$1" != "--" ]; then UDID=$1; shift; fi
if [ "${1:-}" = "--" ]; then shift; fi
if [ -z "$UDID" ]; then
  UDID=$(xcrun simctl list pairs -j | /usr/bin/python3 -I -c '
import json, sys
pairs = json.load(sys.stdin)["pairs"].values()
print(next((p["watch"]["udid"] for p in pairs if p["phone"]["state"] == "Booted"), ""))')
fi
if [ -z "$UDID" ]; then
  echo "run-watch.sh: no watch simulator is paired with a booted iPhone (xcrun simctl pair <watch> <iphone>)" >&2
  exit 1
fi
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" >/dev/null
[ -d "$HERE/SamRabbit.xcodeproj" ] || "$HERE/generate.sh"
xcodebuild -project "$HERE/SamRabbit.xcodeproj" -scheme SamRabbitWatch -destination "platform=watchOS Simulator,id=$UDID" \
  -derivedDataPath "$HERE/build/DerivedData" -quiet build
APP="$HERE/build/DerivedData/Build/Products/Debug-watchsimulator/SamRabbitWatch.app"
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --terminate-running-process "$UDID" com.samrabbit.mobile.watchkitapp "$@" >/dev/null
echo "running SamRabbit on the watch $UDID"
