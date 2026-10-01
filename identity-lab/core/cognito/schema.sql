-- Local SAML integration - Cognito simulated: the simulated user pool's own store.
CREATE TABLE IF NOT EXISTS user_profile (
    username         TEXT PRIMARY KEY,          -- <provider>_<NameID>, as Cognito
    sub              UUID NOT NULL UNIQUE,      -- generated once per profile
    provider         TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,             -- the NameID
    identities       JSONB NOT NULL,
    attributes       JSONB NOT NULL,            -- overwritten on every sign-in
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS group_seed (login TEXT NOT NULL, group_name TEXT NOT NULL,
    PRIMARY KEY (login, group_name));
CREATE TABLE IF NOT EXISTS user_group (username TEXT NOT NULL REFERENCES user_profile(username),
    group_name TEXT NOT NULL, PRIMARY KEY (username, group_name));
CREATE TABLE IF NOT EXISTS pending_auth (relay_state TEXT PRIMARY KEY, saml_request_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL, state TEXT NOT NULL, code_challenge TEXT NOT NULL, scope TEXT NOT NULL,
    nameid_mode TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS auth_code (code_hash TEXT PRIMARY KEY, username TEXT NOT NULL,
    redirect_uri TEXT NOT NULL, code_challenge TEXT NOT NULL, auth_time BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE IF NOT EXISTS refresh_token (token_hash TEXT PRIMARY KEY, username TEXT NOT NULL,
    auth_time BIGINT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ);
CREATE TABLE IF NOT EXISTS saml_session (session_id TEXT PRIMARY KEY, username TEXT NOT NULL,
    name_id TEXT NOT NULL, name_id_format TEXT, name_qualifier TEXT, sp_name_qualifier TEXT,
    session_index TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ);
CREATE TABLE IF NOT EXISTS pending_logout (relay_state TEXT PRIMARY KEY, saml_request_id TEXT NOT NULL,
    logout_uri TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS pool_event (event_id BIGSERIAL PRIMARY KEY, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind TEXT NOT NULL, username TEXT, detail JSONB NOT NULL);
