BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_publications_runtime') THEN
    BEGIN
    CREATE ROLE pittech_publications_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='pittech_publications_runtime' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR has_schema_privilege('pittech_publications_runtime','public','CREATE') THEN RAISE EXCEPTION 'Publications runtime role has elevated privileges'; END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pittech_publications_runtime',current_database());
END $$;
GRANT USAGE ON SCHEMA publications TO pittech_publications_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON publications.profiles,publications.items,publications.objects,publications.revisions,publications.likes,publications.operations,publications.budgets,publications.acknowledgments TO pittech_publications_runtime;
GRANT SELECT ON publications.publisher_controls,publications.blocked_assets,publications.retired_handles,publications.source_flags,publications.source_clearances TO pittech_publications_runtime;
GRANT SELECT,INSERT ON publications.publisher_aliases TO pittech_publications_runtime;
GRANT USAGE ON SCHEMA moderation TO pittech_publications_runtime;
GRANT SELECT(id,state) ON moderation.profile_reviews TO pittech_publications_runtime;
GRANT SELECT ON moderation.approved_profiles TO pittech_publications_runtime;
GRANT INSERT(item_id,revision_id,reason,created_at) ON moderation.reviews TO pittech_publications_runtime;
GRANT INSERT(id,user_id,request_hash,handle,display_name,is_public,created_at) ON moderation.profile_reviews TO pittech_publications_runtime;
COMMIT;
