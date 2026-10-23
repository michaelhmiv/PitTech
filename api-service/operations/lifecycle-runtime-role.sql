-- Run only with the isolated database administrator after migration 005.
BEGIN;
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='pittech_lifecycle_runtime') THEN
    BEGIN
    CREATE ROLE pittech_lifecycle_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='pittech_lifecycle_runtime' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR has_schema_privilege('pittech_lifecycle_runtime','public','CREATE') THEN RAISE EXCEPTION 'Lifecycle runtime role has elevated privileges'; END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_lifecycle_runtime',current_database());
END $$;
GRANT USAGE ON SCHEMA auth,publications,moderation TO pittech_lifecycle_runtime;
GRANT SELECT,DELETE ON auth.users,auth.challenges,auth.provider_challenges,auth.mcp_grants TO pittech_lifecycle_runtime;
GRANT SELECT,UPDATE,DELETE ON auth.sessions,auth.session_tokens TO pittech_lifecycle_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth.account_lifecycle TO pittech_lifecycle_runtime;
GRANT SELECT,UPDATE,DELETE ON publications.items,publications.revisions,publications.objects,publications.profiles TO pittech_lifecycle_runtime;
GRANT SELECT,INSERT,DELETE ON publications.blocks,publications.blocked_assets,publications.retired_handles TO pittech_lifecycle_runtime;
GRANT SELECT,DELETE ON publications.likes,publications.operations,publications.acknowledgments,publications.publisher_aliases TO pittech_lifecycle_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON publications.source_flags,publications.source_clearances TO pittech_lifecycle_runtime;
GRANT SELECT ON publications.publisher_controls TO pittech_lifecycle_runtime;
GRANT SELECT ON moderation.operators TO pittech_lifecycle_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON moderation.reviews,moderation.reports,moderation.operations,moderation.profile_reviews,moderation.approved_profiles,moderation.deletions,moderation.notices,moderation.budgets TO pittech_lifecycle_runtime;
GRANT SELECT,INSERT ON moderation.audit TO pittech_lifecycle_runtime;
GRANT UPDATE(note) ON moderation.audit TO pittech_lifecycle_runtime;
COMMIT;
