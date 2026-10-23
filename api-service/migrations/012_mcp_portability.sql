-- MCP portability artifacts (issue #114, M5): owned, expiring export
-- results (archives and rendered share cards) and import previews.
-- A preview binds the exact projected rows, the snapshot version they
-- were projected against and the archive content hash; apply requires
-- all three to still match, so a stale preview can never write.
CREATE TABLE auth.mcp_exports (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  kind text NOT NULL,
  scope jsonb NOT NULL,
  payload jsonb NOT NULL,
  counts jsonb NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL
);
CREATE INDEX mcp_exports_owner_idx ON auth.mcp_exports (user_id, created_at DESC);
CREATE TABLE auth.mcp_import_previews (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  grant_id text NOT NULL,
  content_hash text NOT NULL,
  base_version bigint NOT NULL,
  rows jsonb NOT NULL,
  summary jsonb NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  consumed_at bigint
);
CREATE INDEX mcp_import_previews_owner_idx ON auth.mcp_import_previews (user_id, created_at DESC);
