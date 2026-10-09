#!/usr/bin/env bash
# Generates apple/SamRabbit.xcodeproj from apple/project.yml (XcodeGen). The project is not
# committed: run this after every pull or project.yml change. Never hand-edit the .pbxproj.
#
#   apple/generate.sh            generate
#   apple/generate.sh --open     generate and open in Xcode
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
if ! command -v xcodegen >/dev/null 2>&1; then
  echo "generate.sh: XcodeGen is missing (brew install xcodegen)" >&2
  exit 69
fi
# The app icon is committed; re-render it only when it is missing (tools/render_icon.swift).
ICON="$HERE/iOS/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
if [ ! -s "$ICON" ]; then
  echo "rendering the app icon"
  mkdir -p "$HERE/build"
  xcrun swiftc -O -parse-as-library "$HERE/tools/render_icon.swift" \
    "$HERE/Shared/SamRabbitKit/Sources/SamRabbitKit/Orb/OrbRenderer.swift" -o "$HERE/build/render_icon"
  mkdir -p "$(dirname "$ICON")"
  "$HERE/build/render_icon" "$ICON"
fi
xcodegen generate --spec project.yml --project . --quiet
echo "generated $HERE/SamRabbit.xcodeproj"
if [ "${1:-}" = "--open" ]; then open "$HERE/SamRabbit.xcodeproj"; fi
