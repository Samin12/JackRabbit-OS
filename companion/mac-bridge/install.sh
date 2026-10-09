#!/usr/bin/env bash
# Install (or refresh) the SamRabbit Mac bridge as a per-user LaunchAgent. Safe to run again:
# the tokens are created only when missing, the scripts and plist are rewritten, the agent reloaded.
# Conversation sync keeps its store in ~/Library/Application Support/SamRabbit/sync (never deleted here).
#
#   companion/mac-bridge/install.sh [--port 3780] [--host 0.0.0.0] [--python /usr/bin/python3]
#                                   [--google-account you@example.com] [--t3-orchestration-project <T3 project id>]
#
# --google-account: the Google account that links opened from the phone are pinned to (authuser=); without it the
# bridge learns it from the calendar. --t3-orchestration-project: where the phone's non-coding tasks go (default:
# T3's own agent project). Both are kept in the agent's environment (SAMRABBIT_GOOGLE_ACCOUNT,
# SAMRABBIT_T3_ORCHESTRATION_PROJECT) across later runs; pass an empty value to remove one.
#
# Environment (tests): SAMRABBIT_HOME (default $HOME), SAMRABBIT_SKIP_LAUNCHCTL=1, SAMRABBIT_COMPOSIO (the Composio
# CLI to record instead of searching PATH, ~/.local/bin, /opt/homebrew/bin and /usr/local/bin), SAMRABBIT_T3_CLI /
# SAMRABBIT_T3_URL (a stand-in T3 CLI and server for the one-time T3 pairing; without SAMRABBIT_T3_CLI the pairing
# is skipped whenever SAMRABBIT_SKIP_LAUNCHCTL=1, so a test install never pairs with the real T3 Code),
# SAMRABBIT_SKIP_T3_PAIR=1, SAMRABBIT_SKIP_TRANSCRIBE_BUILD=1 (keep whatever transcription helper is installed),
# SAMRABBIT_SWIFTC (the Swift compiler for that helper; default xcrun's swiftc), SAMRABBIT_TRANSCRIBE_PREPARE=1 (a
# test install downloads a missing speech model too; a real install always does).
#
# The iPhone / Apple Watch API (/v1/mobile/*) is part of the bridge. The bridge pairs with T3 Code by itself (its
# own session, "SamRabbit bridge", token in ~/.config/samrabbit/t3-token); this script does that once. To pair a
# phone: SamRabbit (desktop app) > Pair iPhone..., or companion/mac-bridge/pair-phone.sh.
#
# Speech to text for the watch and the phone (POST /v1/mobile/transcribe) uses a small Swift helper built here from
# transcribe/ with Xcode or the Command Line Tools (macOS's on-device SpeechAnalyzer, macOS 26+). Without it that one
# route answers transcribe_unavailable and everything else works; this script prints "transcription: on|off (...)".
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
    --google-account) SAMRABBIT_GOOGLE_ACCOUNT=$2; export SAMRABBIT_GOOGLE_ACCOUNT; shift 2 ;;
    --t3-orchestration-project) SAMRABBIT_T3_ORCHESTRATION_PROJECT=$2; export SAMRABBIT_T3_ORCHESTRATION_PROJECT; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "install.sh: unknown option $1" >&2; exit 64 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) echo "install.sh: --port must be a number" >&2; exit 64 ;; esac

SOURCE_DIR=$(cd "$(dirname "$0")" && pwd)
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
CONFIG_DIR="$HOME_DIR/.config/samrabbit"
TOKEN_FILE="$CONFIG_DIR/bridge-token"
DESKTOP_TOKEN_FILE="$CONFIG_DIR/desktop-token"
MOBILE_DEVICES_FILE="$CONFIG_DIR/mobile-devices.json"
T3_TOKEN_FILE="$CONFIG_DIR/t3-token"
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
# The Composio CLI (Google Calendar changes from the R1). A LaunchAgent has a minimal PATH, so its absolute
# path is recorded in the agent's environment (SAMRABBIT_COMPOSIO); the bridge still searches if it moves.
COMPOSIO=${SAMRABBIT_COMPOSIO:-}
if [ -z "$COMPOSIO" ]; then
  COMPOSIO=$(command -v composio 2>/dev/null || true)
  case "$COMPOSIO" in /*) ;; *) COMPOSIO="" ;; esac
  for candidate in "$HOME/.local/bin/composio" /opt/homebrew/bin/composio /usr/local/bin/composio; do
    [ -n "$COMPOSIO" ] && break
    [ -x "$candidate" ] && COMPOSIO=$candidate
  done
fi
case "$COMPOSIO" in /*) [ -x "$COMPOSIO" ] || COMPOSIO="" ;; *) COMPOSIO="" ;; esac  # absolute and runnable only
if [ -z "$COMPOSIO" ]; then
  echo "install.sh: warning: the Composio CLI was not found; Google Calendar changes from the R1 stay off until it" \
    "is installed and signed in (then run install.sh again)." >&2
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
install -m 0644 "$SOURCE_DIR/samrabbit_calendar.py" "$APP_DIR/samrabbit_calendar.py"
install -m 0644 "$SOURCE_DIR/samrabbit_mobile.py" "$APP_DIR/samrabbit_mobile.py"  # iPhone / Apple Watch API
install -m 0644 "$SOURCE_DIR/samrabbit_t3.py" "$APP_DIR/samrabbit_t3.py"          # its T3 Code client
install -m 0644 "$SOURCE_DIR/samrabbit_transcribe.py" "$APP_DIR/samrabbit_transcribe.py"  # speech to text
install -m 0644 "$SOURCE_DIR/samrabbit_installed.py" "$APP_DIR/samrabbit_installed.py"  # "is this the installed copy?"
rm -rf "$APP_DIR/genui"; mkdir -p "$APP_DIR/genui"
for asset in "$SOURCE_DIR"/genui/*; do install -m 0644 "$asset" "$APP_DIR/genui/"; done
install -m 0644 "$SOURCE_DIR/samrabbit_app.py" "$APP_DIR/samrabbit_app.py"  # desktop web UI at /app/
# The install marker: the installed copy is the one in the account's own home (from the password database, never
# $HOME) whose .samrabbit-installed names its folder. Only that copy reaches the real Heptabase journal, T3 Code and
# Google Calendar by default; a checkout, a test or a copy in a temp HOME (SAMRABBIT_HOME) is always a dev copy.
printf '%s\n' "$APP_DIR" > "$APP_DIR/.samrabbit-installed.tmp"
chmod 0644 "$APP_DIR/.samrabbit-installed.tmp"
mv -f "$APP_DIR/.samrabbit-installed.tmp" "$APP_DIR/.samrabbit-installed"
echo "bridge copy: $("$PYTHON" -I "$APP_DIR/samrabbit_installed.py" "$APP_DIR" 2>/dev/null || echo "dev (unknown)")"
touch "$LOG_FILE"; chmod 600 "$LOG_FILE"
if [ "$(stat -f %z "$LOG_FILE" 2>/dev/null || echo 0)" -gt 5242880 ]; then : > "$LOG_FILE"; fi

# 2b. The speech-to-text helper (samrabbit-transcribe next to the bridge), rebuilt only when its source or the
#     compiler changed. A failed build keeps the helper that is already there. Never fails the install.
TRANSCRIBE_SRC="$SOURCE_DIR/transcribe"
TRANSCRIBE_BIN="$APP_DIR/samrabbit-transcribe"
TRANSCRIBE_OFF=""
if [ "${SAMRABBIT_SKIP_TRANSCRIBE_BUILD:-0}" = 1 ]; then
  [ -x "$TRANSCRIBE_BIN" ] || TRANSCRIBE_OFF="build skipped: SAMRABBIT_SKIP_TRANSCRIBE_BUILD=1"
else
  SWIFTC=()
  if [ -n "${SAMRABBIT_SWIFTC:-}" ]; then
    SWIFTC=("$SAMRABBIT_SWIFTC")
  elif xcode-select -p >/dev/null 2>&1 && xcrun --sdk macosx --find swiftc >/dev/null 2>&1; then
    SWIFTC=(xcrun --sdk macosx swiftc)  # xcode-select first: a bare xcrun would offer to install the tools
  fi
  if [ ${#SWIFTC[@]} -eq 0 ]; then
    [ -x "$TRANSCRIBE_BIN" ] || TRANSCRIBE_OFF="no Swift compiler: install Xcode or the Command Line Tools, then run install.sh again"
  else
    STAMP=$({ cat "$TRANSCRIBE_SRC/samrabbit_transcribe.swift" "$TRANSCRIBE_SRC/Info.plist"; "${SWIFTC[@]}" --version 2>&1 || true; } \
      | shasum -a 256 | cut -c1-64)
    if [ -x "$TRANSCRIBE_BIN" ] && [ "$(cat "$TRANSCRIBE_BIN.build" 2>/dev/null || true)" = "$STAMP" ]; then
      echo "transcription helper is up to date"
    else
      BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/samrabbit-transcribe.XXXXXX")
      if "${SWIFTC[@]}" -O -parse-as-library "$TRANSCRIBE_SRC/samrabbit_transcribe.swift" -o "$BUILD_DIR/samrabbit-transcribe" \
          -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$TRANSCRIBE_SRC/Info.plist" \
          >"$BUILD_DIR/build.log" 2>&1 && [ -s "$BUILD_DIR/samrabbit-transcribe" ]; then
        /usr/bin/codesign -s - -f -i com.samrabbit.transcribe "$BUILD_DIR/samrabbit-transcribe" >/dev/null 2>&1 \
          || echo "install.sh: warning: could not sign the transcription helper" >&2
        chmod 0755 "$BUILD_DIR/samrabbit-transcribe"
        mv -f "$BUILD_DIR/samrabbit-transcribe" "$TRANSCRIBE_BIN.tmp"
        mv -f "$TRANSCRIBE_BIN.tmp" "$TRANSCRIBE_BIN"  # a new file, never rewritten in place (code signing)
        echo "$STAMP" > "$TRANSCRIBE_BIN.build"
        echo "built the transcription helper"
      else
        echo "install.sh: warning: the transcription helper did not build (it needs the macOS 26 SDK or newer):" >&2
        tail -n 5 "$BUILD_DIR/build.log" | sed 's/^/install.sh:   /' >&2
        [ -x "$TRANSCRIBE_BIN" ] || TRANSCRIBE_OFF="the helper did not build; it needs the macOS 26 SDK or newer"
      fi
      rm -rf "$BUILD_DIR"
    fi
  fi
fi
# Is it usable? A real install downloads a missing language model (en-US) once; then one clip that `say -o` writes
# to a file (nothing is played) is transcribed as a check. Prints "transcription: on (...)" or "off (reason)".
if [ -n "$TRANSCRIBE_OFF" ]; then
  echo "transcription: off ($TRANSCRIBE_OFF)"
else
  PREPARE=1
  [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" = 1 ] && PREPARE=${SAMRABBIT_TRANSCRIBE_PREPARE:-0}
  "$PYTHON" -I - "$TRANSCRIBE_BIN" "$PREPARE" <<'EOF' || echo "transcription: off (the check failed)"
import json, os, signal, subprocess, sys, tempfile, time
helper, prepare = sys.argv[1], sys.argv[2] == "1"
def run(*args, timeout):
    # Its own deadline a little under ours; at ours, the helper's whole process group is killed.
    try:
        process = subprocess.Popen([helper, *args, "--deadline", f"{max(1, timeout - 5)}"], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        return {"ok": False, "code": "not_runnable"}
    try:
        out, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            process.kill()
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        return {"ok": False, "code": "timeout"}
    lines = [line for line in out.decode("utf-8", "replace").splitlines() if line.strip()]
    try:
        value = json.loads(lines[-1]) if lines else {}
    except ValueError:
        value = {}
    return value if isinstance(value, dict) else {}
check = run("--check", timeout=60)
if check.get("reason") == "model_missing" and prepare:
    print("downloading the speech model for en-US (once; it can take a few minutes)", flush=True)
    if not run("--prepare", timeout=900).get("ok"):
        print("install.sh: warning: the speech model did not download; the bridge tries again on the first recording",
              file=sys.stderr)
    check = run("--check", timeout=60)
if not check.get("available"):
    print(f"transcription: off ({check.get('reason') or check.get('code') or 'the helper did not answer'})")
    sys.exit(0)
detail = f"{check.get('engine')}, {check.get('locale')}"
with tempfile.TemporaryDirectory() as folder:
    clip = os.path.join(folder, "check.wav")
    try:
        subprocess.run(["/usr/bin/say", "-o", clip, "--data-format=LEI16@16000", "SamRabbit can hear you."],
                       stdin=subprocess.DEVNULL, capture_output=True, timeout=60, check=True)
    except (OSError, subprocess.SubprocessError):
        clip = ""
    if clip:
        started = time.monotonic()
        heard = run("--file", clip, "--locale", "en-US", timeout=45)
        if not (heard.get("ok") and heard.get("text")):
            print(f"transcription: off (a test clip failed: {heard.get('code') or 'no answer'})")
            sys.exit(0)
        detail += f", a test clip took {time.monotonic() - started:.1f} s"
print(f"transcription: on ({detail})")
EOF
fi

# 3. LaunchAgent plist (written with plistlib so every path is escaped correctly).
"$PYTHON" - "$PLIST" "$LABEL" "$PYTHON" "$APP_DIR/samrabbit_bridge.py" "$HOST" "$PORT" "$TOKEN_FILE" "$LOG_FILE" \
    "$APP_DIR" "$SYNC_DIR" "$DESKTOP_TOKEN_FILE" "$COMPOSIO" "$MOBILE_DEVICES_FILE" "$T3_TOKEN_FILE" <<'EOF'
import os, plistlib, re, sys
(plist, label, python, script, host, port, token, log, workdir, sync_dir, desktop_token, composio, mobile_devices,
 t3_token) = sys.argv[1:]
environment = {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"}
if composio:
    environment["SAMRABBIT_COMPOSIO"] = composio
# Settings given once (flag or environment) stay in the agent's environment on later runs; an empty value removes one.
try:
    with open(plist, "rb") as handle:
        previous = plistlib.load(handle).get("EnvironmentVariables") or {}
except Exception:  # noqa: BLE001 - no agent yet, or an unreadable one
    previous = {}
checks = {"SAMRABBIT_GOOGLE_ACCOUNT": re.compile(r"^[^@\s]{1,128}@[^@\s]{1,128}\.[A-Za-z]{2,}$"),
          "SAMRABBIT_T3_ORCHESTRATION_PROJECT": re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:\-]{0,127}$")}
for key, pattern in checks.items():
    value = os.environ[key].strip() if key in os.environ else str(previous.get(key) or "")
    if value and not pattern.match(value):
        print(f"install.sh: warning: {key} is not valid; it is not recorded", file=sys.stderr)
    elif value:
        environment[key] = value
value = {
    "Label": label,
    # --cli auto: the installed bridge is the one that writes to the real Heptabase journal (any other copy
    # of the bridge, e.g. a test or dev run from a checkout, defaults to a dry-run CLI).
    "ProgramArguments": [python, "-I", script, "--host", host, "--port", port, "--token-file", token,
                         "--sync-dir", sync_dir, "--desktop-token-file", desktop_token, "--cli", "auto",
                         "--mobile-devices-file", mobile_devices, "--t3-token-file", t3_token],
    "EnvironmentVariables": environment,
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

# 4. T3 Code: the bridge's own session for the phone's Tasks tab, paired once now (the bridge pairs again by
#    itself when the token expires or is revoked). Kept when it is still good; the token is never printed.
if [ "${SAMRABBIT_SKIP_T3_PAIR:-0}" != 1 ] && { [ -n "${SAMRABBIT_T3_CLI:-}" ] || [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" != 1 ]; }; then
  T3_ARGS=(--token-file "$T3_TOKEN_FILE")
  [ -n "${SAMRABBIT_T3_URL:-}" ] && T3_ARGS+=(--url "$SAMRABBIT_T3_URL")
  [ -n "${SAMRABBIT_T3_CLI:-}" ] && T3_ARGS+=(--cli "$SAMRABBIT_T3_CLI")
  if T3_LINE=$("$PYTHON" -I "$APP_DIR/samrabbit_t3.py" ensure-paired "${T3_ARGS[@]}" 2>&1); then
    echo "$T3_LINE"
  else
    echo "install.sh: warning: $T3_LINE; the bridge pairs with T3 Code by itself once T3 Code is running." >&2
  fi
else
  echo "skipping the T3 pairing (test install)"
fi

if [ "${SAMRABBIT_SKIP_LAUNCHCTL:-0}" = 1 ]; then
  echo "skipping launchctl (SAMRABBIT_SKIP_LAUNCHCTL=1)"; exit 0
fi

# 5. (Re)load the agent.
DOMAIN="gui/$(id -u)"
launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null; then break; fi
  [ "$attempt" = 10 ] && { echo "install.sh: launchctl bootstrap failed" >&2; exit 70; }
  sleep 1
done
launchctl enable "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
launchctl kickstart -k "$DOMAIN/$LABEL" >/dev/null

# 6. Wait for the bridge and report (the token is read from the file, never echoed).
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
if health.get("copy") != "installed":
    print("install.sh: warning: the running bridge says it is a dev copy, so the phone's T3 and Google Calendar stay "
          "off; run install.sh as the Mac's own user, with HOME unchanged", file=sys.stderr)
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
cal = health.get("calendarWrite") or {}
print(f"calendar changes: {'on' if cal.get('available') else 'OFF'} (Composio CLI "
      f"{cal.get('path') or 'missing'}, Google calendar {cal.get('calendarId') or 'primary'}"
      f"{', account ' + cal['account'] if cal.get('account') else ''}"
      f"{', problem ' + cal['lastError'] if cal.get('lastError') and not cal.get('available') else ''})")
phone = health.get("mobile") or {}
t3_state = phone.get("t3") or {}
devices = int(phone.get("devices") or 0)
voice = phone.get("transcribe") or {}
print(f"mobile: {'on' if phone.get('available') else 'OFF'} (T3 {'paired' if t3_state.get('paired') else 'not paired'}"
      f"{'' if t3_state.get('ok', True) or not t3_state.get('paired') else ', T3 not answering'}, "
      f"{devices} device{'' if devices == 1 else 's'}, "
      f"transcription {'on' if voice.get('available') else 'off: ' + str(voice.get('reason'))})")
EOF
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<this Mac's IP>")
echo "Bridge URL: http://$IP:$PORT"
echo "Token file: $TOKEN_FILE"
echo "Pair an iPhone: SamRabbit (desktop app) > Pair iPhone..., or $SOURCE_DIR/pair-phone.sh"
