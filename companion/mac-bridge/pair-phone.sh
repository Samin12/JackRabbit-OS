#!/usr/bin/env bash
# Print a one-time code (and the samrabbit://pair link) to pair the SamRabbit iPhone app with this Mac's bridge.
# The code is single use and valid for 10 minutes; it is shown on this terminal only, never logged.
#
#   companion/mac-bridge/pair-phone.sh [--port 3780]
#
# Same as SamRabbit (desktop app) > Pair iPhone..., which also shows the QR code and the paired devices.
set -euo pipefail

PORT=3780
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT=$2; shift 2 ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "pair-phone.sh: unknown option $1" >&2; exit 64 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) echo "pair-phone.sh: --port must be a number" >&2; exit 64 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
HOME_DIR=${SAMRABBIT_HOME:-$HOME}
PYTHON=${SAMRABBIT_PYTHON:-/usr/bin/python3}
[ -x "$PYTHON" ] || PYTHON=python3
exec "$PYTHON" -I "$HERE/samrabbit_mobile.py" pair-code --port "$PORT" \
  --desktop-token-file "${SAMRABBIT_DESKTOP_TOKEN_FILE:-$HOME_DIR/.config/samrabbit/desktop-token}"
