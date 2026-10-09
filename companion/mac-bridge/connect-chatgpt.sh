#!/usr/bin/env bash
# Connect this Mac's SamRabbit bridge to your ChatGPT subscription, for the watch assistant's realtime voice
# (gpt-realtime, like the R1). Prints a code and a link: open the link, sign in to ChatGPT and enter the code; this
# waits until you approved it (up to 15 minutes). The Mac gets its own login (never the R1's, never ~/.codex); its
# tokens stay in ~/.config/samrabbit/chatgpt-auth.json (0600) and are never printed.
#
#   companion/mac-bridge/connect-chatgpt.sh [--open] [--port 3780]
#   companion/mac-bridge/connect-chatgpt.sh status | disconnect
#
# Same as SamRabbit (desktop app) > Connect ChatGPT…
set -euo pipefail

PORT=3780
COMMAND=connect
OPEN=()
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT=$2; shift 2 ;;
    --open) OPEN=(--open); shift ;;
    connect|status|disconnect) COMMAND=$1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "connect-chatgpt.sh: unknown option $1" >&2; exit 64 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) echo "connect-chatgpt.sh: --port must be a number" >&2; exit 64 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
PYTHON=${SAMRABBIT_PYTHON:-/usr/bin/python3}
[ -x "$PYTHON" ] || PYTHON=python3
exec "$PYTHON" -I "$HERE/samrabbit_chatgpt.py" "$COMMAND" --port "$PORT" ${OPEN[@]+"${OPEN[@]}"} \
  --desktop-token-file "${SAMRABBIT_DESKTOP_TOKEN_FILE:-$HOME_DIR/.config/samrabbit/desktop-token}"
