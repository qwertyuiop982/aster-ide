#!/usr/bin/env bash
# Shared Termux environment only; can inspect/install without a bridge RPC or a model.
# Give background commands their own process groups so stopping the shell also stops apt/dpkg.
set -u -m
unset NODE_OPTIONS
mode="${1:---probe}"
if [ "$mode" != '--probe' ] && [ "$mode" != '--install' ]; then exit 64; fi
export DEBIAN_FRONTEND=noninteractive
active=''
version_file=''
cleanup() {
    if [ -n "$active" ]; then
        kill -TERM -- "-$active" 2>/dev/null || kill -TERM "$active" 2>/dev/null || true
        wait "$active" 2>/dev/null || true
    fi
    if [ -n "$version_file" ]; then rm -f -- "$version_file"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
run_step() {
    local result
    "$@" </dev/null &
    active=$!
    wait "$active"; result=$?; active=''
    return "$result"
}
phase() { printf 'ASTER_AGENT_STAGE:%s\n' "$1"; }
field() { printf 'ASTER_AGENT_FIELD:%s=%s\n' "$1" "$2"; }
probe() {
    local valid=1 node_path node_version major minor patch git_path rg_path ssl_path certificates
    node_path="$(command -v node || true)"
    node_version=''
    if [ -n "$node_path" ] && run_step "$node_path" --version >"$version_file" 2>/dev/null; then
        node_version=$(<"$version_file")
    fi
    field nodePath "$node_path"; field nodeVersion "$node_version"
    if [[ "$node_version" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)([-+][A-Za-z0-9.-]+)?$ ]]; then
        major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))
        if (( major > 22 || (major == 22 && minor >= 19) )); then field nodeReady true; else field nodeReady false; valid=0; fi
    else field nodeReady false; valid=0; fi
    git_path="$(command -v git || true)"; rg_path="$(command -v rg || true)"; ssl_path="$(command -v openssl || true)"
    field gitPath "$git_path"; field rgPath "$rg_path"; field opensslPath "$ssl_path"
    if [ -z "$git_path" ] || ! run_step "$git_path" --version >/dev/null 2>&1; then valid=0; field gitReady false; else field gitReady true; fi
    if [ -z "$rg_path" ] || ! run_step "$rg_path" --version >/dev/null 2>&1; then valid=0; field rgReady false; else field rgReady true; fi
    if [ -z "$ssl_path" ] || ! run_step "$ssl_path" version >/dev/null 2>&1; then valid=0; field opensslReady false; else field opensslReady true; fi
    certificates="${PREFIX:-}/etc/tls/cert.pem"; field caPath "$certificates"
    if [ ! -s "$certificates" ]; then valid=0; field caReady false; else field caReady true; fi
    if [ "$valid" = 1 ]; then field ready true; return 0; else field ready false; return 1; fi
}
version_file="$(mktemp)" || exit 61
phase checking
if probe; then
    printf '复用已验证的 Node.js、Git、ripgrep、OpenSSL 与 CA 证书。\n'
    exit 0
fi
if [ "$mode" = '--probe' ]; then exit 1; fi
if [ -z "${PREFIX:-}" ] || ! command -v apt-get >/dev/null; then
    printf 'Agent 环境安装失败：内置 Termux PREFIX 或 apt-get 不可用。\n' >&2
    exit 61
fi
for attempt in 1 2; do
    phase updating
    printf '自动安装 Agent 必需工具（第 %s 次）：先更新基础运行库，再安装 libc++ nodejs-lts git ripgrep openssl openssl-tool ca-certificates\n' "$attempt"
    if run_step apt-get -o APT::Update::Error-Mode=any update; then
        phase upgrading
        # Bootstrap snapshots can contain an older libc++ than the current Node package.
        if run_step apt-get -y -o Dpkg::Options::=--force-confold upgrade; then
            phase installing
            if run_step apt-get -y --reinstall install libc++ nodejs-lts git ripgrep openssl openssl-tool ca-certificates; then
                phase verifying
                if probe; then
                    printf 'Agent 环境安装后实际运行检查通过；Node.js >=22.19.0，工具与 CA 就绪。\n'
                    exit 0
                fi
                printf '安装命令已结束，但真实程序或证书检查未通过。\n' >&2
            fi
        fi
    fi
    if [ "$attempt" = 1 ]; then
        phase repairing
        run_step dpkg --configure -a || true
    fi
done
printf 'Agent 环境未安装完整，不能标记为就绪。请复制运行详情中的环境日志后重试。\n' >&2
exit 62
