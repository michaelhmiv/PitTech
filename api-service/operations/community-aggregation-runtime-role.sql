-- Slice 17 backend-prep lane: aggregation runtime grants. Administrator only,
-- after migration 007. The aggregation role executes the allowlisted
-- eligible-attempts projection and persists runs/figures. It holds NO grants on
-- publications tables, auth, relay, private sync, or moderation: direct reads
-- of those sources fail with 42501, which the Postgres isolation test asserts.
BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_community_aggregation_runtime') THEN
    BEGIN
    CREATE ROLE pittech_community_aggregation_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_community_aggregation_runtime' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR has_schema_privilege('pittech_community_aggregation_runtime','public','CREATE') THEN RAISE EXCEPTION 'Community aggregation runtime role has elevated privileges'; END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_community_aggregation_runtime',current_database());
END $$;
GRANT USAGE ON SCHEMA community_aggregation TO pittech_community_aggregation_runtime;
GRANT SELECT,INSERT ON community_aggregation.job_runs,community_aggregation.figures TO pittech_community_aggregation_runtime;
GRANT EXECUTE ON FUNCTION community_aggregation.eligible_attempts() TO pittech_community_aggregation_runtime;
COMMIT;
