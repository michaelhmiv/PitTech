-- Private payloads live only in authenticated encrypted object storage. These are ownership/lease metadata.
CREATE SCHEMA private_sync;
REVOKE ALL ON SCHEMA private_sync FROM PUBLIC;
CREATE TABLE private_sync.accounts(
 user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE RESTRICT,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0 AND version<9007199254740991),
 head_id uuid,
 updated_at bigint NOT NULL
);
CREATE TABLE private_sync.objects(
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES private_sync.accounts(user_id) ON DELETE RESTRICT,
 key text NOT NULL UNIQUE CHECK(key ~ '^private-sync/[a-f0-9-]{36}\.pittech$'),
 kind text NOT NULL CHECK(kind IN('snapshot','photo')),
 sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
 plain_bytes integer NOT NULL CHECK(plain_bytes>0 AND plain_bytes<=33554432 AND(kind<>'photo' OR plain_bytes<=16777216)),
 reserved_bytes integer NOT NULL CHECK(reserved_bytes=plain_bytes+77),
 cipher_sha256 text CHECK(cipher_sha256 ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN('reserved','active','deleting')),
 lease_id uuid NOT NULL,
 lease_until bigint NOT NULL,
 created_at bigint NOT NULL,
 UNIQUE(user_id,id)
);
ALTER TABLE private_sync.accounts ADD CONSTRAINT private_sync_head_owner FOREIGN KEY(user_id,head_id) REFERENCES private_sync.objects(user_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX private_sync_object_cleanup ON private_sync.objects(user_id,state,lease_until);
CREATE TABLE private_sync.operations(
 user_id uuid NOT NULL REFERENCES private_sync.accounts(user_id) ON DELETE RESTRICT,
 operation_id uuid NOT NULL,
 request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 object_id uuid NOT NULL,
 base_version bigint NOT NULL,
 state text NOT NULL CHECK(state IN('pending','committed','conflict')),
 response jsonb,
 created_at bigint NOT NULL,
 PRIMARY KEY(user_id,operation_id)
);
CREATE TABLE private_sync.budgets(day bigint PRIMARY KEY,uploads integer NOT NULL CHECK(uploads>=0),bytes bigint NOT NULL CHECK(bytes>=0));
REVOKE ALL ON ALL TABLES IN SCHEMA private_sync FROM PUBLIC;
CREATE FUNCTION private_sync.guard_live_account() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('account/'||NEW.user_id::text,890014));
 IF TG_TABLE_NAME='objects' THEN
  IF TG_OP='UPDATE' AND NEW.state='deleting' AND ROW(NEW.id,NEW.user_id,NEW.key,NEW.kind,NEW.sha256,NEW.plain_bytes,NEW.reserved_bytes,NEW.cipher_sha256,NEW.created_at) IS NOT DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.key,OLD.kind,OLD.sha256,OLD.plain_bytes,OLD.reserved_bytes,OLD.cipher_sha256,OLD.created_at) THEN RETURN NEW; END IF;
 ELSIF TG_TABLE_NAME='accounts' THEN
  IF TG_OP='UPDATE' AND NEW.head_id IS NULL AND NEW.version=OLD.version AND NEW.user_id=OLD.user_id THEN RETURN NEW; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=NEW.user_id) OR EXISTS(SELECT 1 FROM auth.account_lifecycle WHERE user_id=NEW.user_id AND deleting) THEN
  RAISE EXCEPTION 'Account unavailable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION private_sync.guard_live_account() FROM PUBLIC;
CREATE TRIGGER live_private_sync_account BEFORE INSERT OR UPDATE ON private_sync.accounts FOR EACH ROW EXECUTE FUNCTION private_sync.guard_live_account();
CREATE TRIGGER live_private_sync_object BEFORE INSERT OR UPDATE ON private_sync.objects FOR EACH ROW EXECUTE FUNCTION private_sync.guard_live_account();
CREATE TRIGGER live_private_sync_operation BEFORE INSERT OR UPDATE ON private_sync.operations FOR EACH ROW EXECUTE FUNCTION private_sync.guard_live_account();
