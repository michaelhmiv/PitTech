-- Provider sign-in (issue #123 slice A): proof-bound Apple/Google exchanges.
-- Email challenges cannot be reused: they require email/code/link before any
-- provider identity is verified. Raw provider subjects are never stored; only
-- domain-separated keyed hashes. Additive only: no existing table is altered.
CREATE TABLE auth.provider_challenges (
  id uuid PRIMARY KEY,
  provider text NOT NULL CHECK (provider IN ('apple','google')),
  proof_hash text NOT NULL CHECK (proof_hash ~ '^[a-f0-9]{64}$'),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  consumed_at bigint,
  exchange_id uuid,
  exchange_envelope text,
  exchange_expires_at bigint
);
CREATE INDEX auth_provider_challenges_owner ON auth.provider_challenges(user_id, created_at DESC);
CREATE TABLE auth.provider_identities (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  provider text NOT NULL CHECK (provider IN ('apple','google')),
  subject_key text NOT NULL CHECK (subject_key ~ '^[a-f0-9]{64}$'),
  email text CHECK (email IS NULL OR (length(email) BETWEEN 1 AND 254)),
  created_at bigint NOT NULL,
  PRIMARY KEY (provider, subject_key),
  UNIQUE (user_id, provider)
);
-- Durable receipt for Sign in with Apple server-to-server notifications. The
-- event identifier is the dedupe key; unknown subjects are a recorded no-op.
CREATE TABLE auth.apple_notification_receipts (
  event_id text PRIMARY KEY CHECK (length(event_id) BETWEEN 1 AND 256),
  received_at bigint NOT NULL
);
