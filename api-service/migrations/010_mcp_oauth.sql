-- MCP OAuth (issue #114, slice M1b): grants gain their origin (which
-- registered client created them) and a family id grouping token
-- rotations; authorization codes and pending approvals live in their own
-- tables. Only keyed hashes of codes and user codes are stored, exactly
-- like grant tokens: the secrets themselves are shown once (browser/app)
-- and never recoverable. Retired token hashes are remembered so a
-- replayed refresh token can be detected and its whole family revoked.
-- Additive only.
ALTER TABLE auth.mcp_grants ADD COLUMN client_id text;
ALTER TABLE auth.mcp_grants ADD COLUMN family_id uuid;
CREATE INDEX auth_mcp_grants_family ON auth.mcp_grants(family_id) WHERE family_id IS NOT NULL;

CREATE TABLE auth.mcp_oauth_codes (
  code_hash text PRIMARY KEY CHECK (code_hash ~ '^[a-f0-9]{64}$'),
  grant_id uuid NOT NULL REFERENCES auth.mcp_grants(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  client_id text NOT NULL,
  redirect_uri text NOT NULL,
  code_challenge text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL CHECK (expires_at > created_at),
  used_at bigint
);

CREATE TABLE auth.mcp_oauth_pending (
  user_code_hash text PRIMARY KEY CHECK (user_code_hash ~ '^[a-f0-9]{64}$'),
  poll_hash text NOT NULL UNIQUE CHECK (poll_hash ~ '^[a-f0-9]{64}$'),
  client_id text NOT NULL,
  redirect_uri text NOT NULL,
  oauth_state text,
  code_challenge text NOT NULL,
  scopes text[] NOT NULL CHECK (cardinality(scopes) BETWEEN 1 AND 10),
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL CHECK (expires_at > created_at),
  approved_user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  approved_code_hash text REFERENCES auth.mcp_oauth_codes(code_hash) ON DELETE SET NULL
);

CREATE TABLE auth.mcp_oauth_retired_hashes (
  token_hash text PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  family_id uuid NOT NULL,
  retired_at bigint NOT NULL
);
