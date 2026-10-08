#!/usr/bin/env bash
# Stop and remove the SamRabbit Mac bridge LaunchAgent. Keeps the tokens unless --purge is given
# (the R1 then needs a new token: run install.sh and connect the bridge again; paired iPhones and watches
# pair again too). The synced conversations in ~/Library/Application Support/SamRabbit/sync are always kept.
set -euo pipefail

LABEL=com.samrabbit.bridge
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"
APP_DIR="$HOME_DIR/Library/Application Support/SamRabbit/bridge"
TOKEN_FILE="$HOME_DIR/.config/samrabbit/bridge-token"
DESKTOP_TOKEN_FILE="$HOME_DIR/.config/samrabbit/desktop-token"
MOBILE_DEVICES_FILE="$HOME_DIR/.config/samrabbit/mobile-devices.json"
T3_TOKEN_FILE="$HOME_DIR/.config/samrabbit/t3-token"
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

if [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" != 1 ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
fi
rm -f "$PLIST"
rm -rf "$APP_DIR"
rmdir "$(dirname "$APP_DIR")" 2>/dev/null || true
if [ $PURGE = 1 ]; then
  rm -f "$TOKEN_FILE" "$DESKTOP_TOKEN_FILE" "$MOBILE_DEVICES_FILE" "$T3_TOKEN_FILE"
  echo "removed the bridge, desktop and T3 tokens and the paired phones (revoke \"SamRabbit bridge\" in T3 Code >"
  echo "Settings > Connections to end its T3 session now; it would expire by itself within 30 days)"
fi
echo "SamRabbit bridge uninstalled (log kept at ~/Library/Logs/samrabbit-bridge.log)"
