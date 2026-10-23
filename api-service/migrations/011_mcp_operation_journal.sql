-- MCP operation journal (issue #114, M3c): before/after record images
-- for every journaled MCP mutation, so undo_private_operation can
-- reverse an operation exactly — and refuse when later work has
-- built on top of it. Images are the owner's own synced records,
-- stored beside the grants that produced them.
CREATE TABLE auth.mcp_operation_journal (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  mutation_id uuid NOT NULL,
  tool text NOT NULL,
  before_image jsonb NOT NULL,
  after_image jsonb NOT NULL,
  after_version bigint NOT NULL,
  created_at bigint NOT NULL,
  undone_at bigint,
  undone_by uuid,
  PRIMARY KEY (user_id, mutation_id)
);
