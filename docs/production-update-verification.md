# Production update verification record

## Scope

This record covers the existing production host and the website privacy update. It does not certify a new Android release or a backend deployment. No credentials are included.

## Observed state

At the November 7, 2026 verification, `nginx`, `aster-service` and `aster-terminal-mcp` were active. `nginx -t` passed. HTTPS requests returned:

| Endpoint | Result |
| --- | --- |
| asteride.cn/ | 200 |
| asteride.top/ | 301 to https://asteride.cn/ |
| astercode.top/ | 301 to https://asteride.cn/ |
| asteride.cn/terminal-mcp without authorization | 401 |

The active website resolves to `/var/www/aster/releases/site-v5-privacy-20261106T171045Z`. This release includes the remote-terminal privacy text. The deployment preserved the previous website for rollback and did not restart either backend.

## MCP comparison

The deployed helper hashes were:

- `lib/aster_diagnostics.py`: `0f28359dcb36d405a8cac51b39c8aeb72e551dc783f3787846a600865cbd0e9a`
- `lib/aster_tools.py`: `8b84f895456d3c368ece63ccd803a458187b3e132026c22bf5d5a686ba043aaf`

Differences from Android bridge helpers do not alone justify deployment: the remote service uses only a subset of bridge capabilities. Review the import graph and actual calls before replacing a shared helper. The service reported zero restarts since its recorded activation. Restarting a PTY service can terminate running commands even if transcript history survives.

## Limits

The HTTP checks establish routing and authentication rejection only. They do not establish successful authenticated MCP tool execution, account login, database correctness, certificate renewal or long-running terminal behavior. Those checks require separate recorded evidence. Backend source archives are retained under `/opt/aster-service/releases/`; a change needs a reproducible build and a rollback plan before activation.