#!/usr/bin/env bash
# Run only after bootstrap verification, executable repair and mirror configuration.
set -u
export DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1
base="$1"
port="$2"
phase() { printf 'ASTER_STAGE:%s\n' "$1"; }
python_ready() { python -c 'import json, socketserver, ssl, pty' >/dev/null 2>&1; }

phase checking
if ! python_ready; then
    for attempt in 1 2; do
        phase updating
        if timeout -k 5s 60s apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15 -o APT::Update::Error-Mode=any update; then
            phase installing
            if timeout -k 5s 120s apt-get -y -o DPkg::Lock::Timeout=15 install python && python_ready; then
                break
            fi
        fi
        if [ "$attempt" = 1 ]; then
            phase retrying
            timeout -k 5s 30s dpkg --configure -a || true
            sleep 2
        else
            printf 'Python bridge installation failed after two attempts. Check the installation log and retry.\n' >&2
            exit 61
        fi
    done
fi
phase starting
exec python -u "$base/bridge.py" --port "$port" --token-file "$base/token" --catalog "$base/servers.json"
