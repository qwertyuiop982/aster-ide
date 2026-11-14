CREATE TABLE IF NOT EXISTS users (
 id BIGSERIAL PRIMARY KEY, display_name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active',
 balance_units BIGINT NOT NULL DEFAULT 1000000 CHECK(balance_units>=0),
 reserved_units BIGINT NOT NULL DEFAULT 0 CHECK(reserved_units>=0 AND reserved_units<=balance_units),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS identities (
 provider TEXT NOT NULL, subject TEXT NOT NULL, user_id BIGINT NOT NULL REFERENCES users(id),
 PRIMARY KEY(provider,subject)
);
CREATE TABLE IF NOT EXISTS ledger (
 id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), kind TEXT NOT NULL,
 reference TEXT NOT NULL, delta_units BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,kind,reference)
);
CREATE TABLE IF NOT EXISTS checkins (
 user_id BIGINT NOT NULL REFERENCES users(id), beijing_date DATE NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(user_id,beijing_date)
);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash TEXT PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id),
 expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS challenges (
 id TEXT PRIMARY KEY, email TEXT NOT NULL, code_hash TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 expires_at TIMESTAMPTZ NOT NULL, consumed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS challenges_email_date ON challenges(email,created_at);
CREATE TABLE IF NOT EXISTS auth_limits (
 bucket TEXT PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL, used INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_transactions (
 state_hash TEXT PRIMARY KEY, mobile_challenge TEXT NOT NULL, upstream_verifier TEXT NOT NULL,
 ticket_hash TEXT UNIQUE, user_id BIGINT REFERENCES users(id),
 expires_at TIMESTAMPTZ NOT NULL, consumed BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS usage_requests (
 user_id BIGINT NOT NULL REFERENCES users(id), request_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
 status TEXT NOT NULL, reserved_units BIGINT NOT NULL, input_tokens BIGINT NOT NULL DEFAULT 0,
 output_tokens BIGINT NOT NULL DEFAULT 0, charged_units BIGINT NOT NULL DEFAULT 0,
 prompt_version TEXT NOT NULL, result_json JSONB, billing_fingerprint TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(user_id,request_id)
);
CREATE INDEX IF NOT EXISTS usage_status_date ON usage_requests(status,created_at);

CREATE TABLE IF NOT EXISTS admin_sessions (
 token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, credential_version TEXT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS admin_sessions_expiry ON admin_sessions(expires_at);
CREATE TABLE IF NOT EXISTS admin_adjustments (
 request_id TEXT PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), admin_name TEXT NOT NULL,
 delta_units BIGINT NOT NULL CHECK(delta_units<>0), balance_after BIGINT NOT NULL CHECK(balance_after>=0),
 reserved_after BIGINT NOT NULL CHECK(reserved_after>=0 AND reserved_after<=balance_after),
 reason TEXT NOT NULL CHECK(char_length(reason) BETWEEN 2 AND 200),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS admin_adjustments_date ON admin_adjustments(created_at DESC);
CREATE INDEX IF NOT EXISTS ledger_date ON ledger(created_at);
