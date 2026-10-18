CREATE SCHEMA billing;
CREATE TABLE billing.transactions (
  store text NOT NULL CHECK (store IN ('apple', 'google')),
  environment text NOT NULL CHECK (environment IN ('sandbox', 'production')),
  transaction_key text NOT NULL,
  latest_transaction text NOT NULL,
  account_id uuid NOT NULL,
  product_id text NOT NULL,
  purchased_at bigint NOT NULL,
  expires_at bigint,
  auto_renew boolean,
  revoked boolean NOT NULL DEFAULT false,
  cancelled_marker boolean NOT NULL DEFAULT false,
  store_reference text NOT NULL,
  evidence_ref text NOT NULL,
  released boolean NOT NULL DEFAULT false,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (store, environment, transaction_key)
);
CREATE INDEX billing_transactions_account ON billing.transactions(account_id, environment);
CREATE TABLE billing.entitlements (
  account_id uuid NOT NULL,
  environment text NOT NULL CHECK (environment IN ('sandbox', 'production')),
  store text NOT NULL CHECK (store IN ('apple', 'google')),
  state text NOT NULL CHECK (state IN ('none', 'pending', 'active', 'grace', 'retry', 'paused', 'expired', 'cancelled', 'refunded', 'revoked')),
  transaction_store text CHECK (transaction_store IN ('apple', 'google')),
  transaction_key text,
  expires_at bigint,
  auto_renew boolean,
  last_verified_at bigint NOT NULL,
  applied_event_at bigint NOT NULL DEFAULT 0,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  PRIMARY KEY (account_id, environment)
);
CREATE TABLE billing.store_events (
  event_key text PRIMARY KEY,
  store text NOT NULL CHECK (store IN ('apple', 'google')),
  environment text NOT NULL CHECK (environment IN ('sandbox', 'production')),
  received_at bigint NOT NULL,
  applied boolean NOT NULL,
  note text NOT NULL
);
CREATE TABLE billing.binding_conflicts (
  id uuid PRIMARY KEY,
  store text NOT NULL CHECK (store IN ('apple', 'google')),
  environment text NOT NULL CHECK (environment IN ('sandbox', 'production')),
  transaction_key text NOT NULL,
  holder_account uuid NOT NULL,
  claimant_account uuid NOT NULL,
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'released', 'transferred', 'rejected')),
  created_at bigint NOT NULL,
  resolved_at bigint
);
CREATE UNIQUE INDEX billing_conflicts_open ON billing.binding_conflicts(store, environment, transaction_key, claimant_account) WHERE state = 'open';
CREATE TABLE billing.reconciliation_events (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL,
  environment text NOT NULL CHECK (environment IN ('sandbox', 'production')),
  from_state text NOT NULL,
  to_state text NOT NULL,
  reason text NOT NULL,
  created_at bigint NOT NULL
);
CREATE INDEX billing_reconciliation_account ON billing.reconciliation_events(account_id, environment, created_at DESC);
