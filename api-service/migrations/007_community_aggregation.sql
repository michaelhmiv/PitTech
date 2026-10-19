-- Slice 17 backend-prep lane: isolated community aggregation storage and the
-- eligible-attempt projection. Additive only: no existing table is altered.
CREATE SCHEMA community_aggregation;

CREATE TABLE community_aggregation.job_runs (
  run_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cutoff_millis bigint NOT NULL CHECK (cutoff_millis >= 0),
  computed_at bigint NOT NULL CHECK (computed_at >= 0),
  algorithm_version text NOT NULL CHECK (length(algorithm_version) BETWEEN 1 AND 160),
  taxonomy_version text NOT NULL CHECK (length(taxonomy_version) BETWEEN 1 AND 160),
  band_version text NOT NULL CHECK (length(band_version) BETWEEN 1 AND 160),
  metric_set_hash text NOT NULL CHECK (metric_set_hash ~ '^[a-f0-9]{64}$'),
  input_sha256 text NOT NULL CHECK (input_sha256 ~ '^[a-f0-9]{64}$'),
  input_count integer NOT NULL CHECK (input_count >= 0),
  eligible_count integer NOT NULL CHECK (eligible_count >= 0),
  unbucketed_count integer NOT NULL CHECK (unbucketed_count >= 0),
  cell_count integer NOT NULL CHECK (cell_count >= 0),
  visible_count integer NOT NULL CHECK (visible_count >= 0),
  output_sha256 text NOT NULL CHECK (output_sha256 ~ '^[a-f0-9]{64}$'),
  figures jsonb NOT NULL,
  UNIQUE (cutoff_millis, algorithm_version, taxonomy_version, band_version, metric_set_hash, input_sha256)
);

CREATE TABLE community_aggregation.figures (
  run_id uuid NOT NULL REFERENCES community_aggregation.job_runs(run_id) ON DELETE CASCADE,
  bucket text NOT NULL CHECK (length(bucket) BETWEEN 1 AND 320),
  metric_id text NOT NULL CHECK (length(metric_id) BETWEEN 1 AND 160),
  n integer NOT NULL CHECK (n >= 0),
  status text NOT NULL CHECK (status IN ('visible', 'insufficient_data')),
  figure jsonb NOT NULL,
  PRIMARY KEY (run_id, bucket, metric_id)
);

-- The single SQL choke point for community aggregation. One row per (current
-- revision, recipe with a measured story) from deliberately active public
-- items. It selects NO instruction fields and references NO private store.
-- SECURITY DEFINER lets the aggregation runtime role execute it without any
-- direct grant on publications tables, so the allowlisted columns below are
-- the only publication data that role can ever observe. The visibility
-- predicates mirror public reads: author deletion/suspension, blocked assets,
-- and uncleared source-review flags all exclude the contribution, so removal
-- and takedown propagate to aggregates through eligibility.
CREATE FUNCTION community_aggregation.eligible_attempts()
RETURNS TABLE(item_id uuid, revision_id uuid, collected_at bigint, dish_id text, attempt_id text,
  food_type text, cut text, weight_grams double precision, method text, story jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
  SELECT i.id, r.id, i.updated_at,
    recipe.value->>'id', (recipe.value->'story')->>'attemptId',
    recipe.value->>'foodType', recipe.value->>'cut',
    CASE WHEN jsonb_typeof(recipe.value->'weightGrams') = 'number'
      THEN (recipe.value->>'weightGrams')::double precision ELSE NULL END,
    recipe.value->>'method', recipe.value->'story'
  FROM publications.items i
  JOIN publications.revisions r ON r.id = i.current_revision_id
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(r.payload->'recipes') = 'array' THEN r.payload->'recipes' ELSE '[]' END
  ) AS recipe(value)
  LEFT JOIN publications.publisher_controls c ON c.user_id = i.author_id
  WHERE i.status = 'active'
    AND NOT COALESCE(c.deleting OR c.suspended, false)
    AND jsonb_typeof(recipe.value->'story') = 'object'
    AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(r.payload->'recipes') = 'array' THEN r.payload->'recipes' ELSE '[]' END
      ) AS rc(value)
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(rc.value->'assets') = 'array' THEN rc.value->'assets' ELSE '[]' END
      ) AS ac(value)
      JOIN publications.blocked_assets b ON b.sha256 = ac.value->>'sha256'
    )
    AND NOT EXISTS (
      SELECT 1 FROM publications.source_flags f
      WHERE (
        (lower(r.payload->'source'->>'itemId') = f.source_id::text
          AND lower(r.payload->'source'->>'revisionId') = f.source_revision_id::text)
        OR EXISTS (
          SELECT 1 FROM jsonb_array_elements(
            CASE WHEN jsonb_typeof(r.payload->'recipes') = 'array' THEN r.payload->'recipes' ELSE '[]' END
          ) AS p(value)
          WHERE lower(p.value->'source'->>'itemId') = f.source_id::text
            AND lower(p.value->'source'->>'revisionId') = f.source_revision_id::text
        )
      )
      AND NOT EXISTS (
        SELECT 1 FROM publications.source_clearances clearance
        WHERE clearance.item_id = i.id AND clearance.revision_id = r.id AND clearance.incident_id = f.incident_id
      )
    )
$$;
REVOKE ALL ON FUNCTION community_aggregation.eligible_attempts() FROM PUBLIC;
