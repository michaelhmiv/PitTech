// Slice 17 backend-prep lane: taxonomy plug-in points for community aggregation.
//
// The food/cut taxonomy and weight-band boundaries are slice 15's versioned
// calculation contract. This module defines ONLY the interface the aggregation
// job buckets through, plus the version hooks every output records. It pins no
// food/cut groupings, no band boundaries, and no metric meanings.
export type BucketInput = {
  readonly foodType: string;
  readonly cut: string | null;
  readonly weightGrams: number | null;
  readonly method: string | null;
};

export type CommunityTaxonomy = {
  // Version of the food/cut/method grouping rules. Recorded on every figure and
  // every job run so a taxonomy change is always visible in the outputs.
  readonly taxonomyVersion: string;
  // Version of the weight-band boundaries, tracked alongside the taxonomy.
  readonly bandVersion: string;
  // Stable bucket key for one eligible attempt, or null when the attempt cannot
  // be bucketed (it is then counted as unbucketed, never silently dropped).
  bucket(input: BucketInput): string | null;
};

/** Non-authoritative test stub. NOT a food/cut taxonomy: it groups by raw
 * food-type text only, deliberately ignores cut/weight/method, and must never
 * be presented as community analysis. Slice 15 replaces it with the reviewed
 * versioned taxonomy; the job records whichever versions it runs with. */
export const STUB_TAXONOMY_NON_AUTHORITATIVE: CommunityTaxonomy = {
  taxonomyVersion: 'stub-non-authoritative-v0',
  bandVersion: 'stub-bands-v0',
  bucket(input) {
    const food = input.foodType.trim().toLowerCase();
    if (!food) return null;
    return `stub:${food}`;
  },
};
