-- MCP connection grants (issue #114, slice M1): personal access tokens for
-- AI assistants. Only domain-separated keyed hashes are stored (the token
-- itself is shown once at creation and never recoverable). Grants live in
-- the auth schema on the account pool, next to sessions: they are account
-- metadata, revoked with the account, and deleted with it (explicit deletes
-- in account-deletion plus the cascade below). Additive only.
CREATE TABLE auth.mcp_grants (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  nickname text NOT NULL CHECK (length(nickname) BETWEEN 1 AND 80),
  token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  scopes text[] NOT NULL CHECK (cardinality(scopes) BETWEEN 1 AND 10),
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL CHECK (expires_at > created_at),
  revoked_at bigint,
  last_used_at bigint
);
CREATE INDEX auth_mcp_grants_owner ON auth.mcp_grants(user_id, created_at DESC);
