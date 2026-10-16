CREATE TABLE auth.account_lifecycle (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  deleting boolean NOT NULL DEFAULT false,
  suspended boolean NOT NULL DEFAULT false,
  updated_at bigint NOT NULL
);
CREATE VIEW publications.publisher_controls WITH (security_barrier=true) AS
  SELECT u.id AS user_id,COALESCE(l.deleting,false) AS deleting,COALESCE(l.suspended,false) AS suspended
  FROM auth.users u LEFT JOIN auth.account_lifecycle l ON l.user_id=u.id;
ALTER TABLE publications.items DROP CONSTRAINT items_status_check;
ALTER TABLE publications.items ADD CONSTRAINT items_status_check CHECK(status IN ('active','pending_review','unpublished','taken_down','purged'));
CREATE TABLE publications.publisher_aliases (
  item_id uuid PRIMARY KEY REFERENCES publications.items(id) ON DELETE CASCADE,
  alias uuid NOT NULL UNIQUE DEFAULT gen_random_uuid()
);
INSERT INTO publications.publisher_aliases(item_id) SELECT id FROM publications.items;
CREATE TABLE publications.blocks (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  publisher_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  alias uuid NOT NULL,
  created_at bigint NOT NULL,
  PRIMARY KEY(user_id,publisher_id),
  CHECK(user_id<>publisher_id)
);
CREATE TABLE publications.blocked_assets (
  sha256 text PRIMARY KEY CHECK(sha256 ~ '^[a-f0-9]{64}$'),
  created_at bigint NOT NULL
);
CREATE SCHEMA moderation;
CREATE TABLE moderation.operators (
  id uuid PRIMARY KEY,
  token_hash text NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
  label text NOT NULL CHECK(length(label) BETWEEN 1 AND 100),
  role text NOT NULL CHECK(role IN ('moderator','owner')),
  disabled boolean NOT NULL DEFAULT false
);
CREATE TABLE moderation.reviews (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  item_id uuid NOT NULL REFERENCES publications.items(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL UNIQUE REFERENCES publications.revisions(id) ON DELETE CASCADE,
  state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','approved','rejected','superseded')),
  reason text NOT NULL CHECK(reason IN ('new_content','changed_content','source_takedown','legacy_content')),
  note text NOT NULL DEFAULT '' CHECK(length(note)<=2000),
  created_at bigint NOT NULL,
  reviewed_at bigint
);
INSERT INTO moderation.reviews(item_id,revision_id,reason,created_at)
  SELECT id,current_revision_id,'legacy_content',updated_at FROM publications.items WHERE status='active';
UPDATE publications.items SET status='pending_review' WHERE status='active';
CREATE TABLE moderation.reports (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL,
  reporter_key text NOT NULL CHECK(length(reporter_key)=64),
  reporter_user_id uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  request_hash text NOT NULL CHECK(length(request_hash)=64),
  item_id uuid NOT NULL REFERENCES publications.items(id),
  asset_id uuid,
  kind text NOT NULL CHECK(kind IN ('content','publisher')),
  reason text NOT NULL CHECK(reason IN ('spam','harassment','unsafe','privacy','copyright','other')),
  details text NOT NULL CHECK(length(details)<=2000),
  state text NOT NULL DEFAULT 'open' CHECK(state IN ('open','reviewing','resolved','dismissed')),
  moderator_note text NOT NULL DEFAULT '' CHECK(length(moderator_note)<=2000),
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  UNIQUE(reporter_key,operation_id)
);
CREATE TABLE moderation.operations (
  principal_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  request_hash text NOT NULL CHECK(length(request_hash)=64),
  response jsonb NOT NULL,
  PRIMARY KEY(principal_id,operation_id)
);
CREATE TABLE moderation.audit (
  id uuid PRIMARY KEY,
  operator_id uuid NOT NULL REFERENCES moderation.operators(id),
  action text NOT NULL CHECK(action IN ('approve','reject','takedown','suspend','restore','report_status')),
  target_id uuid NOT NULL,
  revision_id uuid,
  note text NOT NULL CHECK(length(note)<=2000),
  created_at bigint NOT NULL
);
CREATE TABLE moderation.deletions (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL UNIQUE,
  user_id uuid UNIQUE REFERENCES auth.users(id) ON DELETE SET NULL,
  proof_hash text NOT NULL CHECK(proof_hash ~ '^[a-f0-9]{64}$'),
  request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
  status_hash text NOT NULL CHECK(status_hash ~ '^[a-f0-9]{64}$'),
  receipt_envelope text NOT NULL,
  state text NOT NULL CHECK(state IN ('queued','purging','notifying','complete')),
  lease_id uuid,
  lease_until bigint NOT NULL DEFAULT 0,
  attempts integer NOT NULL DEFAULT 0,
  created_at bigint NOT NULL,
  completed_at bigint
);
CREATE TABLE moderation.notices (
  id uuid PRIMARY KEY,
  user_id uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  deletion_id uuid REFERENCES moderation.deletions(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK(kind IN ('publication_approved','publication_removed','account_suspended','deletion_complete')),
  envelope text,
  state text NOT NULL CHECK(state IN ('pending','sent','failed')),
  lease_id uuid,
  lease_until bigint NOT NULL DEFAULT 0,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt bigint NOT NULL,
  created_at bigint NOT NULL
);
CREATE TABLE moderation.budgets (
  key text PRIMARY KEY,
  count integer NOT NULL,
  expires_at bigint NOT NULL
);
CREATE INDEX moderation_pending_reviews ON moderation.reviews(state,created_at);
CREATE INDEX moderation_open_reports ON moderation.reports(state,created_at);
CREATE INDEX moderation_deletion_jobs ON moderation.deletions(state,lease_until);
CREATE INDEX moderation_notice_jobs ON moderation.notices(state,next_attempt,lease_until);
CREATE TABLE moderation.profile_reviews (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  request_hash text NOT NULL,
  handle text,
  display_name text NOT NULL,
  is_public boolean NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','approved','rejected','superseded')),
  note text NOT NULL DEFAULT '' CHECK(length(note)<=2000),
  created_at bigint NOT NULL
);
ALTER TABLE publications.profiles ADD COLUMN review_id uuid REFERENCES moderation.profile_reviews(id) ON DELETE SET NULL;
CREATE TABLE moderation.approved_profiles (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  review_id uuid NOT NULL REFERENCES moderation.profile_reviews(id) ON DELETE CASCADE
);
CREATE INDEX moderation_pending_profiles ON moderation.profile_reviews(state,created_at);
-- A keyed, non-account-linked handle tombstone prevents old profile links from
-- later resolving to an unrelated account, without retaining the original handle.
CREATE TABLE publications.retired_handles (
  hash text PRIMARY KEY CHECK(hash ~ '^[a-f0-9]{64}$')
);
CREATE TABLE publications.source_flags (
  source_id uuid PRIMARY KEY REFERENCES publications.items(id) ON DELETE CASCADE,
  source_revision_id uuid NOT NULL,
  incident_id uuid NOT NULL UNIQUE,
  created_at bigint NOT NULL
);
CREATE TABLE publications.source_clearances (
  item_id uuid NOT NULL REFERENCES publications.items(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES publications.revisions(id) ON DELETE CASCADE,
  incident_id uuid NOT NULL,
  PRIMARY KEY(item_id,revision_id,incident_id)
);

-- Fence in-flight writers (including older application instances) at the database
-- boundary. These functions expose no identity and cannot be called by runtime
-- roles directly. The advisory key/order matches the application account lock.
CREATE FUNCTION auth.guard_live_account() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE account_id uuid;
BEGIN
  IF TG_TABLE_SCHEMA='auth' THEN
    account_id:=NEW.user_id;
  ELSIF TG_TABLE_NAME='objects' THEN
    IF NEW.state='deleting' THEN RETURN NEW; END IF;
    account_id:=NEW.owner_id;
  ELSIF TG_TABLE_NAME='items' THEN
    IF NEW.author_id IS NULL OR (NEW.status='purged' AND NEW.current_revision_id IS NULL) THEN RETURN NEW; END IF;
    account_id:=NEW.author_id;
  ELSIF TG_TABLE_NAME='profiles' THEN
    account_id:=NEW.user_id;
    IF TG_OP='UPDATE' AND NOT NEW.is_public AND NEW.handle IS NOT DISTINCT FROM OLD.handle
      AND NEW.display_name IS NOT DISTINCT FROM OLD.display_name AND NEW.review_id IS NOT DISTINCT FROM OLD.review_id THEN RETURN NEW; END IF;
  ELSE
    SELECT author_id INTO account_id FROM publications.items WHERE id=NEW.item_id;
  END IF;
  IF account_id IS NULL THEN RAISE EXCEPTION 'Account unavailable' USING ERRCODE='23514'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('account/'||account_id::text,890014));
  IF EXISTS(SELECT 1 FROM auth.account_lifecycle WHERE user_id=account_id AND deleting) THEN
    RAISE EXCEPTION 'Account unavailable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION auth.guard_live_account() FROM PUBLIC;
CREATE TRIGGER live_session BEFORE INSERT ON auth.sessions FOR EACH ROW EXECUTE FUNCTION auth.guard_live_account();
CREATE TRIGGER live_publication_object BEFORE INSERT OR UPDATE ON publications.objects FOR EACH ROW EXECUTE FUNCTION auth.guard_live_account();
CREATE TRIGGER live_publication_item BEFORE INSERT OR UPDATE ON publications.items FOR EACH ROW EXECUTE FUNCTION auth.guard_live_account();
CREATE TRIGGER live_publication_revision BEFORE INSERT ON publications.revisions FOR EACH ROW EXECUTE FUNCTION auth.guard_live_account();
CREATE TRIGGER live_publication_profile BEFORE INSERT OR UPDATE ON publications.profiles FOR EACH ROW EXECUTE FUNCTION auth.guard_live_account();
