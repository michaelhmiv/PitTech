-- Apply after migrations, using the isolated relay database administrator.
-- No login credential is created here. Grant this role to the separate API login.
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_relay_runtime') THEN
    CREATE ROLE pittech_relay_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_relay_runtime'
      AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Existing runtime role has elevated privileges';
  END IF;
  IF has_schema_privilege('pittech_relay_runtime', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'Runtime role can create public objects; use a dedicated database with restricted PUBLIC privileges';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_relay_runtime', current_database());
END $$;
GRANT USAGE ON SCHEMA relay TO pittech_relay_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON relay.packs, relay.rate_buckets, relay.daily_budgets TO pittech_relay_runtime;
-- Deliberately no CREATE, TRUNCATE, migration-table access or future-table grants.
COMMIT;
