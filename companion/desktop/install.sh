#!/usr/bin/env bash
# Install (or refresh) the SamRabbit desktop app. Safe to run again.
#
#   companion/desktop/install.sh [--no-open] [--no-login-item] [--apps-dir /Applications]
#
# 1. builds SamRabbit.app (build.sh) and replaces /Applications/SamRabbit.app (quitting it first);
# 2. creates the desktop token ~/.config/samrabbit/desktop-token (0600) when missing, never printed;
# 3. installs the login item: LaunchAgent com.samrabbit.desktop.login (open -g -a SamRabbit --args --login);
# 4. opens the app and reports whether the bridge already serves the desktop page.
#
# Environment (tests): SAMRABBIT_HOME (default $HOME), SAMRABBIT_SKIP_LAUNCHCTL=1 (no launchctl/open/pkill).
set -euo pipefail

LABEL=com.samrabbit.desktop.login
BUNDLE_ID=com.samrabbit.desktop
APPS_DIR=/Applications
OPEN_APP=1
LOGIN_ITEM=1
PYTHON=${SAMRABBIT_PYTHON:-/usr/bin/python3}
while [ $# -gt 0 ]; do
  case "$1" in
    --no-open) OPEN_APP=0; shift ;;
    --no-login-item) LOGIN_ITEM=0; shift ;;
    --apps-dir) APPS_DIR=$2; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "install.sh: unknown option $1" >&2; exit 64 ;;
  esac
done

HERE=$(cd "$(dirname "$0")" && pwd)
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
CONFIG_DIR="$HOME_DIR/.config/samrabbit"
TOKEN_FILE="$CONFIG_DIR/desktop-token"
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"
APP="$APPS_DIR/SamRabbit.app"
SKIP=${SAMRABBIT_SKIP_LAUNCHCTL:-0}
[ -x "$PYTHON" ] || PYTHON=python3

# 1. Build.
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/samrabbit-build.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' EXIT
"$HERE/build.sh" --out "$BUILD_DIR" | sed 's/^/  /'

# 2. Desktop token (0600, never printed).
umask 077
mkdir -p "$CONFIG_DIR"; chmod 700 "$CONFIG_DIR"
if [ ! -s "$TOKEN_FILE" ]; then
  "$PYTHON" -c 'import secrets, sys; sys.stdout.write(secrets.token_urlsafe(32) + "\n")' > "$TOKEN_FILE.tmp"
  mv "$TOKEN_FILE.tmp" "$TOKEN_FILE"
  echo "created a new desktop token in $TOKEN_FILE"
else
  echo "keeping the existing desktop token in $TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"
umask 022

# 3. Replace the installed app (quit the running one first).
if [ "$SKIP" != 1 ] && pgrep -x SamRabbit >/dev/null 2>&1; then
  pkill -TERM -x SamRabbit || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -x SamRabbit >/dev/null 2>&1 || break; sleep 0.5; done
  pkill -KILL -x SamRabbit 2>/dev/null || true
fi
mkdir -p "$APPS_DIR"
rm -rf "$APP.new"
ditto "$BUILD_DIR/SamRabbit.app" "$APP.new"
rm -rf "$APP"
mv "$APP.new" "$APP"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
if [ "$SKIP" != 1 ] && [ -x "$LSREGISTER" ]; then "$LSREGISTER" -f "$APP" >/dev/null 2>&1 || true; fi
echo "installed $APP"

# 4. Login item (LaunchAgent; starts SamRabbit in the background at login, without a window).
DOMAIN="gui/$(id -u)"
if [ "$LOGIN_ITEM" = 1 ]; then
  mkdir -p "$(dirname "$PLIST")"
  "$PYTHON" - "$PLIST" "$LABEL" "$APP" <<'EOF'
import os, plistlib, sys
plist, label, app = sys.argv[1:]
value = {
    "Label": label,
    "ProgramArguments": ["/usr/bin/open", "-g", "-a", app, "--args", "--login"],
    "RunAtLoad": True,
    "KeepAlive": False,
    "LimitLoadToSessionType": "Aqua",
}
tmp = plist + ".tmp"
with open(tmp, "wb") as handle:
    plistlib.dump(value, handle)
os.chmod(tmp, 0o644)
os.replace(tmp, plist)
EOF
  echo "wrote $PLIST"
  if [ "$SKIP" != 1 ]; then
    launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
    for attempt in 1 2 3 4 5; do
      if launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null; then break; fi
      [ "$attempt" = 5 ] && { echo "install.sh: launchctl bootstrap failed" >&2; exit 70; }
      sleep 1
    done
    launchctl enable "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
  fi
else
  if [ "$SKIP" != 1 ]; then launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true; fi
  rm -f "$PLIST"
  echo "no login item (--no-login-item)"
fi

# 5. Open it and report on the bridge (the token is read from the file, never echoed).
if [ "$SKIP" != 1 ] && [ "$OPEN_APP" = 1 ]; then
  sleep 0.5
  open -a "$APP"
fi
BASE=$(defaults read "$BUNDLE_ID" BaseURL 2>/dev/null || echo "http://127.0.0.1:3780/app/")
"$PYTHON" - "$BASE" "$TOKEN_FILE" <<'EOF' || true
import sys, urllib.error, urllib.request
base, token_file = sys.argv[1], sys.argv[2]
token = open(token_file).read().strip()
bearer = False
try:
    request = urllib.request.Request(base, headers={"X-SamRabbit-Desktop": token})
    with urllib.request.urlopen(request, timeout=4) as response:
        status = response.status
except urllib.error.HTTPError as error:
    status = error.code
    bearer = (error.headers.get("WWW-Authenticate") or "").startswith("Bearer")
except Exception:  # noqa: BLE001
    status = 0
if status == 401 and bearer:
    status = 404  # a bridge from before the desktop page: its own bearer check answers
if status == 200:
    print(f"the bridge serves the desktop page at {base}")
elif status == 0:
    print(f"the bridge is not running at {base} yet; SamRabbit waits for it (companion/mac-bridge/install.sh)")
elif status == 404:
    print("the bridge is running but does not serve the desktop page yet: run companion/mac-bridge/install.sh")
else:
    print(f"the bridge answered HTTP {status} for the desktop page")
EOF
echo "SamRabbit is installed. Log: ~/Library/Logs/samrabbit-desktop.log"
