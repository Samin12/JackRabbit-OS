#!/usr/bin/env bash
# Builds SamRabbit (app + widgets) for a simulator, installs it and launches it.
#   apple/dev/run-sim.sh [simulator-udid]      (default: the booted iPhone)
# Start the fake bridge first: /usr/bin/python3 -I apple/dev/fake_bridge.py
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
UDID=${1:-booted}
DEST="platform=iOS Simulator,id=$UDID"
if [ "$UDID" = booted ]; then
  UDID=$(xcrun simctl list devices booted | grep -m1 -oE '[0-9A-F-]{36}')
  DEST="platform=iOS Simulator,id=$UDID"
fi
[ -d "$HERE/SamRabbit.xcodeproj" ] || "$HERE/generate.sh"
xcodebuild -project "$HERE/SamRabbit.xcodeproj" -scheme SamRabbit -destination "$DEST" \
  -derivedDataPath "$HERE/build/DerivedData" -quiet build
APP="$HERE/build/DerivedData/Build/Products/Debug-iphonesimulator/SamRabbit.app"
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --terminate-running-process "$UDID" com.samrabbit.mobile >/dev/null
echo "running SamRabbit on $UDID"
