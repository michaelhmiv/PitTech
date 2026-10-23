-- Apply after migrations using the isolated database administrator.
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_auth_runtime') THEN
    BEGIN
    CREATE ROLE pittech_auth_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_auth_runtime'
      AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
      OR has_schema_privilege('pittech_auth_runtime', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'Auth runtime role has elevated privileges';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_auth_runtime', current_database());
END $$;
GRANT USAGE ON SCHEMA auth TO pittech_auth_runtime;
GRANT SELECT, INSERT, UPDATE ON auth.users TO pittech_auth_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON auth.challenges, auth.provider_challenges, auth.rate_buckets, auth.sessions, auth.session_tokens, auth.mcp_grants TO pittech_auth_runtime;
GRANT SELECT, INSERT, UPDATE ON auth.provider_identities TO pittech_auth_runtime;
GRANT SELECT, INSERT ON auth.apple_notification_receipts TO pittech_auth_runtime;
GRANT SELECT ON auth.account_lifecycle TO pittech_auth_runtime;
COMMIT;
