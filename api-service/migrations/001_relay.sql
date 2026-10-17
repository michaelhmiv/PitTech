CREATE SCHEMA IF NOT EXISTS relay;
CREATE TABLE relay.packs (
  code text PRIMARY KEY,
  object_key text NOT NULL UNIQUE,
  owner_hash text NOT NULL CHECK (owner_hash ~ '^[a-f0-9]{64}$'),
  idempotency_key uuid NOT NULL,
  sha256 text NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
  bytes bigint NOT NULL CHECK (bytes > 0 AND bytes <= 67108864),
  kind text NOT NULL CHECK (kind IN ('recipe','collection','cook')),
  dishes integer NOT NULL CHECK (dishes BETWEEN 1 AND 20),
  state text NOT NULL CHECK (state IN ('uploading','active','revoked','deleting')),
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  lease_until bigint NOT NULL DEFAULT 0,
  lease_token uuid,
  UNIQUE (owner_hash, idempotency_key)
);
CREATE INDEX packs_sweep ON relay.packs (state, expires_at, lease_until);
CREATE TABLE relay.rate_buckets (key text PRIMARY KEY, count integer NOT NULL, expires_at bigint NOT NULL);
CREATE TABLE relay.daily_budgets (day text PRIMARY KEY, uploads bigint NOT NULL, bytes bigint NOT NULL);
