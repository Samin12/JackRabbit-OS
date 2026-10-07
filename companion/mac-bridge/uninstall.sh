#!/usr/bin/env bash
# Stop and remove the SamRabbit Mac bridge LaunchAgent. Keeps the token unless --purge is given
# (the R1 then needs a new token: run install.sh and connect the bridge again).
set -euo pipefail

LABEL=com.samrabbit.bridge
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"
APP_DIR="$HOME_DIR/Library/Application Support/SamRabbit/bridge"
TOKEN_FILE="$HOME_DIR/.config/samrabbit/bridge-token"
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

if [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" != 1 ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
fi
rm -f "$PLIST"
rm -rf "$APP_DIR"
rmdir "$(dirname "$APP_DIR")" 2>/dev/null || true
if [ $PURGE = 1 ]; then rm -f "$TOKEN_FILE"; echo "removed the bridge token"; fi
echo "SamRabbit bridge uninstalled (log kept at ~/Library/Logs/samrabbit-bridge.log)"
