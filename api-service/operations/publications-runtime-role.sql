BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_publications_runtime') THEN
    CREATE ROLE pittech_publications_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_publications_runtime' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR has_schema_privilege('pittech_publications_runtime','public','CREATE') THEN RAISE EXCEPTION 'Publications runtime role has elevated privileges'; END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_publications_runtime',current_database());
END $$;
GRANT USAGE ON SCHEMA publications TO pittech_publications_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON publications.profiles,publications.items,publications.objects,publications.revisions,publications.likes,publications.operations,publications.budgets TO pittech_publications_runtime;
COMMIT;
