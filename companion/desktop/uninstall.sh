#!/usr/bin/env bash
# Remove the SamRabbit desktop app and its login item. Keeps the desktop token and the log unless
# --purge is given (the bridge then refuses the app until install.sh creates a new token).
#
#   companion/desktop/uninstall.sh [--purge] [--apps-dir /Applications]
set -euo pipefail

LABEL=com.samrabbit.desktop.login
BUNDLE_ID=com.samrabbit.desktop
APPS_DIR=/Applications
PURGE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --purge) PURGE=1; shift ;;
    --apps-dir) APPS_DIR=$2; shift 2 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "uninstall.sh: unknown option $1" >&2; exit 64 ;;
  esac
done
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"
TOKEN_FILE="$HOME_DIR/.config/samrabbit/desktop-token"
SKIP=${SAMRABBIT_SKIP_LAUNCHCTL:-0}

if [ "$SKIP" != 1 ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
  if pgrep -x SamRabbit >/dev/null 2>&1; then
    pkill -TERM -x SamRabbit || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -x SamRabbit >/dev/null 2>&1 || break; sleep 0.5; done
    pkill -KILL -x SamRabbit 2>/dev/null || true
  fi
fi
rm -f "$PLIST"
rm -rf "$APPS_DIR/SamRabbit.app"
if [ $PURGE = 1 ]; then
  rm -f "$TOKEN_FILE" "$HOME_DIR/Library/Logs/samrabbit-desktop.log"
  [ "$SKIP" != 1 ] && defaults delete "$BUNDLE_ID" >/dev/null 2>&1 || true
  echo "removed the desktop token, the log and the app's settings"
fi
echo "SamRabbit desktop app uninstalled"
