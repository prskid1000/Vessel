#!/usr/bin/env bash
# Keepalive ping and wireless ADB auto-discovery.
#
#   ./tools/keepalive-ping.sh [TARGET_IP:PORT] [INTERVAL]
#
# Usage:
#   ./tools/keepalive-ping.sh 192.168.1.13:40845 2
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if command -v python3 >/dev/null 2>&1; then
    exec python3 "$REPO/tools/adb_keepalive.py" "$@"
elif command -v python >/dev/null 2>&1; then
    exec python "$REPO/tools/adb_keepalive.py" "$@"
fi

# Fallback bash loop if python is unavailable
TARGET="${1:-192.168.1.1}"
INTERVAL="${2:-2}"

echo "[keepalive-ping] Starting background ping from device to $TARGET every ${INTERVAL}s..."
while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    RESULT=$(adb shell "ping -c 1 -W 1 $TARGET 2>&1" | tr -d '\r' || true)
    if echo "$RESULT" | grep -q "bytes from"; then
        TIME=$(echo "$RESULT" | grep -o "time=[0-9.]* ms" || echo "ok")
        echo "[$TIMESTAMP] OK -> $TARGET ($TIME)"
    else
        echo "[$TIMESTAMP] FAILED -> $TARGET: $RESULT"
    fi
    sleep "$INTERVAL"
done
