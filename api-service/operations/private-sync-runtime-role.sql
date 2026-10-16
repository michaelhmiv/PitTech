-- Administrator only, after migration 006 and lifecycle-runtime-role.sql. Never use the owner connection at runtime.
BEGIN;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='pittech_private_sync_runtime') THEN
  BEGIN CREATE ROLE pittech_private_sync_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='pittech_private_sync_runtime' AND(rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) OR has_schema_privilege('pittech_private_sync_runtime','public','CREATE') THEN RAISE EXCEPTION 'Private sync runtime role has elevated privileges'; END IF;
 EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_private_sync_runtime',current_database());
END $$;
GRANT USAGE ON SCHEMA private_sync,publications TO pittech_private_sync_runtime;
GRANT SELECT ON publications.publisher_controls TO pittech_private_sync_runtime;
GRANT SELECT,INSERT,DELETE ON private_sync.accounts,private_sync.objects,private_sync.operations,private_sync.budgets TO pittech_private_sync_runtime;
GRANT UPDATE(version,head_id,updated_at) ON private_sync.accounts TO pittech_private_sync_runtime;
GRANT UPDATE(cipher_sha256,state,lease_id,lease_until) ON private_sync.objects TO pittech_private_sync_runtime;
GRANT UPDATE(state,response) ON private_sync.operations TO pittech_private_sync_runtime;
GRANT UPDATE(uploads,bytes) ON private_sync.budgets TO pittech_private_sync_runtime;
GRANT USAGE ON SCHEMA private_sync TO pittech_lifecycle_runtime;
GRANT SELECT,DELETE ON private_sync.accounts,private_sync.objects,private_sync.operations TO pittech_lifecycle_runtime;
GRANT UPDATE(head_id) ON private_sync.accounts TO pittech_lifecycle_runtime;
GRANT UPDATE(state,lease_id,lease_until) ON private_sync.objects TO pittech_lifecycle_runtime;
COMMIT;
