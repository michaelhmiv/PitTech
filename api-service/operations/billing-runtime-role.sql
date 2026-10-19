-- Apply after migrations using the isolated database administrator.
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_billing_runtime') THEN
    BEGIN
    CREATE ROLE pittech_billing_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pittech_billing_runtime'
      AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
      OR has_schema_privilege('pittech_billing_runtime', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'Billing runtime role has elevated privileges';
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_billing_runtime', current_database());
END $$;
GRANT USAGE ON SCHEMA billing TO pittech_billing_runtime;
GRANT SELECT, INSERT, UPDATE ON billing.transactions, billing.entitlements, billing.store_events, billing.binding_conflicts, billing.reconciliation_events TO pittech_billing_runtime;
COMMIT;
