# Aster Terminal MCP

Streamable HTTP endpoint: `https://asteride.cn/terminal-mcp`. The running service uses a private Bearer token, the `aster-terminal` system account, and a loopback listener on port 8893. User-visible configuration is delivered separately as `Aster-terminal-MCP-config.json`.

The seven MCP tools wrap the same `aster_terminal.py` PTY implementation bundled in the app. Keep a stable conversation ID and retain the returned terminal IDs. `terminal_exec` submits once; `terminal_read` waits without killing or replaying the command; `terminal_write` sends text, a key, or a key with `modifiers`. The tool instructions describe the remote execution environment.

For a local development server, install the pinned requirements in a virtual environment and set `ASTER_TERMINAL_TOKEN` to a private value of at least 32 characters, `ASTER_TERMINAL_ROOT` to a private writable directory and `ASTER_TERMINAL_PORT` to the port to use. Run `server.py` with that virtual environment's Python. Its fallback module path uses the project sources at `core/app/src/main/assets/bridge/agent_vendor`; a deployed release instead stores `aster_terminal.py`, `aster_diagnostics.py` and `aster_tools.py` in `lib/`.

`provision.py` runs on the authorized deployment host with a private JSON payload file containing `archive` (base64 tar.gz), `archive_sha256`, and `token`. The archive contains `server.py`, `requirements.txt`, `aster-terminal-mcp.service`, `nginx-location.conf`, and those three `lib/` modules. The provisioner creates a versioned release, verifies its digest, installs the pinned SDK, changes the `current` symlink, restarts the service and validates nginx. A deployment receipt is saved at `/var/lib/aster-terminal-mcp-deployment.json`; it contains no token. The private payload and environment file must remain outside source archives.

The deployed systemd unit and nginx location are included here. nginx routes only `/terminal-mcp` to the service. Service restarts close live PTYs; retained history remains available without replaying inputs.

Aster reads a project's `.aster/mcp.json` and enables the server names selected in model settings. Set `conversationScoped: true` on this server entry so the client injects its workspace/engine/conversation scope. Aster subagents share their parent's scope.

Validation:

```sh
/path/to/venv/bin/python -B -m unittest discover -s scripts -p 'test_terminal_mcp.py' -v
```

The integration tests cover authentication, protocol negotiation, reconnection, terminal history, independent shells, text/key/chord input, resizing, conversation injection and restart behavior. Public endpoint verification additionally runs a real 125-second command and checks that the website continues to respond.