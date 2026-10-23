-- MCP device execution (issue #114, M6 / §7): registered devices that
-- opt into named classes of remote app actions, and the durable
-- command ledger they claim from. Commands are never smuggled through
-- the synced library — this is a separate account-bound path with
-- claim/apply receipts, and a command counts as applied only when the
-- device itself posts that receipt.
CREATE TABLE auth.mcp_devices (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id uuid NOT NULL,
  name text NOT NULL,
  platform text NOT NULL,
  action_classes jsonb NOT NULL DEFAULT '[]'::jsonb,
  last_seen_at bigint,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, device_id)
);
CREATE TABLE auth.mcp_device_commands (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id uuid,
  grant_id text NOT NULL,
  action text NOT NULL,
  params jsonb NOT NULL DEFAULT '{}'::jsonb,
  state text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  claimed_at bigint,
  claimed_by uuid,
  resolved_at bigint,
  receipt jsonb
);
CREATE INDEX mcp_device_commands_owner_idx ON auth.mcp_device_commands (user_id, created_at DESC);
CREATE INDEX mcp_device_commands_claim_idx ON auth.mcp_device_commands (user_id, device_id, state, expires_at);
