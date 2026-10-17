CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text NOT NULL UNIQUE,
  created_at bigint NOT NULL
);
CREATE TABLE auth.challenges (
  id uuid PRIMARY KEY,
  email text NOT NULL,
  email_key text NOT NULL,
  proof_hash text NOT NULL,
  code_hash text NOT NULL,
  link_hash text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  consumed_at bigint,
  exchange_id uuid,
  exchange_envelope text,
  exchange_expires_at bigint,
  mail_envelope text,
  mail_lease_until bigint NOT NULL DEFAULT 0,
  mail_lease uuid,
  mail_attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX auth_challenges_email ON auth.challenges(email_key, created_at DESC);
CREATE INDEX auth_challenges_outbox ON auth.challenges(mail_lease_until) WHERE mail_envelope IS NOT NULL;
CREATE TABLE auth.rate_buckets (key text PRIMARY KEY, count integer NOT NULL, expires_at bigint NOT NULL);
CREATE TABLE auth.sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  revoked boolean NOT NULL DEFAULT false
);
CREATE TABLE auth.session_tokens (
  refresh_hash text PRIMARY KEY,
  access_hash text NOT NULL UNIQUE,
  session_id uuid NOT NULL REFERENCES auth.sessions(id) ON DELETE CASCADE,
  access_expires_at bigint NOT NULL,
  used boolean NOT NULL DEFAULT false,
  rotation_id uuid,
  rotation_envelope text,
  rotation_expires_at bigint
);
CREATE INDEX auth_tokens_session ON auth.session_tokens(session_id);
