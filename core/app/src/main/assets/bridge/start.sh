#!/usr/bin/env bash
# Run only after bootstrap verification and executable repair.
# Give background commands their own process groups so stopping the shell also stops apt/dpkg.
set -u -m
export DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1
base="$1"
port="$2"
phase() { printf 'ASTER_STAGE:%s\n' "$1"; }
python_ready() { run_step python -c 'import json, socketserver, ssl, pty' >/dev/null 2>&1; }
active=''
cleanup() {
    if [ -n "$active" ]; then
        kill -TERM -- "-$active" 2>/dev/null || kill -TERM "$active" 2>/dev/null || true
        wait "$active" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT HUP
run_step() {
    local status
    "$@" </dev/null &
    active=$!
    wait "$active"; status=$?; active=''
    return "$status"
}

phase checking
if ! python_ready; then
    for attempt in 1 2; do
        phase updating
        if run_step apt-get -o APT::Update::Error-Mode=any update; then
            phase installing
            if run_step apt-get -y install python && python_ready; then
                break
            fi
        fi
        if [ "$attempt" = 1 ]; then
            phase retrying
            run_step dpkg --configure -a || true
            sleep 2
        else
            printf 'Python bridge installation failed after two attempts. Check the installation log and retry.\n' >&2
            exit 61
        fi
    done
fi
phase starting
exec python -u "$base/bridge.py" --port "$port" --token-file "$base/token" --catalog "$base/servers.json"
