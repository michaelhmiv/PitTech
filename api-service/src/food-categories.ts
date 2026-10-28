import { readFileSync } from 'node:fs';

/** The one shared food taxonomy for publishing and Discover lives in
 * contracts/fixtures/food-categories-v1.json (owner decision 2026-10-27:
 * categories are a controlled dropdown, never free text — people mistype free
 * text and filtering breaks). The publication contract carries these ids as
 * an enum so the API rejects any value outside the list, and both apps render
 * labels and picker examples from the same file. This module is the server's
 * view of that file; the contract test pins the schema enum to it. */
const contract = JSON.parse(readFileSync(new URL('../../contracts/fixtures/food-categories-v1.json', import.meta.url), 'utf8')) as {
  version: number;
  categories: { id: string; label: string; examples: string[] }[];
};

export interface FoodCategory { id: string; label: string; examples: readonly string[] }

export const FOOD_CATEGORIES: readonly FoodCategory[] = contract.categories.map((c) => ({ id: c.id, label: c.label, examples: c.examples }));

/** Publications written before the taxonomy existed carry no category. They
 * are treated as Other for display and filtering without ever rewriting the
 * stored payload, so old content keeps working untouched. */
export const DEFAULT_FOOD_CATEGORY_ID = 'other';

const ids = new Set(FOOD_CATEGORIES.map((c) => c.id));

export function isFoodCategoryId(value: unknown): value is string {
  return typeof value === 'string' && ids.has(value);
}

/** The category a stored payload counts as: its own id when valid, otherwise
 * the pre-taxonomy default (Other). Used for read paths only — writes are
 * validated by the publication contract's enum, never by this fallback. */
export function normalizeFoodCategory(value: unknown): string {
  return isFoodCategoryId(value) ? value : DEFAULT_FOOD_CATEGORY_ID;
}
