// Fixed SQL fragments only; aliases i (item), r (revision) and f (flag) are owned
// by the queries below, never supplied by an HTTP caller. UUID spelling is not
// an attribution boundary: compare canonical lower-case values.
export const SOURCE_REFERENCE_SQL=`(
  (lower(r.payload->'source'->>'itemId')=f.source_id::text AND lower(r.payload->'source'->>'revisionId')=f.source_revision_id::text)
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(r.payload->'recipes') p
    WHERE lower(p->'source'->>'itemId')=f.source_id::text AND lower(p->'source'->>'revisionId')=f.source_revision_id::text))`;
export const SOURCE_REVIEW_REQUIRED_SQL=`EXISTS(SELECT 1 FROM publications.source_flags f
  WHERE ${SOURCE_REFERENCE_SQL} AND NOT EXISTS(SELECT 1 FROM publications.source_clearances clearance
    WHERE clearance.item_id=i.id AND clearance.revision_id=r.id AND clearance.incident_id=f.incident_id))`;

export const BLOCKED_PUBLIC_ASSET_SQL=`EXISTS(SELECT 1 FROM jsonb_array_elements(r.payload->'recipes') AS recipe(value)
  CROSS JOIN LATERAL jsonb_array_elements(recipe.value->'assets') AS asset(value)
  JOIN publications.blocked_assets b ON b.sha256=asset.value->>'sha256')`;
