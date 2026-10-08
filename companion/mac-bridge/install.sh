#!/usr/bin/env bash
# Install (or refresh) the SamRabbit Mac bridge as a per-user LaunchAgent. Safe to run again:
# the tokens are created only when missing, the scripts and plist are rewritten, the agent reloaded.
# Conversation sync keeps its store in ~/Library/Application Support/SamRabbit/sync (never deleted here).
#
#   companion/mac-bridge/install.sh [--port 3780] [--host 0.0.0.0] [--python /usr/bin/python3]
#
# Environment (tests): SAMRABBIT_HOME (default $HOME), SAMRABBIT_SKIP_LAUNCHCTL=1.
set -euo pipefail

LABEL=com.samrabbit.bridge
PORT=3780
HOST=0.0.0.0
PYTHON=${SAMRABBIT_PYTHON:-/usr/bin/python3}
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT=$2; shift 2 ;;
    --host) HOST=$2; shift 2 ;;
    --python) PYTHON=$2; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "install.sh: unknown option $1" >&2; exit 64 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) echo "install.sh: --port must be a number" >&2; exit 64 ;; esac

SOURCE_DIR=$(cd "$(dirname "$0")" && pwd)
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
CONFIG_DIR="$HOME_DIR/.config/samrabbit"
TOKEN_FILE="$CONFIG_DIR/bridge-token"
DESKTOP_TOKEN_FILE="$CONFIG_DIR/desktop-token"
SYNC_DIR="$HOME_DIR/Library/Application Support/SamRabbit/sync"
APP_DIR="$HOME_DIR/Library/Application Support/SamRabbit/bridge"
LOG_FILE="$HOME_DIR/Library/Logs/samrabbit-bridge.log"
PLIST="$HOME_DIR/Library/LaunchAgents/$LABEL.plist"

[ -x "$PYTHON" ] || { echo "install.sh: $PYTHON not found (pass --python)" >&2; exit 69; }
"$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' \
  || { echo "install.sh: Python 3.9 or newer is required" >&2; exit 69; }
if ! command -v heptabase >/dev/null 2>&1 && [ ! -x /opt/homebrew/bin/heptabase ]; then
  echo "install.sh: warning: the heptabase CLI is not installed yet (Heptabase > Settings > AI Features)." >&2
fi
if ! command -v cua-driver >/dev/null 2>&1 && [ ! -x /Applications/CuaDriver.app/Contents/MacOS/cua-driver ] \
    && ! ls "$HOME"/.hermes/tools/cua-driver-*/CuaDriver.app/Contents/MacOS/cua-driver >/dev/null 2>&1; then
  echo "install.sh: warning: cua-driver was not found; Mac control from the R1 stays off until it is installed." >&2
fi
if ! command -v claude >/dev/null 2>&1 && [ ! -x "$HOME/.local/bin/claude" ]; then
  echo "install.sh: warning: the Claude Code CLI was not found; generated UIs stay off until it is installed." >&2
fi

# 1. Token (0600, never printed).
umask 077
mkdir -p "$CONFIG_DIR"; chmod 700 "$CONFIG_DIR"
if [ ! -s "$TOKEN_FILE" ]; then
  "$PYTHON" -c 'import secrets, sys; sys.stdout.write(secrets.token_urlsafe(32) + "\n")' > "$TOKEN_FILE.tmp"
  mv "$TOKEN_FILE.tmp" "$TOKEN_FILE"
  echo "created a new bridge token in $TOKEN_FILE"
else
  echo "keeping the existing bridge token in $TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"
# The desktop app's own token (loopback-only conversation API), separate from the R1's.
if [ ! -s "$DESKTOP_TOKEN_FILE" ]; then
  "$PYTHON" -c 'import secrets, sys; sys.stdout.write(secrets.token_urlsafe(32) + "\n")' > "$DESKTOP_TOKEN_FILE.tmp"
  mv "$DESKTOP_TOKEN_FILE.tmp" "$DESKTOP_TOKEN_FILE"
  echo "created a new desktop token in $DESKTOP_TOKEN_FILE"
fi
chmod 600 "$DESKTOP_TOKEN_FILE"
mkdir -p "$SYNC_DIR"; chmod 700 "$SYNC_DIR"

# 2. The bridge script, copied so the agent does not depend on this checkout.
umask 022
mkdir -p "$APP_DIR" "$(dirname "$LOG_FILE")" "$(dirname "$PLIST")"
install -m 0644 "$SOURCE_DIR/samrabbit_bridge.py" "$APP_DIR/samrabbit_bridge.py"
install -m 0644 "$SOURCE_DIR/samrabbit_mac.py" "$APP_DIR/samrabbit_mac.py"
install -m 0644 "$SOURCE_DIR/samrabbit_sync.py" "$APP_DIR/samrabbit_sync.py"
install -m 0644 "$SOURCE_DIR/samrabbit_genui.py" "$APP_DIR/samrabbit_genui.py"
rm -rf "$APP_DIR/genui"; mkdir -p "$APP_DIR/genui"
for asset in "$SOURCE_DIR"/genui/*; do install -m 0644 "$asset" "$APP_DIR/genui/"; done
touch "$LOG_FILE"; chmod 600 "$LOG_FILE"
if [ "$(stat -f %z "$LOG_FILE" 2>/dev/null || echo 0)" -gt 5242880 ]; then : > "$LOG_FILE"; fi

# 3. LaunchAgent plist (written with plistlib so every path is escaped correctly).
"$PYTHON" - "$PLIST" "$LABEL" "$PYTHON" "$APP_DIR/samrabbit_bridge.py" "$HOST" "$PORT" "$TOKEN_FILE" "$LOG_FILE" \
    "$APP_DIR" "$SYNC_DIR" "$DESKTOP_TOKEN_FILE" <<'EOF'
import os, plistlib, sys
plist, label, python, script, host, port, token, log, workdir, sync_dir, desktop_token = sys.argv[1:]
value = {
    "Label": label,
    "ProgramArguments": [python, "-I", script, "--host", host, "--port", port, "--token-file", token,
                         "--sync-dir", sync_dir, "--desktop-token-file", desktop_token],
    "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
    "WorkingDirectory": workdir,
    "RunAtLoad": True,
    "KeepAlive": True,
    "ThrottleInterval": 10,
    "StandardOutPath": log,
    "StandardErrorPath": log,
}
tmp = plist + ".tmp"
with open(tmp, "wb") as handle:
    plistlib.dump(value, handle)
os.chmod(tmp, 0o644)
os.replace(tmp, plist)
EOF
echo "wrote $PLIST"

if [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" = 1 ]; then
  echo "skipping launchctl (SAMRABBIT_SKIP_LAUNCHCTL=1)"; exit 0
fi

# 4. (Re)load the agent.
DOMAIN="gui/$(id -u)"
launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null; then break; fi
  [ "$attempt" = 10 ] && { echo "install.sh: launchctl bootstrap failed" >&2; exit 70; }
  sleep 1
done
launchctl enable "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
launchctl kickstart -k "$DOMAIN/$LABEL" >/dev/null

# 5. Wait for the bridge and report (the token is read from the file, never echoed).
"$PYTHON" - "$PORT" "$TOKEN_FILE" <<'EOF'
import json, sys, time, urllib.request
port, token_file = sys.argv[1], sys.argv[2]
token = open(token_file).read().strip()
deadline = time.time() + 20
while True:
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/health",
                                         headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(request, timeout=15) as response:
            health = json.load(response)
        break
    except Exception as error:  # noqa: BLE001
        if time.time() > deadline:
            print(f"install.sh: the bridge did not answer on port {port} ({type(error).__name__}); "
                  "see ~/Library/Logs/samrabbit-bridge.log", file=sys.stderr)
            sys.exit(1)
        time.sleep(0.5)
cli, app = health.get("cli") or {}, health.get("app") or {}
print(f"bridge running: heptabase CLI {cli.get('version') or 'missing'}, "
      f"Heptabase app {'reachable' if app.get('reachable') else 'NOT reachable (' + str(app.get('detail')) + ')'}")
mac = health.get("mac") or {}
driver, permissions = mac.get("driver") or {}, mac.get("permissions") or {}
print(f"mac control: cua-driver {driver.get('version') or 'missing'}, "
      f"accessibility {'on' if permissions.get('accessibility') else 'OFF'}, "
      f"screen vision {'on' if permissions.get('screenRecording') else 'OFF'}")
if mac.get("screenRecordingFix"):
    print("  to turn on screen vision: " + mac["screenRecordingFix"].replace("Run on the Mac: ", ""))
sync = health.get("sync") or {}
print(f"conversation sync: {'on' if sync.get('available') else 'OFF'}"
      f"{'' if sync.get('desktopToken', True) else ' (desktop token missing)'}")
ui = health.get("genui") or {}
print(f"generated UIs: {'on' if ui.get('available') else 'OFF'} (Claude Code {'found' if ui.get('claude') else 'missing'}, "
      f"renderer {'found' if ui.get('renderer') else 'missing'}, model {ui.get('model')})")
EOF
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<this Mac's IP>")
echo "Bridge URL: http://$IP:$PORT"
echo "Token file: $TOKEN_FILE"
