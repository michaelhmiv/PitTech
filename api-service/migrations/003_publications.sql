-- Deliberately separate from auth and future owner-private sync storage.
CREATE SCHEMA publications;
CREATE TABLE publications.profiles (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  handle text UNIQUE CHECK(handle ~ '^[a-z0-9_]{3,24}$'),
  display_name text NOT NULL DEFAULT '',
  is_public boolean NOT NULL DEFAULT false,
  updated_at bigint NOT NULL
);
CREATE TABLE publications.items (
  id uuid PRIMARY KEY,
  author_id uuid REFERENCES auth.users(id),
  current_revision_id uuid,
  parent_id uuid REFERENCES publications.items(id),
  status text NOT NULL CHECK(status IN ('active','unpublished','taken_down','purged')),
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL
);
CREATE TABLE publications.objects (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES auth.users(id),
  operation_id uuid NOT NULL,
  request_hash text NOT NULL,
  key text NOT NULL UNIQUE,
  sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
  bytes bigint NOT NULL CHECK(bytes BETWEEN 1 AND 67108864),
  state text NOT NULL CHECK(state IN ('uploading','active','deleting')),
  lease_id uuid NOT NULL,
  lease_until bigint NOT NULL,
  created_at bigint NOT NULL,
  UNIQUE(owner_id,operation_id)
);
CREATE TABLE publications.revisions (
  id uuid PRIMARY KEY,
  item_id uuid NOT NULL REFERENCES publications.items(id),
  revision_no integer NOT NULL CHECK(revision_no>0),
  object_id uuid NOT NULL REFERENCES publications.objects(id),
  identity_mode text NOT NULL CHECK(identity_mode IN ('named','anonymous')),
  payload jsonb NOT NULL,
  created_at bigint NOT NULL,
  UNIQUE(item_id,revision_no)
);
ALTER TABLE publications.items ADD CONSTRAINT current_revision_fk FOREIGN KEY(current_revision_id) REFERENCES publications.revisions(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE publications.likes (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  item_id uuid NOT NULL REFERENCES publications.items(id) ON DELETE CASCADE,
  PRIMARY KEY(user_id,item_id)
);
CREATE TABLE publications.operations (
  user_id uuid NOT NULL REFERENCES auth.users(id),
  operation_id uuid NOT NULL,
  request_hash text NOT NULL,
  response jsonb NOT NULL,
  PRIMARY KEY(user_id,operation_id)
);
CREATE TABLE publications.budgets (key text PRIMARY KEY, count bigint NOT NULL, bytes bigint NOT NULL, expires_at bigint NOT NULL);
CREATE INDEX publication_objects_cleanup ON publications.objects(state,lease_until);
CREATE INDEX publication_items_owner ON publications.items(author_id,status);
CREATE INDEX publication_items_parent ON publications.items(parent_id,status);
CREATE INDEX publication_likes_item ON publications.likes(item_id);
