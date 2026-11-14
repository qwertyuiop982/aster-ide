# Aster account and model service

This Go service backs the Aster account, wallet, Free and Free Go endpoints. The service reports `v1.06-aster`; the Android APK for that release is unchanged by this server update.

Current rates use integer thousandths of a point: Free costs **1 point per 1,000 tokens**; Free Go costs **5 points per 1,000 tokens**. Input and output tokens both count. Model metadata, wallet metadata, reservation and settlement share the same rate constants. Switching the provider model invalidates an incompatible request replay.

The production operator configures Free Go as `gpt-6.1-terra` and Free as `glm-5.3-flash` through `https://api.uselunora.com/v1`. Provider credentials are supplied through a private environment file; none are part of this source tree.

Build with Go 1.23 or later:

```sh
cd services/account
go build -trimpath -o /tmp/aster-service ./cmd/server
go test ./...
```

Database integration tests require `ASTER_TEST_DATABASE_URL` pointing to the dedicated `aster_service_test` PostgreSQL database. They clear that database and refuse any other database name. Without it, only tests that do not need PostgreSQL run. Live provider tests additionally require `ASTER_LIVE_CHAT_SMOKE=1` and private upstream settings; requests incur provider usage.

Required production settings include `ASTER_DATABASE_URL`, `ASTER_SESSION_SECRET` and `ASTER_PUBLIC_URL`. Free Go uses `ASTER_FREE_UPSTREAM_URL`, `ASTER_FREE_UPSTREAM_MODEL` and `ASTER_FREE_UPSTREAM_KEY`; Free uses `ASTER_FREE_NORMAL_URL`, `ASTER_FREE_NORMAL_MODEL` and `ASTER_FREE_NORMAL_KEY`. `ASTER_GO_COUNTER_MODE=chat_usage` and `ASTER_FREE_NORMAL_COUNTER_MODE=chat_usage` enable a short usage probe before each completion. The probe is service overhead and does not debit the user's wallet. `ASTER_GO_REASONING_EFFORT=low` retains the existing fixed low reasoning policy for Free Go.

The embedded schema initializes the service tables. Deploy the binary behind the existing reverse proxy and retain the private environment file, PostgreSQL data, previous binary and rollback path. The service binds to loopback; API credentials, database records, signing keys and deployment passwords must never enter release assets.

The billing helper retains its upstream license at `internal/sub2billing/LICENSE`. Existing project and third-party notices remain applicable.