CREATE TABLE publications.acknowledgments (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  operation_id uuid NOT NULL,
  revision_id uuid NOT NULL REFERENCES publications.revisions(id) ON DELETE CASCADE,
  version text NOT NULL CHECK(length(version) BETWEEN 1 AND 64),
  accepted_at bigint NOT NULL,
  PRIMARY KEY(user_id,operation_id),
  FOREIGN KEY(user_id,operation_id) REFERENCES publications.operations(user_id,operation_id) ON DELETE CASCADE
);
