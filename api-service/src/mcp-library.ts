import { createHash, randomUUID } from 'node:crypto';
import { ApiError } from './core.ts';
import { MCP_SCOPES, hasScope, type McpGrants, type McpPrincipal, type McpScope } from './mcp-grants.ts';
import type { SyncEnvelope } from './sync-envelope.ts';
import type { SyncRecord } from './sync-merge.ts';

/**
 * The MCP command layer's read side (issue #114, M2): the only door
 * between MCP tools and the owner's private library. Tools never touch
 * storage themselves — every read resolves here, against the synced
 * server snapshot, and every answer distinguishes "no synced library"
 * from "an empty one" (the registry's connection.context rule). Writes
 * join this service in M3+ behind MCP_WRITES_ENABLED with expected
 * revisions and durable receipts; nothing here mutates.
 */

export interface SnapshotReader {
  read(user: string): Promise<{ version: number; envelope: SyncEnvelope | null }>;
  status(user: string): Promise<{ version: number; updatedAt: number | null }>;
}

/** The write half of the snapshot store: PrivateSnapshots.put is a
 * compare-and-swap on the envelope's baseVersion with a durable,
 * idempotent receipt per mutationId — exactly the guarantees the MCP
 * command spine is built on. */
export interface McpCommunitySource {
  discover(input: { sort?: string; food?: string; query?: string; cursor?: string }): Promise<unknown>;
  get(id: string): Promise<{ revisionId: string; payload: { recipes?: Record<string, unknown>[] }; credit?: unknown }>;
}
export interface McpBenchmarkSource {
  latestFigures(bucket?: string, metric?: string): Promise<{ run: unknown; figures: readonly unknown[] }>;
}
export interface McpPhotoStore {
  begin(user: string, input: unknown): Promise<unknown>;
  statusOf(user: string, asset: string): Promise<unknown>;
  putPart(user: string, asset: string, index: number, bytes: Buffer): Promise<unknown>;
  finish(user: string, asset: string): Promise<unknown>;
  read(user: string, asset: string): Promise<{ bytes: Buffer; mimeType: string; sha256: string }>;
  abandon(user: string, asset: string): Promise<unknown>;
}
export interface SnapshotReadWriter extends SnapshotReader {
  put(user: string, value: unknown): Promise<{ version: number; mutationId: string; sha256: string }>;
  receiptFor(user: string, mutationId: string): Promise<{ version: number; mutationId: string; sha256: string } | undefined>;
}

/** The MCP writer identity stamped on every record MCP creates or
 * changes (sync requires changed records to carry the envelope's
 * clientId, a lowercase UUID). One identity for the whole surface:
 * provenance in the data says "the MCP writer did this", while the
 * durable receipt + grant audit say which connection. */
export const MCP_WRITER_CLIENT_ID = '6d435057-0000-4000-8000-000000000001';

export const COOK_STATUSES = ['active', 'paused', 'completed'] as const;
const COOK_TRANSITIONS: Record<string, readonly string[]> = {
  active: ['paused', 'completed'],
  paused: ['active', 'completed'],
  completed: [],
};

/** Expected, user-fixable tool failures (unknown id, bad arguments).
 * The protocol core renders these as isError tool results with the
 * message intact, instead of a generic internal error. */
export class ToolFailure extends Error {}

type Payload = Record<string, unknown>;
interface LibraryData {
  version: number;
  byType: Map<string, Map<string, Payload>>;
}
const iso = (millis: unknown): string | null => typeof millis === 'number' && Number.isFinite(millis) ? new Date(millis).toISOString() : null;
const str = (value: unknown): string | undefined => typeof value === 'string' ? value : undefined;
const num = (value: unknown): number | undefined => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
const safeJson = (value: string): unknown => { try { return JSON.parse(value); } catch { return value; } };
function page<T>(items: T[], args: Record<string, unknown>): { items: T[]; nextCursor: string | null; total: number } {
  const limitRaw = args.limit === undefined ? 20 : args.limit;
  if (typeof limitRaw !== 'number' || !Number.isSafeInteger(limitRaw) || limitRaw < 1 || limitRaw > 50) throw new ToolFailure('limit must be an integer between 1 and 50.');
  let offset = 0;
  if (args.cursor !== undefined) {
    if (typeof args.cursor !== 'string') throw new ToolFailure('cursor must be a string returned by a previous call.');
    const parsed = Number(Buffer.from(args.cursor, 'base64url').toString('utf8'));
    if (!Number.isSafeInteger(parsed) || parsed < 0) throw new ToolFailure('cursor is not valid; start again without one.');
    offset = parsed;
  }
  const slice = items.slice(offset, offset + limitRaw);
  return { items: slice, nextCursor: offset + limitRaw < items.length ? Buffer.from(String(offset + limitRaw)).toString('base64url') : null, total: items.length };
}
const WRITE_ARGS = {
  expectedSnapshotVersion: { type: 'integer', description: 'Optional guard: the write only applies if the library is still at this snapshot version (from a read result).' },
  idempotencyKey: { type: 'string', description: 'Optional UUID. Repeating a call with the same key and the same change returns the original receipt instead of applying twice.' },
} as const;
const PAGE_ARGS = {
  limit: { type: 'integer', minimum: 1, maximum: 50, description: 'Page size (default 20).' },
  cursor: { type: 'string', description: 'Cursor from a previous response.' },
} as const;

export interface ToolDefinition {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  /** All scopes a principal must hold to see or call the tool. */
  scopes: readonly McpScope[];
  handler: (principal: McpPrincipal, args: Record<string, unknown>) => Promise<unknown>;
}

const NO_ARGS_SCHEMA = { type: 'object', properties: {}, additionalProperties: false } as const;


/** A mutable working copy of the snapshot for one mutation. Records
 * are keyed exactly like the sync layer (recordType + recordId, where
 * the id is the projection key field — id, or cookId for lifecycles,
 * readingId for logReadingLinks, dishId for dishRecipeSources). */
const KEY_FIELDS: Record<string, string> = { lifecycles: 'cookId', logReadingLinks: 'readingId', dishRecipeSources: 'dishId' };
class WorkingLibrary {
  private rows: SyncRecord[]; private clock: () => number; private stamp: number;
  constructor(envelope: SyncEnvelope | null, clock: () => number) {
    this.rows = structuredClone(envelope?.records ?? []);
    this.clock = clock;
    this.stamp = this.rows.reduce((max, r) => Math.max(max, r.version.counter), 0) + 1;
  }
  records(): SyncRecord[] { return this.rows; }
  private keyOf(type: string, payload: Payload): string { return String(payload[KEY_FIELDS[type] ?? 'id']); }
  private find(type: string, id: string): SyncRecord | undefined { return this.rows.find(r => r.recordType === type && r.recordId === id); }
  live(type: string, id: unknown, label: string): Payload {
    if (typeof id !== 'string' || !id) throw new ToolFailure(`${label} id is required.`);
    const row = this.find(type, id);
    if (!row || row.deleted) throw new ToolFailure(`No ${label.toLowerCase()} with id ${id} exists in the synced library. It may have been deleted, or the phone may not have synced it yet.`);
    return row.payload as Payload;
  }
  wasLive(type: string, id: string): boolean { const row = this.find(type, id); return !!row && !row.deleted; }
  liveRecords(type: string): Payload[] { return this.rows.filter(r => r.recordType === type && !r.deleted).map(r => r.payload as Payload); }
  insert(type: string, payload: Payload, recordIdOverride?: string, assetIds: string[] = []) {
    const recordId = recordIdOverride ?? this.keyOf(type, payload);
    this.rows.push({ recordType: type as SyncRecord['recordType'], recordId, deleted: false, payload, assetIds, version: { counter: this.stamp, clientId: MCP_WRITER_CLIENT_ID } });
  }
  update(type: string, payload: Payload, patch: Record<string, unknown>) {
    const id = this.keyOf(type, payload);
    const row = this.find(type, id);
    if (!row || row.deleted) throw new ToolFailure('The record being updated no longer exists.');
    row.payload = { ...(row.payload as Payload), ...patch };
    row.version = { counter: this.stamp, clientId: MCP_WRITER_CLIENT_ID };
  }
  tombstone(type: string, payload: Payload) {
    const id = this.keyOf(type, payload);
    const row = this.find(type, id);
    if (!row || row.deleted) return;
    row.deleted = true; row.payload = null; row.assetIds = [];
    row.version = { counter: this.stamp, clientId: MCP_WRITER_CLIENT_ID };
  }
}

export class PrivateLibraryService {
  private grants: McpGrants; private snapshots: SnapshotReader;
  private writer: SnapshotReadWriter | undefined; private writesEnabled: boolean;
  private photos: McpPhotoStore | undefined; private benchmarks: McpBenchmarkSource | undefined; private community: McpCommunitySource | undefined;
  private clock: () => number;
  constructor(grants: McpGrants, snapshots: SnapshotReader,
    options: { writer?: SnapshotReadWriter; writesEnabled?: boolean; clock?: () => number; photos?: McpPhotoStore; benchmarks?: McpBenchmarkSource; community?: McpCommunitySource } = {}) {
    this.grants = grants; this.snapshots = snapshots;
    this.writer = options.writer; this.writesEnabled = options.writesEnabled === true;
    this.photos = options.photos; this.benchmarks = options.benchmarks; this.community = options.community;
    this.clock = options.clock ?? Date.now;
  }
  private requirePhotos(): McpPhotoStore {
    if (!this.photos) throw new ToolFailure('Photo storage is not available on this server.');
    if (!this.canWrite) throw new ToolFailure('Writes are not enabled on this server yet. Reads are unaffected.');
    return this.photos;
  }
  private get canWrite(): boolean { return this.writer !== undefined && this.writesEnabled; }

  /** The tool catalog for this slice. Later slices register their tools
   * here too — one catalog, so tools/list can never drift from what the
   * handlers actually serve. */
  tools(): ToolDefinition[] {
    return [
      {
        name: 'get_connection_status', title: 'Connection status',
        description: 'Reports this AI connection: which PitTech account it belongs to, what it is allowed to do, and when it expires. Use first when a request fails so you can tell the user exactly what to fix.',
        inputSchema: { ...NO_ARGS_SCHEMA }, scopes: ['library:read'],
        handler: async principal => this.connectionStatus(principal),
      },
      {
        name: 'get_capabilities', title: 'PitTech capabilities',
        description: 'Lists what PitTech can do through this connection: the available tools, the data areas they cover, and the hard limits (no publishing, no grill control, no billing or account changes).',
        inputSchema: { ...NO_ARGS_SCHEMA }, scopes: ['library:read'],
        handler: async principal => this.capabilities(principal),
      },
      {
        name: 'list_meals', title: 'List meals',
        description: 'Lists the user\'s meals (cooks), newest first, with status, start time, smoker and dish count. Filter with status when the user asks about e.g. completed or in-progress cooks.',
        inputSchema: { type: 'object', properties: { status: { type: 'string', description: 'Only meals with this exact status.' }, ...PAGE_ARGS }, additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.listMeals(principal, args),
      },
      {
        name: 'get_meal', title: 'Get a meal',
        description: 'Full detail for one meal: the cook record, its dishes with ingredients, targets and results, lifecycle, and note/photo counts. Use list_notes and the photo tools for the entries themselves.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' } }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.getMeal(principal, args),
      },
      {
        name: 'list_notes', title: 'List cook notes',
        description: 'Lists the log notes/events recorded during one meal, newest first.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, ...PAGE_ARGS }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.listNotes(principal, args),
      },
      {
        name: 'get_note', title: 'Get a note',
        description: 'Full detail for one log note/event by id.',
        inputSchema: { type: 'object', properties: { noteId: { type: 'string' } }, required: ['noteId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.getNote(principal, args),
      },
      ...(this.canWrite ? ([
      {
        name: 'create_note', title: 'Add a cook note',
        description: 'Adds a note to a meal\'s log. Notes are stamped as written by this connection (source: mcp). occurredAtUtcMillis defaults to now.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, title: { type: 'string' }, details: { type: 'string' }, eventType: { type: 'string', description: 'Defaults to "note".' }, dishId: { type: 'string' }, occurredAtUtcMillis: { type: 'number' }, timeZoneId: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId', 'title'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.createNote(principal, args),
      },
      {
        name: 'update_note', title: 'Update a note',
        description: 'Changes a log note\'s title, details or type.',
        inputSchema: { type: 'object', properties: { noteId: { type: 'string' }, title: { type: 'string' }, details: { type: 'string' }, eventType: { type: 'string' }, ...WRITE_ARGS }, required: ['noteId'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.updateNote(principal, args),
      },
      {
        name: 'delete_note', title: 'Delete a note',
        description: 'Deletes one log note (and its reading links, if any).',
        inputSchema: { type: 'object', properties: { noteId: { type: 'string' }, ...WRITE_ARGS }, required: ['noteId'], additionalProperties: false },
        scopes: ['library:delete'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.deleteNote(principal, args),
      },
      ] as ToolDefinition[]) : []),
      {
        name: 'list_companions', title: 'List companion documents',
        description: 'Lists companion documents (plans, playbooks, checklists, equipment and more), optionally filtered by kind or meal.',
        inputSchema: { type: 'object', properties: { kind: { type: 'string' }, mealId: { type: 'string' } }, additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.listCompanions(principal, args),
      },
      {
        name: 'get_companion', title: 'Get a companion document',
        description: 'Reads one companion document in full.',
        inputSchema: { type: 'object', properties: { companionId: { type: 'string' } }, required: ['companionId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.getCompanion(principal, args),
      },
      {
        name: 'list_reminder_rules', title: 'List reminder rules',
        description: 'Lists reminder rules (optionally for one meal) with their trigger configuration. Rules sync to the devices; activation on a device is the user\'s there, never a synced flag.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' } }, additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.listReminderRules(principal, args),
      },
      {
        name: 'list_photos', title: 'List photos',
        description: 'Lists the photos in the library (optionally for one meal) with captions and timestamps. Use get_photo to actually see an image.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' } }, additionalProperties: false },
        scopes: ['media:read'],
        handler: async (principal, args) => this.listPhotos(principal, args),
      },
      {
        name: 'get_photo', title: 'View a photo',
        description: 'Returns the actual image for a library photo so you can look at it.',
        inputSchema: { type: 'object', properties: { photoId: { type: 'string' } }, required: ['photoId'], additionalProperties: false },
        scopes: ['media:read'],
        handler: async (principal, args) => this.getPhoto(principal, args),
      },
      {
        name: 'search_recipes', title: 'Search recipes',
        description: 'Searches the user\'s recipe library by name, description and tags. Empty query lists recipes, most recently updated first. Archived recipes are excluded unless includeArchived is true.',
        inputSchema: { type: 'object', properties: { query: { type: 'string' }, includeArchived: { type: 'boolean' }, ...PAGE_ARGS }, additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.searchRecipes(principal, args),
      },
      {
        name: 'get_recipe', title: 'Get a recipe',
        description: 'Full detail for one recipe, including the current revision\'s full content snapshot.',
        inputSchema: { type: 'object', properties: { recipeId: { type: 'string' } }, required: ['recipeId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.getRecipe(principal, args),
      },
      {
        name: 'list_recipe_revisions', title: 'List recipe revisions',
        description: 'Revision history for one recipe, newest revision first, marking which revision is current.',
        inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, ...PAGE_ARGS }, required: ['recipeId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.listRecipeRevisions(principal, args),
      },
      ...(this.canWrite ? ([
      {
        name: 'create_meal', title: 'Create a meal',
        description: 'Starts a new meal (cook) in the user\'s library, optionally with its dishes. The meal starts active now. Returns the new mealId and a durable receipt. With an idempotencyKey, the key IS the operation: a retry returns the original receipt (marked replayed) without applying anything again, and the meal id is derived from the key so it can always be found. Use a fresh key for a new meal.',
        inputSchema: { type: 'object', properties: {
          title: { type: 'string' }, smokerName: { type: 'string' }, notes: { type: 'string' },
          fuelType: { type: 'string' }, woodOrPelletBlend: { type: 'string' }, timeZoneId: { type: 'string', description: 'IANA time zone for the start time; defaults to UTC.' },
          dishes: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, foodType: { type: 'string' }, cut: { type: 'string' }, weightValue: { type: 'number' }, weightUnit: { type: 'string' }, pieceCount: { type: 'number' }, startingCondition: { type: 'string' }, boneIn: { type: 'boolean' }, placement: { type: 'string' }, prepNotes: { type: 'string' }, gradeOrSource: { type: 'string' }, thicknessNotes: { type: 'string' } }, required: ['name', 'foodType'], additionalProperties: false } },
          ...WRITE_ARGS }, required: ['title'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.createMeal(principal, args),
      },
      {
        name: 'update_meal', title: 'Update a meal',
        description: 'Changes a meal\'s descriptive fields (title, smoker, notes, fuel, weather notes). Status changes go through transition_meal.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, title: { type: 'string' }, smokerName: { type: 'string' }, notes: { type: 'string' }, fuelType: { type: 'string' }, woodOrPelletBlend: { type: 'string' }, weatherNotes: { type: 'string' }, windNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.updateMeal(principal, args),
      },
      {
        name: 'transition_meal', title: 'Change a meal\'s status',
        description: 'Moves a meal between active, paused and completed. Completing stamps the end time. Completed meals cannot transition further.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, toStatus: { type: 'string', enum: ['active', 'paused', 'completed'] }, ...WRITE_ARGS }, required: ['mealId', 'toStatus'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.transitionMeal(principal, args),
      },
      {
        name: 'add_dish', title: 'Add a dish to a meal',
        description: 'Adds a dish to an existing meal.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, name: { type: 'string' }, foodType: { type: 'string' }, cut: { type: 'string' }, weightValue: { type: 'number' }, weightUnit: { type: 'string' }, pieceCount: { type: 'number' }, startingCondition: { type: 'string' }, boneIn: { type: 'boolean' }, placement: { type: 'string' }, prepNotes: { type: 'string' }, gradeOrSource: { type: 'string' }, thicknessNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId', 'name', 'foodType'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.addDish(principal, args),
      },
      {
        name: 'update_dish', title: 'Update a dish',
        description: 'Changes a dish\'s descriptive fields.',
        inputSchema: { type: 'object', properties: { dishId: { type: 'string' }, name: { type: 'string' }, foodType: { type: 'string' }, cut: { type: 'string' }, weightValue: { type: 'number' }, weightUnit: { type: 'string' }, pieceCount: { type: 'number' }, startingCondition: { type: 'string' }, boneIn: { type: 'boolean' }, placement: { type: 'string' }, prepNotes: { type: 'string' }, gradeOrSource: { type: 'string' }, thicknessNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['dishId'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.updateDish(principal, args),
      },
      {
        name: 'delete_meal', title: 'Delete a meal',
        description: 'Deletes a meal and everything recorded under it (dishes, ingredients, targets, results, notes, readings, photos metadata, lifecycle). This is the synced-library delete: it propagates to the user\'s devices on their next sync.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:delete'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.deleteMeal(principal, args),
      },
      {
        name: 'duplicate_meal_setup', title: 'Duplicate a meal setup',
        description: 'Starts a new meal from an existing meal\'s setup (smoker, dishes, ingredients, targets) with fresh identities. History, readings, results and photos are NOT copied.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, title: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:write'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.duplicateMealSetup(principal, args),
      },
      {
        name: 'remove_dish', title: 'Remove a dish',
        description: 'Removes one dish from its meal, along with its ingredients, targets and results.',
        inputSchema: { type: 'object', properties: { dishId: { type: 'string' }, ...WRITE_ARGS }, required: ['dishId'], additionalProperties: false },
        scopes: ['library:delete'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.removeDish(principal, args),
      },
        {
          name: 'create_recipe', title: 'Create a recipe',
          description: 'Creates a saved recipe with its first revision. Content lives in the revision snapshot, exactly as the app writes it: dish (with ingredients), instructions and targets.',
          inputSchema: { type: 'object', properties: { name: { type: 'string' }, description: { type: 'string' }, tags: { type: 'array', items: { type: 'string' } }, dish: { type: 'object' }, instructions: { type: 'array', items: { type: 'object' } }, targets: { type: 'array', items: { type: 'object' } }, servings: { type: 'string' }, preferredPitTemperatureF: { type: 'number' }, resultNotes: { type: 'string' }, changeNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['name'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.createRecipe(principal, args),
        },
        {
          name: 'update_recipe', title: 'Update a recipe',
          description: 'Saves a NEW revision of a recipe — revisions are immutable, history is never rewritten. Parts you omit keep their current values; observations recorded from real cooks are always preserved.',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, name: { type: 'string' }, description: { type: 'string' }, tags: { type: 'array', items: { type: 'string' } }, dish: { type: 'object' }, instructions: { type: 'array', items: { type: 'object' } }, targets: { type: 'array', items: { type: 'object' } }, servings: { type: 'string' }, preferredPitTemperatureF: { type: 'number' }, resultNotes: { type: 'string' }, changeNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['recipeId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.updateRecipe(principal, args),
        },
        {
          name: 'archive_recipe', title: 'Archive a recipe',
          description: 'Archives a recipe (hidden from everyday lists). Nothing is deleted; restore_recipe brings it back.',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, ...WRITE_ARGS }, required: ['recipeId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.archiveRecipe(principal, args),
        },
        {
          name: 'restore_recipe', title: 'Restore a recipe',
          description: 'Brings an archived recipe back to the everyday lists.',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, ...WRITE_ARGS }, required: ['recipeId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.restoreRecipe(principal, args),
        },
        {
          name: 'set_recipe_favorite', title: 'Favorite or unfavorite a recipe',
          description: 'Marks a recipe as a favorite (or clears the mark).',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, favorite: { type: 'boolean' }, ...WRITE_ARGS }, required: ['recipeId', 'favorite'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.setRecipeFavorite(principal, args),
        },
        {
          name: 'duplicate_recipe', title: 'Duplicate a recipe',
          description: 'Copies a recipe with its full revision history under fresh identities, so a variant can evolve without touching the original.',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, name: { type: 'string' }, ...WRITE_ARGS }, required: ['recipeId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.duplicateRecipe(principal, args),
        },
        {
          name: 'save_dish_as_recipe', title: 'Save a dish as a recipe',
          description: 'Saves one dish from a cook as a standalone recipe (its ingredients and targets become the recipe), linked back to its source dish.',
          inputSchema: { type: 'object', properties: { dishId: { type: 'string' }, name: { type: 'string' }, changeNotes: { type: 'string' }, ...WRITE_ARGS }, required: ['dishId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.saveDishAsRecipe(principal, args),
        },
        {
          name: 'compose_meal', title: 'Compose a meal',
          description: 'Creates a meal from a mix of saved recipes and plain dish descriptions. Recipe dishes bring their ingredients, targets and a source link to the exact revision used. Nothing is scaled or silently saved as new recipes.',
          inputSchema: { type: 'object', properties: { title: { type: 'string' }, smokerName: { type: 'string' }, dishes: { type: 'array', items: { type: 'object' } }, ...WRITE_ARGS }, required: ['dishes'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.composeMeal(principal, args),
        },
        {
          name: 'add_recipe_to_meal', title: 'Add a recipe to a meal',
          description: 'Adds one dish from a saved recipe to an existing (not completed) meal, with its ingredients, targets and source link.',
          inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, recipeId: { type: 'string' }, name: { type: 'string' }, ...WRITE_ARGS }, required: ['mealId', 'recipeId'], additionalProperties: false },
          scopes: ['library:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.addRecipeToMeal(principal, args),
        },
        {
          name: 'delete_recipe', title: 'Delete a recipe',
          description: 'Deletes a recipe and all of its revisions.',
          inputSchema: { type: 'object', properties: { recipeId: { type: 'string' }, ...WRITE_ARGS }, required: ['recipeId'], additionalProperties: false },
          scopes: ['library:delete'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.deleteRecipe(principal, args),
        },
        {
          name: 'begin_photo_upload', title: 'Begin a photo upload',
          description: 'Starts a chunked photo upload (1 MiB chunks). Declare the total bytes, mimeType (jpeg/png/webp) and the sha256 of the whole image; you get an assetId to upload chunks against.',
          inputSchema: { type: 'object', properties: { bytes: { type: 'number' }, mimeType: { type: 'string' }, sha256: { type: 'string' }, assetId: { type: 'string' } }, required: ['bytes', 'mimeType', 'sha256'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.beginPhotoUpload(principal, args),
        },
        {
          name: 'upload_photo_chunk', title: 'Upload a photo chunk',
          description: 'Uploads one chunk (base64, up to 1 MiB decoded) of an in-progress photo upload.',
          inputSchema: { type: 'object', properties: { assetId: { type: 'string' }, index: { type: 'number' }, dataBase64: { type: 'string' } }, required: ['assetId', 'index', 'dataBase64'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.uploadPhotoChunk(principal, args),
        },
        {
          name: 'get_photo_upload_status', title: 'Photo upload status',
          description: 'Shows which chunks of an upload have arrived and whether it is complete.',
          inputSchema: { type: 'object', properties: { assetId: { type: 'string' } }, required: ['assetId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.photoUploadStatus(principal, args),
        },
        {
          name: 'complete_photo_upload', title: 'Complete a photo upload',
          description: 'Finishes an upload: the server verifies the image (hash, format, dimensions) and stores it. Attach it to a meal afterwards with attach_photo.',
          inputSchema: { type: 'object', properties: { assetId: { type: 'string' } }, required: ['assetId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.completePhotoUpload(principal, args),
        },
        {
          name: 'cancel_photo_upload', title: 'Cancel a photo upload',
          description: 'Abandons an in-progress (or unattached) upload and discards its chunks.',
          inputSchema: { type: 'object', properties: { assetId: { type: 'string' } }, required: ['assetId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.cancelPhotoUpload(principal, args),
        },
        {
          name: 'attach_photo', title: 'Attach a photo to a meal',
          description: 'Adds a completed upload to a meal (optionally one of its dishes) with an optional caption, so it appears in the library and on the phones.',
          inputSchema: { type: 'object', properties: { assetId: { type: 'string' }, mealId: { type: 'string' }, dishId: { type: 'string' }, caption: { type: 'string' }, capturedAtUtcMillis: { type: 'number' }, ...WRITE_ARGS }, required: ['assetId', 'mealId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.attachPhoto(principal, args),
        },
        {
          name: 'update_photo', title: 'Update a photo',
          description: 'Changes a photo\'s caption.',
          inputSchema: { type: 'object', properties: { photoId: { type: 'string' }, caption: { type: 'string' }, ...WRITE_ARGS }, required: ['photoId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.updatePhoto(principal, args),
        },
        {
          name: 'remove_photo', title: 'Remove a photo',
          description: 'Removes a photo from the library. The meal and its other content are unchanged.',
          inputSchema: { type: 'object', properties: { photoId: { type: 'string' }, ...WRITE_ARGS }, required: ['photoId'], additionalProperties: false },
          scopes: ['media:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.removePhoto(principal, args),
        },
        ...(this.community ? [{
          name: 'save_community_recipe', title: 'Save a community recipe',
          description: 'Saves a private copy of a public community recipe into the user\'s library, pinned to the exact public revision with its credit kept. The public item is untouched.',
          inputSchema: { type: 'object', properties: { itemId: { type: 'string' }, name: { type: 'string' }, ...WRITE_ARGS }, required: ['itemId'], additionalProperties: false },
          scopes: ['library:write'] as const,
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.saveCommunityRecipe(principal, args),
        }] as ToolDefinition[] : []),
        {
          name: 'upsert_companion', title: 'Create or update a companion document',
          description: 'Creates or updates a companion document (plan, playbook, checklist, equipment, preset and similar kinds). The payload is the document for its kind; the library validates its shape on save. Planning kinds need the planning access; checklist/equipment/preset kinds need library write.',
          inputSchema: { type: 'object', properties: { companionId: { type: 'string' }, kind: { type: 'string' }, title: { type: 'string' }, mealId: { type: 'string' }, payload: { type: 'object' }, ...WRITE_ARGS }, required: ['kind', 'payload'], additionalProperties: false },
          scopes: ['library:write', 'planning:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.upsertCompanion(principal, args),
        },
        {
          name: 'delete_companion', title: 'Delete a companion document',
          description: 'Deletes one companion document.',
          inputSchema: { type: 'object', properties: { companionId: { type: 'string' }, ...WRITE_ARGS }, required: ['companionId'], additionalProperties: false },
          scopes: ['library:delete', 'planning:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.deleteCompanion(principal, args),
        },
        {
          name: 'upsert_reminder_rule', title: 'Create or update a reminder rule',
          description: 'Creates or updates a reminder rule for a meal. triggerConfig.type must match kind. The rule syncs to the user\'s devices; turning it on there is always the user\'s action.',
          inputSchema: { type: 'object', properties: { ruleId: { type: 'string' }, mealId: { type: 'string' }, dishId: { type: 'string' }, kind: { type: 'string' }, title: { type: 'string' }, triggerConfig: { type: 'object' }, ...WRITE_ARGS }, required: ['mealId', 'kind', 'title', 'triggerConfig'], additionalProperties: false },
          scopes: ['planning:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.upsertReminderRule(principal, args),
        },
        {
          name: 'delete_reminder_rule', title: 'Delete a reminder rule',
          description: 'Deletes one reminder rule.',
          inputSchema: { type: 'object', properties: { ruleId: { type: 'string' }, ...WRITE_ARGS }, required: ['ruleId'], additionalProperties: false },
          scopes: ['planning:write'],
          handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.deleteReminderRule(principal, args),
        },
      ] as ToolDefinition[]) : []),
      ...(this.community ? [{
        name: 'search_community_recipes', title: 'Search community recipes',
        description: 'Searches public community recipes (read-only). Saving one keeps a private copy; nothing here can publish, like or comment.',
        inputSchema: { type: 'object', properties: { query: { type: 'string' }, food: { type: 'string' }, sort: { type: 'string' }, cursor: { type: 'string' } }, additionalProperties: false },
        scopes: ['community:read'] as const,
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.searchCommunity(principal, args),
      }, {
        name: 'get_community_recipe', title: 'Get a community recipe',
        description: 'Reads one public community recipe in full, with its credit.',
        inputSchema: { type: 'object', properties: { itemId: { type: 'string' } }, required: ['itemId'], additionalProperties: false },
        scopes: ['community:read'] as const,
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.getCommunityRecipe(principal, args),
      }] as ToolDefinition[] : []),
      {
        name: 'get_temperature_history', title: 'Temperature history',
        description: 'Temperature readings for a meal. Default mode is reduced: 15-minute interval statistics (min/max/average/samples) per dish and probe type. Use mode raw for individual readings (paged by limit).',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' }, dishId: { type: 'string' }, measurementType: { type: 'string' }, mode: { type: 'string', enum: ['reduced', 'raw'] }, limit: { type: 'number' } }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.temperatureHistory(principal, args),
      },
      {
        name: 'get_cook_summary', title: 'Cook summary',
        description: 'A computed summary of one meal: duration, per-dish peak temperatures, results, note/photo/reading counts.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' } }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.cookSummary(principal, args),
      },
      {
        name: 'compare_cooks', title: 'Compare cooks',
        description: 'Side-by-side summaries of 2 to 4 meals.',
        inputSchema: { type: 'object', properties: { mealIds: { type: 'array', items: { type: 'string' } } }, required: ['mealIds'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.compareCooks(principal, args),
      },
      {
        name: 'get_personal_insights', title: 'Personal insights',
        description: 'Aggregates across the whole library: totals, average completed-cook duration, most-cooked food types, top-rated dishes, meals by month. Sample counts are always included.',
        inputSchema: { ...NO_ARGS_SCHEMA },
        scopes: ['library:read'],
        handler: async principal => this.personalInsights(principal, {}),
      },
      {
        name: 'build_private_recap', title: 'Build a private recap',
        description: 'Builds a private, shareable-text recap of one meal from its summary. It stays in this conversation unless the user shares it.',
        inputSchema: { type: 'object', properties: { mealId: { type: 'string' } }, required: ['mealId'], additionalProperties: false },
        scopes: ['library:read'],
        handler: async (principal, args) => this.buildRecap(principal, args),
      },
      ...(this.benchmarks ? [{
        name: 'get_community_benchmarks', title: 'Community benchmarks',
        description: 'Anonymous community aggregates (cook durations, temperatures and outcomes by category) from the latest published figures. Optional bucket and metric filters.',
        inputSchema: { type: 'object', properties: { bucket: { type: 'string' }, metric: { type: 'string' } }, additionalProperties: false },
        scopes: ['community:read'] as const,
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.communityBenchmarks(principal, args),
      }] as ToolDefinition[] : []),
      {
        name: 'get_sync_status', title: 'Sync status',
        description: 'Reports the state of the user\'s synced library on the server: whether anything has synced yet, the snapshot version and time, and how many records of each kind it holds. Never claims live phone state — the phone may have newer unsynced changes.',
        inputSchema: { ...NO_ARGS_SCHEMA }, scopes: ['library:read'],
        handler: async principal => this.syncStatus(principal),
      },
    ];
  }

  /** Loads and indexes the owner's synced snapshot once per call. A
   * never-synced account is a ToolFailure with the fix, not an empty
   * library — the distinction the connection.context row demands. */
  private async loadLibrary(principal: McpPrincipal): Promise<LibraryData> {
    const { version, envelope } = await this.snapshots.read(principal.userId);
    if (version === 0 || !envelope) throw new ToolFailure('No synced library yet. Ask the user to open PitTech while signed in so the first sync can run, then try again.');
    const byType = new Map<string, Map<string, Payload>>();
    for (const record of envelope.records) {
      if (record.deleted) continue;
      let bucket = byType.get(record.recordType);
      if (!bucket) { bucket = new Map(); byType.set(record.recordType, bucket); }
      bucket.set(record.recordId, record.payload as Payload);
    }
    return { version, byType };
  }
  private records(data: LibraryData, type: string): Payload[] { return [...(data.byType.get(type)?.values() ?? [])]; }
  private record(data: LibraryData, type: string, id: unknown, label: string): Payload {
    if (typeof id !== 'string' || !id) throw new ToolFailure(`${label} id is required.`);
    const found = data.byType.get(type)?.get(id);
    if (!found) throw new ToolFailure(`No ${label.toLowerCase()} with id ${id} exists in the synced library. It may have been deleted, or the phone may not have synced it yet.`);
    return found;
  }

  async listMeals(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const dishes = this.records(data, 'dishes');
    const dishCounts = new Map<string, number>();
    for (const dish of dishes) { const cookId = str(dish.cookId); if (cookId) dishCounts.set(cookId, (dishCounts.get(cookId) ?? 0) + 1); }
    let meals = this.records(data, 'cooks');
    if (args.status !== undefined) {
      if (typeof args.status !== 'string') throw new ToolFailure('status must be a string.');
      meals = meals.filter(meal => meal.status === args.status);
    }
    meals.sort((a, b) => Number(b.startedAtUtcMillis ?? 0) - Number(a.startedAtUtcMillis ?? 0));
    const paged = page(meals, args);
    return {
      snapshotVersion: data.version,
      meals: paged.items.map(meal => ({
        id: meal.id, title: meal.title, status: meal.status,
        startedAt: iso(meal.startedAtUtcMillis), endedAt: iso(meal.endedAtUtcMillis),
        smokerName: meal.smokerName ?? null, dishCount: dishCounts.get(str(meal.id) ?? '') ?? 0,
      })),
      total: paged.total, nextCursor: paged.nextCursor,
    };
  }

  async getMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const meal = this.record(data, 'cooks', args.mealId, 'Meal');
    const mealId = str(meal.id)!;
    const byCook = (type: string) => this.records(data, type).filter(row => row.cookId === mealId);
    const dishes = byCook('dishes').map(dish => {
      const dishId = str(dish.id);
      return {
        ...dish,
        ingredients: this.records(data, 'ingredients').filter(row => row.dishId === dishId),
        targets: this.records(data, 'targets').filter(row => row.dishId === dishId),
        results: this.records(data, 'results').filter(row => row.dishId === dishId),
      };
    });
    const lifecycle = this.records(data, 'lifecycles').find(row => row.cookId === mealId) ?? null;
    return {
      snapshotVersion: data.version, meal, dishes, lifecycle,
      targets: this.records(data, 'targets').filter(row => row.cookId === mealId && row.dishId == null),
      results: this.records(data, 'results').filter(row => row.cookId === mealId && row.dishId == null),
      noteCount: byCook('events').length, photoCount: byCook('photos').length,
    };
  }

  async listNotes(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const meal = this.record(data, 'cooks', args.mealId, 'Meal');
    const notes = this.records(data, 'events').filter(row => row.cookId === meal.id)
      .sort((a, b) => Number(b.occurredAtUtcMillis ?? 0) - Number(a.occurredAtUtcMillis ?? 0));
    const paged = page(notes, args);
    return {
      snapshotVersion: data.version, mealId: meal.id,
      notes: paged.items.map(note => ({
        id: note.id, eventType: note.eventType, title: note.title, details: note.details ?? null,
        occurredAt: iso(note.occurredAtUtcMillis), dishId: note.dishId ?? null,
      })),
      total: paged.total, nextCursor: paged.nextCursor,
    };
  }

  async getNote(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const note = this.record(data, 'events', args.noteId, 'Note');
    return { snapshotVersion: data.version, note };
  }

  async searchRecipes(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const query = args.query === undefined ? '' : args.query;
    if (typeof query !== 'string') throw new ToolFailure('query must be a string.');
    const includeArchived = args.includeArchived === true;
    const needle = query.trim().toLowerCase();
    let recipes = this.records(data, 'recipes').filter(recipe => includeArchived || recipe.archivedAtUtcMillis == null);
    if (needle) recipes = recipes.filter(recipe =>
      str(recipe.name)?.toLowerCase().includes(needle) || str(recipe.description)?.toLowerCase().includes(needle)
      || str(recipe.tagsJson)?.toLowerCase().includes(needle));
    recipes.sort((a, b) => Number(b.updatedAtUtcMillis ?? 0) - Number(a.updatedAtUtcMillis ?? 0));
    const paged = page(recipes, args);
    return {
      snapshotVersion: data.version,
      recipes: paged.items.map(recipe => ({
        id: recipe.id, name: recipe.name, description: recipe.description,
        favorite: recipe.favorite === true, archived: recipe.archivedAtUtcMillis != null,
        updatedAt: iso(recipe.updatedAtUtcMillis), currentRevisionId: recipe.currentRevisionId,
      })),
      total: paged.total, nextCursor: paged.nextCursor,
    };
  }

  async getRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const recipe = this.record(data, 'recipes', args.recipeId, 'Recipe');
    const revision = typeof recipe.currentRevisionId === 'string' ? data.byType.get('recipeRevisions')?.get(recipe.currentRevisionId) : undefined;
    let currentRevision: Payload | null = null;
    if (revision) {
      let snapshot: unknown = revision.snapshotJson;
      if (typeof snapshot === 'string') { try { snapshot = JSON.parse(snapshot); } catch { /* keep the raw string */ } }
      currentRevision = { id: revision.id, revisionNumber: revision.revisionNumber, createdAt: iso(revision.createdAtUtcMillis), changeNotes: revision.changeNotes, snapshot };
    }
    return { snapshotVersion: data.version, recipe, currentRevision };
  }

  async listRecipeRevisions(principal: McpPrincipal, args: Record<string, unknown>) {
    const data = await this.loadLibrary(principal);
    const recipe = this.record(data, 'recipes', args.recipeId, 'Recipe');
    const revisions = this.records(data, 'recipeRevisions').filter(row => row.recipeId === recipe.id)
      .sort((a, b) => Number(b.revisionNumber ?? 0) - Number(a.revisionNumber ?? 0));
    const paged = page(revisions, args);
    return {
      snapshotVersion: data.version, recipeId: recipe.id,
      revisions: paged.items.map(revision => ({
        id: revision.id, revisionNumber: revision.revisionNumber, parentRevisionId: revision.parentRevisionId ?? null,
        createdAt: iso(revision.createdAtUtcMillis), changeNotes: revision.changeNotes,
        isCurrent: revision.id === recipe.currentRevisionId,
      })),
      total: paged.total, nextCursor: paged.nextCursor,
    };
  }


  /** The command spine. One mutation = read the current snapshot, apply
   * a pure change to a working copy, stamp every touched record with a
   * fresh library-wide counter and the MCP writer identity, then commit
   * through put() whose baseVersion CAS and mutationId receipt make the
   * write atomic and idempotent. A version race is a ToolFailure that
   * tells the caller to re-read and retry — never a silent overwrite.
   * The registry's expected-revision rule is the optional
   * expectedSnapshotVersion argument, checked before any work. */
  private async mutate(principal: McpPrincipal, args: Record<string, unknown>,
    apply: (work: WorkingLibrary) => Record<string, unknown>,
    replaySummary?: (args: Record<string, unknown>) => Record<string, unknown>): Promise<Record<string, unknown>> {
    if (!this.canWrite || !this.writer) throw new ToolFailure('Writes are not enabled on this server yet. Reads are unaffected.');
    // Receipt-first: a keyed retry is answered from the durable receipt
    // alone — the change is never rebuilt against a moved library.
    if (typeof args.idempotencyKey === 'string' && /^[0-9a-fA-F-]{36}$/.test(args.idempotencyKey)) {
      const prior = await this.writer.receiptFor(principal.userId, args.idempotencyKey);
      if (prior) return { ...(replaySummary?.(args) ?? {}), replayed: true, receipt: { snapshotVersion: prior.version, mutationId: prior.mutationId } };
    }
    const { version, envelope } = await this.snapshots.read(principal.userId);
    if (args.expectedSnapshotVersion !== undefined) {
      if (typeof args.expectedSnapshotVersion !== 'number' || !Number.isSafeInteger(args.expectedSnapshotVersion)) throw new ToolFailure('expectedSnapshotVersion must be an integer snapshot version.');
      if (args.expectedSnapshotVersion !== version) throw new ToolFailure(`The library is at snapshot version ${version}, not ${args.expectedSnapshotVersion}. Re-read the current state before writing so this change is based on the latest library.`);
    }
    const mutationId = args.idempotencyKey === undefined ? randomUUID() : args.idempotencyKey;
    if (typeof mutationId !== 'string' || !/^[0-9a-fA-F-]{36}$/.test(mutationId)) throw new ToolFailure('idempotencyKey must be a UUID when provided.');
    const work = new WorkingLibrary(envelope, this.clock);
    const summary = apply(work);
    const next: SyncEnvelope = {
      contractVersion: 1, baseVersion: version, mutationId, clientId: MCP_WRITER_CLIENT_ID,
      records: work.records(), conflicts: envelope?.conflicts ?? [],
    };
    try {
      const receipt = await this.writer.put(principal.userId, next);
      return { ...summary, receipt: { snapshotVersion: receipt.version, mutationId: receipt.mutationId } };
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'sync_version_changed') throw new ToolFailure('The library changed while this write was being prepared (another device or connection synced). Re-read the current state and try again.');
      if (failure instanceof ApiError && failure.code === 'retry_content_changed') throw new ToolFailure('That idempotencyKey was already used for a different change. Use a new key for a new change, or repeat the original call exactly to get its receipt.');
      if (failure instanceof ApiError && failure.code === 'private_assets_unavailable') throw new ToolFailure('The change references a photo or file that is not available in the synced library.');
      if (failure instanceof ApiError) throw new ToolFailure(`The synced library rejected this change (${failure.code}). Re-read the current state and check the values.`);
      throw failure;
    }
  }

  /** Deterministic record ids for keyed calls: derived from the
   * idempotency key, so a retry (or a follow-up read) can always find
   * the record a keyed create produced, even if the receipt was lost. */
  private idFor(args: Record<string, unknown>, salt: string): string {
    const key = args.idempotencyKey;
    if (typeof key !== 'string') return randomUUID();
    const bytes = createHash('sha256').update(key + '|' + salt).digest();
    bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.toString('hex');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
  }
  private requireFields(args: Record<string, unknown>, allowed: readonly string[]) {
    for (const key of Object.keys(args)) if (!allowed.includes(key)) throw new ToolFailure(`Unknown field: ${key}. Allowed: ${allowed.join(', ')}.`);
  }
  private optString(args: Record<string, unknown>, field: string): string | undefined {
    const value = args[field];
    if (value === undefined || value === null) return undefined;
    if (typeof value !== 'string') throw new ToolFailure(`${field} must be a string.`);
    return value;
  }
  private optNumber(args: Record<string, unknown>, field: string): number | undefined {
    const value = args[field];
    if (value === undefined || value === null) return undefined;
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new ToolFailure(`${field} must be a number.`);
    return value;
  }

  async createMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['title', 'smokerName', 'notes', 'fuelType', 'woodOrPelletBlend', 'timeZoneId', 'dishes', 'idempotencyKey', 'expectedSnapshotVersion']);
    const title = this.optString(args, 'title');
    if (!title?.trim()) throw new ToolFailure('title is required.');
    const dishesArg = args.dishes === undefined ? [] : args.dishes;
    if (!Array.isArray(dishesArg) || dishesArg.length > 20) throw new ToolFailure('dishes must be an array of at most 20 dishes.');
    return this.mutate(principal, args, work => {
      const now = this.clock();
      const mealId = this.idFor(args, 'meal');
      work.insert('cooks', {
        id: mealId, title: title.trim(), status: 'active',
        startedAtUtcMillis: now, startedTimeZoneId: this.optString(args, 'timeZoneId') ?? 'UTC',
        endedAtUtcMillis: null,
        smokerName: this.optString(args, 'smokerName') ?? null,
        initialSetpointValue: null, initialSetpointUnit: null,
        notes: this.optString(args, 'notes') ?? null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
        fuelType: this.optString(args, 'fuelType') ?? null,
        woodOrPelletBlend: this.optString(args, 'woodOrPelletBlend') ?? null,
        outdoorTemperatureValue: null, outdoorTemperatureUnit: null,
        weatherNotes: null, windNotes: null,
      });
      work.insert('lifecycles', { cookId: mealId, timingMode: 'legacy_preserved' }, mealId);
      const dishIds: string[] = [];
      for (const raw of dishesArg) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new ToolFailure('each dish must be an object.');
        const dish = raw as Record<string, unknown>;
        this.requireFields(dish, ['name', 'foodType', 'cut', 'weightValue', 'weightUnit', 'pieceCount', 'startingCondition', 'boneIn', 'placement', 'prepNotes', 'gradeOrSource', 'thicknessNotes']);
        const name = this.optString(dish, 'name'); const foodType = this.optString(dish, 'foodType');
        if (!name?.trim() || !foodType?.trim()) throw new ToolFailure('each dish needs a name and a foodType.');
        const dishId = this.idFor(args, 'dish:' + dishIds.length); dishIds.push(dishId);
        work.insert('dishes', {
          id: dishId, cookId: mealId, name: name.trim(), foodType: foodType.trim(),
          cut: this.optString(dish, 'cut') ?? null,
          weightValue: this.optNumber(dish, 'weightValue') ?? null, weightUnit: this.optString(dish, 'weightUnit') ?? null,
          pieceCount: this.optNumber(dish, 'pieceCount') ?? null,
          startingCondition: this.optString(dish, 'startingCondition') ?? null,
          boneIn: typeof dish.boneIn === 'boolean' ? dish.boneIn : null,
          placement: this.optString(dish, 'placement') ?? null, prepNotes: this.optString(dish, 'prepNotes') ?? null,
          gradeOrSource: this.optString(dish, 'gradeOrSource') ?? null, thicknessNotes: this.optString(dish, 'thicknessNotes') ?? null,
          createdAtUtcMillis: now, updatedAtUtcMillis: now,
        });
      }
      return { mealId, dishIds, status: 'active' };
    }, args => ({ mealId: this.idFor(args, 'meal') }));
  }

  async updateMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'title', 'smokerName', 'notes', 'fuelType', 'woodOrPelletBlend', 'weatherNotes', 'windNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const patch: Record<string, unknown> = {};
      for (const field of ['title', 'smokerName', 'notes', 'fuelType', 'woodOrPelletBlend', 'weatherNotes', 'windNotes'] as const) {
        if (field in args) patch[field] = this.optString(args, field) ?? null;
      }
      if (typeof patch.title === 'string' && !patch.title.trim()) throw new ToolFailure('title cannot be empty.');
      if (!Object.keys(patch).length) throw new ToolFailure('Nothing to update: provide at least one field.');
      work.update('cooks', meal, { ...patch, updatedAtUtcMillis: this.clock() });
      return { mealId: meal.id };
    });
  }

  async transitionMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'toStatus', 'idempotencyKey', 'expectedSnapshotVersion']);
    const to = this.optString(args, 'toStatus');
    if (!to || !(COOK_STATUSES as readonly string[]).includes(to)) throw new ToolFailure(`toStatus must be one of: ${COOK_STATUSES.join(', ')}.`);
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const from = str(meal.status) ?? '';
      const allowed = COOK_TRANSITIONS[from] ?? [];
      if (!allowed.includes(to)) throw new ToolFailure(`A ${from || 'unknown'} meal cannot become ${to}. Allowed from ${from || 'unknown'}: ${allowed.length ? allowed.join(', ') : 'none — this meal is finished'}.`);
      const patch: Record<string, unknown> = { status: to, updatedAtUtcMillis: this.clock() };
      if (to === 'completed') patch.endedAtUtcMillis = this.clock();
      work.update('cooks', meal, patch);
      return { mealId: meal.id, from, to };
    });
  }

  async deleteMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const mealId = str(meal.id)!;
      let tombstoned = 0;
      const eventIds = new Set(work.liveRecords('events').filter(r => r.cookId === mealId).map(r => str(r.id)));
      const readingIds = new Set(work.liveRecords('readings').filter(r => r.cookId === mealId).map(r => str(r.id)));
      for (const type of ['dishes', 'ingredients', 'targets', 'targetHistory', 'results', 'events', 'readings', 'photos', 'devices', 'probes', 'reminderRules'] as const) {
        for (const row of work.liveRecords(type).filter(r => r.cookId === mealId)) { work.tombstone(type, row); tombstoned++; }
      }
      for (const link of work.liveRecords('logReadingLinks')) {
        if (eventIds.has(str(link.eventId) ?? '') || readingIds.has(str(link.readingId) ?? '')) { work.tombstone('logReadingLinks', link); tombstoned++; }
      }
      for (const lifecycle of work.liveRecords('lifecycles').filter(r => r.cookId === mealId)) { work.tombstone('lifecycles', lifecycle); tombstoned++; }
      for (const companion of work.liveRecords('companionRecords').filter(r => r.cookId === mealId)) { work.tombstone('companionRecords', companion); tombstoned++; }
      work.tombstone('cooks', meal); tombstoned++;
      return { mealId, tombstonedRecords: tombstoned };
    });
  }

  async addDish(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'name', 'foodType', 'cut', 'weightValue', 'weightUnit', 'pieceCount', 'startingCondition', 'boneIn', 'placement', 'prepNotes', 'gradeOrSource', 'thicknessNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    const name = this.optString(args, 'name'); const foodType = this.optString(args, 'foodType');
    if (!name?.trim() || !foodType?.trim()) throw new ToolFailure('name and foodType are required.');
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const now = this.clock(); const dishId = this.idFor(args, 'dish');
      work.insert('dishes', {
        id: dishId, cookId: meal.id, name: name.trim(), foodType: foodType.trim(),
        cut: this.optString(args, 'cut') ?? null, weightValue: this.optNumber(args, 'weightValue') ?? null,
        weightUnit: this.optString(args, 'weightUnit') ?? null, pieceCount: this.optNumber(args, 'pieceCount') ?? null,
        startingCondition: this.optString(args, 'startingCondition') ?? null,
        boneIn: typeof args.boneIn === 'boolean' ? args.boneIn : null,
        placement: this.optString(args, 'placement') ?? null, prepNotes: this.optString(args, 'prepNotes') ?? null,
        gradeOrSource: this.optString(args, 'gradeOrSource') ?? null, thicknessNotes: this.optString(args, 'thicknessNotes') ?? null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
      });
      return { mealId: meal.id, dishId };
    });
  }

  async updateDish(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['dishId', 'name', 'foodType', 'cut', 'weightValue', 'weightUnit', 'pieceCount', 'startingCondition', 'boneIn', 'placement', 'prepNotes', 'gradeOrSource', 'thicknessNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const dish = work.live('dishes', args.dishId, 'Dish');
      const patch: Record<string, unknown> = {};
      for (const field of ['name', 'foodType', 'cut', 'weightUnit', 'startingCondition', 'placement', 'prepNotes', 'gradeOrSource', 'thicknessNotes'] as const) {
        if (field in args) patch[field] = this.optString(args, field) ?? null;
      }
      for (const field of ['weightValue', 'pieceCount'] as const) if (field in args) patch[field] = this.optNumber(args, field) ?? null;
      if ('boneIn' in args) patch.boneIn = typeof args.boneIn === 'boolean' ? args.boneIn : null;
      if (!Object.keys(patch).length) throw new ToolFailure('Nothing to update: provide at least one field.');
      work.update('dishes', dish, { ...patch, updatedAtUtcMillis: this.clock() });
      return { dishId: dish.id };
    });
  }

  async removeDish(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['dishId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const dish = work.live('dishes', args.dishId, 'Dish');
      const dishId = str(dish.id)!;
      let tombstoned = 0;
      for (const type of ['ingredients', 'targets', 'targetHistory', 'results'] as const) {
        for (const row of work.liveRecords(type).filter(r => r.dishId === dishId)) { work.tombstone(type, row); tombstoned++; }
      }
      work.tombstone('dishes', dish); tombstoned++;
      return { dishId, tombstonedRecords: tombstoned };
    });
  }


  async createNote(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'title', 'details', 'eventType', 'dishId', 'occurredAtUtcMillis', 'timeZoneId', 'idempotencyKey', 'expectedSnapshotVersion']);
    const title = this.optString(args, 'title');
    if (!title?.trim()) throw new ToolFailure('title is required.');
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      let dishId: string | null = null;
      if (args.dishId !== undefined && args.dishId !== null) {
        const dish = work.live('dishes', args.dishId, 'Dish');
        if (dish.cookId !== meal.id) throw new ToolFailure('That dish belongs to a different meal.');
        dishId = str(dish.id)!;
      }
      const now = this.clock();
      const occurred = this.optNumber(args, 'occurredAtUtcMillis') ?? now;
      const noteId = this.idFor(args, 'note');
      work.insert('events', {
        id: noteId, cookId: meal.id, dishId,
        eventType: this.optString(args, 'eventType') ?? 'note',
        title: title.trim(), details: this.optString(args, 'details') ?? null,
        occurredAtUtcMillis: occurred, recordedAtUtcMillis: now,
        timeZoneId: this.optString(args, 'timeZoneId') ?? 'UTC',
        source: 'mcp', sourceDeviceId: null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
        temperatureContextJson: null, noteMetadataJson: null,
      });
      return { mealId: meal.id, noteId };
    }, args => ({ noteId: this.idFor(args, 'note') }));
  }

  async updateNote(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['noteId', 'title', 'details', 'eventType', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const note = work.live('events', args.noteId, 'Note');
      const patch: Record<string, unknown> = {};
      for (const field of ['title', 'details', 'eventType'] as const) if (field in args) patch[field] = this.optString(args, field) ?? null;
      if (typeof patch.title === 'string' && !patch.title.trim()) throw new ToolFailure('title cannot be empty.');
      if (!Object.keys(patch).length) throw new ToolFailure('Nothing to update: provide at least one field.');
      work.update('events', note, { ...patch, updatedAtUtcMillis: this.clock() });
      return { noteId: note.id };
    });
  }

  async deleteNote(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['noteId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const note = work.live('events', args.noteId, 'Note');
      const noteId = str(note.id)!;
      let tombstoned = 0;
      for (const link of work.liveRecords('logReadingLinks').filter(r => r.eventId === noteId)) { work.tombstone('logReadingLinks', link); tombstoned++; }
      work.tombstone('events', note); tombstoned++;
      return { noteId, tombstonedRecords: tombstoned };
    });
  }


  /** Builds a codec-exact recipe snapshot document (the app's
   * RecipeSnapshotCodec shape) from model-friendly parts. On update,
   * parts the caller omits inherit the current snapshot's values —
   * content is never silently dropped by a partial edit. */
  private recipeSnapshot(args: Record<string, unknown>, base?: Record<string, unknown>): Record<string, unknown> {
    const name = this.optString(args, 'name') ?? str(base?.name);
    if (!name?.trim()) throw new ToolFailure('name is required.');
    const baseDraft = (typeof base?.draft === 'object' && base?.draft !== null ? base.draft : {}) as Record<string, unknown>;
    const baseDish = (Array.isArray(baseDraft.dishes) ? baseDraft.dishes[0] ?? {} : {}) as Record<string, unknown>;
    const dishArg = (typeof args.dish === 'object' && args.dish !== null ? args.dish : undefined) as Record<string, unknown> | undefined;
    const dishName = dishArg ? this.optString(dishArg, 'name') ?? name : str(baseDish.name) ?? name;
    const ingredients = dishArg?.ingredients !== undefined ? dishArg.ingredients : baseDish.ingredients;
    if (ingredients !== undefined && !Array.isArray(ingredients)) throw new ToolFailure('dish.ingredients must be an array.');
    const dish = {
      name: dishName,
      foodType: dishArg ? this.optString(dishArg, 'foodType') ?? 'other' : str(baseDish.foodType) ?? 'other',
      cut: dishArg ? this.optString(dishArg, 'cut') ?? '' : str(baseDish.cut) ?? '',
      weight: dishArg ? this.optString(dishArg, 'weightText') ?? '' : str(baseDish.weight) ?? '',
      weightUnit: dishArg ? this.optString(dishArg, 'weightUnit') ?? 'lb' : str(baseDish.weightUnit) ?? 'lb',
      startingCondition: dishArg ? (this.optString(dishArg, 'startingCondition') ?? null) : (baseDish.startingCondition ?? null),
      boneIn: dishArg ? (typeof dishArg.boneIn === 'boolean' ? dishArg.boneIn : null) : (baseDish.boneIn ?? null),
      placement: dishArg ? this.optString(dishArg, 'placement') ?? '' : str(baseDish.placement) ?? '',
      grade: dishArg ? this.optString(dishArg, 'gradeOrSource') ?? '' : str(baseDish.grade) ?? '',
      thickness: dishArg ? this.optString(dishArg, 'thicknessNotes') ?? '' : str(baseDish.thickness) ?? '',
      prep: dishArg ? this.optString(dishArg, 'prepNotes') ?? '' : str(baseDish.prep) ?? '',
      ingredients: (Array.isArray(ingredients) ? ingredients : []).map((raw, index) => {
        const item = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
        const iname = this.optString(item, 'name');
        if (!iname?.trim()) throw new ToolFailure(`dish.ingredients[${index}].name is required.`);
        return { name: iname, stage: this.optString(item, 'stage') ?? 'seasoning', brand: this.optString(item, 'brand') ?? '', amount: this.optString(item, 'amountText') ?? '', unit: this.optString(item, 'amountUnit') ?? 'tbsp' };
      }),
    };
    const instructionsArg = args.instructions !== undefined ? args.instructions : base?.instructions;
    if (instructionsArg !== undefined && !Array.isArray(instructionsArg)) throw new ToolFailure('instructions must be an array.');
    let instructionSeq = 0;
    const instructions = (Array.isArray(instructionsArg) ? instructionsArg : []).map(raw => {
      const item = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
      const title = this.optString(item, 'title');
      if (!title?.trim()) throw new ToolFailure('Every instruction needs a title.');
      instructionSeq++;
      return {
        id: str(item.id) ?? this.idFor(args, 'step:' + instructionSeq + ':' + title),
        title, details: this.optString(item, 'details') ?? '',
        method: this.optString(item, 'method') ?? null,
        offsetMillis: typeof item.offsetMillis === 'number' ? item.offsetMillis : null,
        durationMillis: typeof item.durationMinutes === 'number' ? Math.round(item.durationMinutes * 60000) : (typeof item.durationMillis === 'number' ? item.durationMillis : null),
        sourceEventId: str(item.sourceEventId) ?? null,
        action: this.optString(item, 'action') ?? null,
      };
    });
    const targetsArg = args.targets !== undefined ? args.targets : base?.targets;
    if (targetsArg !== undefined && !Array.isArray(targetsArg)) throw new ToolFailure('targets must be an array.');
    const targets = (Array.isArray(targetsArg) ? targetsArg : []).map(raw => {
      const item = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
      const type = this.optString(item, 'type');
      if (!type) throw new ToolFailure('Every target needs a type.');
      return { dish: 0, type, value: typeof item.value === 'number' ? item.value : 0, unit: this.optString(item, 'unit') ?? '°F', explanation: this.optString(item, 'explanation') ?? '' };
    });
    const tags = args.tags !== undefined ? args.tags : base?.tags;
    if (tags !== undefined && (!Array.isArray(tags) || tags.some(x => typeof x !== 'string'))) throw new ToolFailure('tags must be an array of strings.');
    return {
      version: 1, name,
      description: this.optString(args, 'description') ?? str(base?.description) ?? '',
      tags: Array.isArray(tags) ? tags : [],
      draft: { title: name, smoker: '', setpoint: '', unit: '°F', notes: '', fuel: '', wood: '', dishes: [dish] },
      instructions,
      observations: Array.isArray(base?.observations) ? base!.observations : [],
      targets,
      resultNotes: this.optString(args, 'resultNotes') ?? str(base?.resultNotes) ?? '',
      rating: typeof args.rating === 'number' ? args.rating : (typeof base?.rating === 'number' ? base.rating : null),
      smokingAnchorUtcMillis: typeof base?.smokingAnchorUtcMillis === 'number' ? base.smokingAnchorUtcMillis : null,
      preferredPitTemperatureF: typeof args.preferredPitTemperatureF === 'number' ? args.preferredPitTemperatureF : (typeof base?.preferredPitTemperatureF === 'number' ? base.preferredPitTemperatureF : null),
      servings: this.optString(args, 'servings') ?? str(base?.servings) ?? '',
      cookAgain: typeof base?.cookAgain === 'boolean' ? base.cookAgain : null,
      publicSource: (typeof base?.publicSource === 'object' && base?.publicSource !== null ? base.publicSource : null),
    };
  }

  async createRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['name', 'description', 'tags', 'dish', 'instructions', 'targets', 'servings', 'preferredPitTemperatureF', 'resultNotes', 'changeNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    const snapshot = this.recipeSnapshot(args);
    return this.mutate(principal, args, work => {
      const now = this.clock();
      const recipeId = this.idFor(args, 'recipe');
      const revisionId = this.idFor(args, 'revision:1');
      work.insert('recipes', {
        id: recipeId, name: snapshot.name, description: snapshot.description,
        tagsJson: JSON.stringify(snapshot.tags), currentRevisionId: revisionId,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
        archivedAtUtcMillis: null, favorite: false,
      });
      work.insert('recipeRevisions', {
        id: revisionId, recipeId, parentRevisionId: null, revisionNumber: 1,
        snapshotSchemaVersion: 1, snapshotJson: JSON.stringify(snapshot),
        createdAtUtcMillis: now, changeNotes: this.optString(args, 'changeNotes') ?? 'Created by your AI assistant',
        sourceCookId: null, sourceDishId: null,
      });
      return { recipeId, revisionId, revisionNumber: 1 };
    }, args => ({ recipeId: this.idFor(args, 'recipe'), revisionId: this.idFor(args, 'revision:1'), revisionNumber: 1 }));
  }

  async updateRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['recipeId', 'name', 'description', 'tags', 'dish', 'instructions', 'targets', 'servings', 'preferredPitTemperatureF', 'resultNotes', 'changeNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      const revisions = work.liveRecords('recipeRevisions').filter(r => r.recipeId === recipe.id);
      if (!revisions.length) throw new ToolFailure('That recipe has no revisions to build on.');
      const current = revisions.find(r => r.id === recipe.currentRevisionId) ?? revisions.reduce((a, b) => ((num(b.revisionNumber) ?? 0) > (num(a.revisionNumber) ?? 0) ? b : a));
      let base: Record<string, unknown>;
      try { base = JSON.parse(str(current.snapshotJson) ?? '{}') as Record<string, unknown>; }
      catch { throw new ToolFailure('The current revision could not be read.'); }
      const snapshot = this.recipeSnapshot(args, base);
      const now = this.clock();
      const number = Math.max(...revisions.map(r => num(r.revisionNumber) ?? 0)) + 1;
      const revisionId = this.idFor(args, 'revision:' + number);
      work.insert('recipeRevisions', {
        id: revisionId, recipeId: recipe.id, parentRevisionId: current.id, revisionNumber: number,
        snapshotSchemaVersion: 1, snapshotJson: JSON.stringify(snapshot),
        createdAtUtcMillis: now, changeNotes: this.optString(args, 'changeNotes') ?? 'Updated by your AI assistant',
        sourceCookId: null, sourceDishId: null,
      });
      work.update('recipes', recipe, {
        name: snapshot.name, description: snapshot.description, tagsJson: JSON.stringify(snapshot.tags),
        currentRevisionId: revisionId, updatedAtUtcMillis: now,
      });
      return { recipeId: recipe.id, revisionId, revisionNumber: number };
    });
  }

  async archiveRecipe(principal: McpPrincipal, args: Record<string, unknown>) { return this.setArchived(principal, args, true); }
  async restoreRecipe(principal: McpPrincipal, args: Record<string, unknown>) { return this.setArchived(principal, args, false); }
  private async setArchived(principal: McpPrincipal, args: Record<string, unknown>, archived: boolean) {
    this.requireFields(args, ['recipeId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      work.update('recipes', recipe, { archivedAtUtcMillis: archived ? this.clock() : null, updatedAtUtcMillis: this.clock() });
      return { recipeId: recipe.id, archived };
    });
  }

  async setRecipeFavorite(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['recipeId', 'favorite', 'idempotencyKey', 'expectedSnapshotVersion']);
    if (typeof args.favorite !== 'boolean') throw new ToolFailure('favorite (true or false) is required.');
    return this.mutate(principal, args, work => {
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      work.update('recipes', recipe, { favorite: args.favorite, updatedAtUtcMillis: this.clock() });
      return { recipeId: recipe.id, favorite: args.favorite };
    });
  }

  async duplicateRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['recipeId', 'name', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      const revisions = work.liveRecords('recipeRevisions').filter(r => r.recipeId === recipe.id)
        .sort((a, b) => (num(a.revisionNumber) ?? 0) - (num(b.revisionNumber) ?? 0));
      const now = this.clock();
      const recipeId = this.idFor(args, 'recipe');
      const idMap = new Map<string, string>();
      for (const revision of revisions) idMap.set(str(revision.id)!, this.idFor(args, 'revision-copy:' + revision.id));
      for (const revision of revisions) {
        work.insert('recipeRevisions', {
          ...revision,
          id: idMap.get(str(revision.id)!)!,
          recipeId,
          parentRevisionId: revision.parentRevisionId ? idMap.get(str(revision.parentRevisionId)!) ?? null : null,
          createdAtUtcMillis: now,
        });
      }
      const currentCopy = recipe.currentRevisionId ? idMap.get(str(recipe.currentRevisionId)!) ?? null : null;
      const name = this.optString(args, 'name') ?? ((str(recipe.name) ?? 'Recipe') + ' (copy)');
      work.insert('recipes', {
        ...recipe, id: recipeId, name, currentRevisionId: currentCopy,
        createdAtUtcMillis: now, updatedAtUtcMillis: now, archivedAtUtcMillis: null,
      });
      return { recipeId, copiedRevisions: revisions.length, sourceRecipeId: recipe.id };
    }, args => ({ recipeId: this.idFor(args, 'recipe') }));
  }

  async saveDishAsRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['dishId', 'name', 'changeNotes', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const dish = work.live('dishes', args.dishId, 'Dish');
      const ingredients = work.liveRecords('ingredients').filter(r => r.dishId === dish.id)
        .map(row => ({ name: str(row.name) ?? '', stage: str(row.stage) ?? 'seasoning', brand: str(row.brand) ?? '', amountText: str(row.amountText) ?? '', amountUnit: str(row.amountUnit) ?? 'tbsp' }));
      const targets = work.liveRecords('targets').filter(r => r.dishId === dish.id)
        .map(row => ({ type: str(row.targetType) ?? 'internal', value: num(row.value) ?? 0, unit: str(row.unit) ?? '°F', explanation: str(row.explanation) ?? '' }));
      const name = this.optString(args, 'name') ?? str(dish.name) ?? 'Saved dish';
      const snapshot = this.recipeSnapshot({
        name, dish: {
          name: str(dish.name) ?? name, foodType: str(dish.foodType) ?? 'other', cut: str(dish.cut) ?? '',
          weightText: dish.weightValue !== undefined && dish.weightValue !== null ? String(dish.weightValue) : '', weightUnit: str(dish.weightUnit) ?? 'lb',
          placement: str(dish.placement) ?? '', gradeOrSource: str(dish.gradeOrSource) ?? '', prepNotes: str(dish.prepNotes) ?? '',
          ingredients,
        }, targets,
      });
      const now = this.clock();
      const recipeId = this.idFor(args, 'recipe');
      const revisionId = this.idFor(args, 'revision:1');
      work.insert('recipes', {
        id: recipeId, name, description: snapshot.description, tagsJson: JSON.stringify(snapshot.tags),
        currentRevisionId: revisionId, createdAtUtcMillis: now, updatedAtUtcMillis: now,
        archivedAtUtcMillis: null, favorite: false,
      });
      work.insert('recipeRevisions', {
        id: revisionId, recipeId, parentRevisionId: null, revisionNumber: 1,
        snapshotSchemaVersion: 1, snapshotJson: JSON.stringify(snapshot),
        createdAtUtcMillis: now, changeNotes: this.optString(args, 'changeNotes') ?? 'Saved from a dish by your AI assistant',
        sourceCookId: str(dish.cookId) ?? null, sourceDishId: str(dish.id) ?? null,
      });
      work.insert('dishRecipeSources', { dishId: dish.id, recipeId, recipeRevisionId: revisionId, appliedAtUtcMillis: now }, dish.id + ':' + recipeId);
      return { recipeId, revisionId, dishId: dish.id };
    }, args => ({ recipeId: this.idFor(args, 'recipe'), revisionId: this.idFor(args, 'revision:1') }));
  }

  async duplicateMealSetup(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'title', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const now = this.clock();
      const mealId = this.idFor(args, 'meal');
      work.insert('cooks', {
        ...meal, id: mealId,
        title: this.optString(args, 'title') ?? str(meal.title) ?? 'Cook',
        status: 'active', startedAtUtcMillis: now, endedAtUtcMillis: null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
      });
      work.insert('lifecycles', {
        cookId: mealId, state: 'in_progress', weatherJson: null, snapshotJson: null,
        legacyRating: null, legacyOutcome: null, legacyVisibility: null, legacySpotType: null,
        savedRecipeId: null, updatedAtUtcMillis: now,
      }, mealId, []);
      const dishes = work.liveRecords('dishes').filter(r => r.cookId === meal.id);
      const dishIds: string[] = [];
      dishes.forEach((dish, index) => {
        const dishId = this.idFor(args, 'dish:' + index); dishIds.push(dishId);
        work.insert('dishes', { ...dish, id: dishId, cookId: mealId, createdAtUtcMillis: now, updatedAtUtcMillis: now });
        for (const ingredient of work.liveRecords('ingredients').filter(r => r.dishId === dish.id)) {
          work.insert('ingredients', { ...ingredient, id: this.idFor(args, 'ing:' + ingredient.id), cookId: mealId, dishId, createdAtUtcMillis: now });
        }
        for (const target of work.liveRecords('targets').filter(r => r.dishId === dish.id)) {
          work.insert('targets', { ...target, id: this.idFor(args, 'target:' + target.id), cookId: mealId, dishId, createdAtUtcMillis: now });
        }
      });
      for (const target of work.liveRecords('targets').filter(r => r.cookId === meal.id && (r.dishId === null || r.dishId === undefined))) {
        work.insert('targets', { ...target, id: this.idFor(args, 'target:' + target.id), cookId: mealId, createdAtUtcMillis: now });
      }
      return { mealId, dishIds, sourceMealId: meal.id, note: 'Setup copied with fresh identities; no history, readings, results or photos were replayed.' };
    }, args => ({ mealId: this.idFor(args, 'meal') }));
  }

  async deleteRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['recipeId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      let tombstoned = 0;
      for (const revision of work.liveRecords('recipeRevisions').filter(r => r.recipeId === recipe.id)) { work.tombstone('recipeRevisions', revision); tombstoned++; }
      for (const source of work.liveRecords('dishRecipeSources').filter(r => r.recipeId === recipe.id)) { work.tombstone('dishRecipeSources', source); tombstoned++; }
      work.tombstone('recipes', recipe); tombstoned++;
      return { recipeId: recipe.id, tombstonedRecords: tombstoned };
    });
  }


  private photoStore(): McpPhotoStore {
    if (!this.photos) throw new ToolFailure('Photo storage is not available on this server.');
    return this.photos;
  }
  private async photoOp<T>(run: () => Promise<T>): Promise<T> {
    try { return await run(); }
    catch (failure) {
      if (failure instanceof ApiError) throw new ToolFailure(`The photo store rejected that (${failure.code}). Check the upload state with get_photo_upload_status and try again.`);
      throw failure;
    }
  }
  private photoSummary(photo: Payload) {
    return {
      photoId: photo.id, assetId: photo.assetId, mealId: photo.cookId,
      dishId: photo.dishId ?? null, eventId: photo.eventId ?? null,
      caption: photo.caption ?? null, capturedAt: iso(photo.capturedAtUtcMillis), mimeType: photo.mimeType,
    };
  }

  async listPhotos(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId']);
    const data = await this.loadLibrary(principal);
    let photos = this.records(data, 'photos');
    if (typeof args.mealId === 'string') photos = photos.filter(photo => photo.cookId === args.mealId);
    return { snapshotVersion: data.version, total: photos.length, photos: photos.map(photo => this.photoSummary(photo)) };
  }

  async getPhoto(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['photoId']);
    const data = await this.loadLibrary(principal);
    const photo = this.record(data, 'photos', args.photoId, 'Photo');
    const assetId = str(photo.assetId);
    if (!assetId) throw new ToolFailure('That photo has no stored image.');
    const stored = await this.photoOp(() => this.photoStore().read(principal.userId, assetId));
    return {
      content: [{ type: 'image', data: stored.bytes.toString('base64'), mimeType: stored.mimeType }],
      structuredContent: { snapshotVersion: data.version, ...this.photoSummary(photo), bytes: stored.bytes.length, sha256: stored.sha256 },
    };
  }

  async beginPhotoUpload(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['bytes', 'mimeType', 'sha256', 'assetId']);
    const bytes = this.optNumber(args, 'bytes');
    const mimeType = this.optString(args, 'mimeType');
    const sha256 = this.optString(args, 'sha256');
    if (!bytes || !Number.isSafeInteger(bytes) || bytes <= 0) throw new ToolFailure('bytes (the total image size) is required.');
    if (!mimeType || !['image/jpeg', 'image/png', 'image/webp'].includes(mimeType)) throw new ToolFailure('mimeType must be image/jpeg, image/png or image/webp.');
    if (!sha256 || !/^[0-9a-f]{64}$/.test(sha256)) throw new ToolFailure('sha256 (lowercase hex of the whole image) is required.');
    const assetId = this.optString(args, 'assetId') ?? randomUUID();
    if (!/^[0-9a-fA-F-]{36}$/.test(assetId)) throw new ToolFailure('assetId must be a UUID when provided.');
    const status = await this.photoOp(() => this.requirePhotos().begin(principal.userId, { assetId, bytes, mimeType, sha256 }));
    return { assetId, upload: status };
  }

  async uploadPhotoChunk(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['assetId', 'index', 'dataBase64']);
    const assetId = this.optString(args, 'assetId');
    const index = this.optNumber(args, 'index');
    const dataBase64 = this.optString(args, 'dataBase64');
    if (!assetId || !/^[0-9a-fA-F-]{36}$/.test(assetId)) throw new ToolFailure('assetId must be a UUID.');
    if (index === undefined || !Number.isSafeInteger(index) || index < 0) throw new ToolFailure('index (0-based chunk number) is required.');
    if (!dataBase64) throw new ToolFailure('dataBase64 is required.');
    const bytes = Buffer.from(dataBase64, 'base64');
    if (!bytes.length || bytes.length > 1024 ** 2) throw new ToolFailure('Each chunk must decode to between 1 byte and 1 MiB.');
    const status = await this.photoOp(() => this.requirePhotos().putPart(principal.userId, assetId, index, bytes));
    return { assetId, upload: status };
  }

  async photoUploadStatus(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['assetId']);
    const assetId = this.optString(args, 'assetId');
    if (!assetId) throw new ToolFailure('assetId is required.');
    const status = await this.photoOp(() => this.photoStore().statusOf(principal.userId, assetId));
    return { assetId, upload: status };
  }

  async completePhotoUpload(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['assetId']);
    const assetId = this.optString(args, 'assetId');
    if (!assetId) throw new ToolFailure('assetId is required.');
    const status = await this.photoOp(() => this.requirePhotos().finish(principal.userId, assetId));
    return { assetId, upload: status, next: 'The image is stored. Attach it to a meal with attach_photo so it appears in the library.' };
  }

  async cancelPhotoUpload(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['assetId']);
    const assetId = this.optString(args, 'assetId');
    if (!assetId) throw new ToolFailure('assetId is required.');
    await this.photoOp(() => this.requirePhotos().abandon(principal.userId, assetId));
    return { assetId, cancelled: true };
  }

  async attachPhoto(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['assetId', 'mealId', 'dishId', 'caption', 'capturedAtUtcMillis', 'idempotencyKey', 'expectedSnapshotVersion']);
    const assetId = this.optString(args, 'assetId');
    if (!assetId) throw new ToolFailure('assetId is required.');
    // Verify the asset is committed and learn its real mime type before
    // the library points at it.
    const stored = await this.photoOp(() => this.photoStore().read(principal.userId, assetId));
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      let dishId: string | null = null;
      if (args.dishId !== undefined && args.dishId !== null) {
        const dish = work.live('dishes', args.dishId, 'Dish');
        if (dish.cookId !== meal.id) throw new ToolFailure('That dish belongs to a different meal.');
        dishId = str(dish.id)!;
      }
      if (work.liveRecords('photos').some(photo => photo.assetId === assetId)) throw new ToolFailure('That image is already attached to the library.');
      const now = this.clock();
      const photoId = this.idFor(args, 'photo');
      work.insert('photos', {
        id: photoId, assetId, cookId: meal.id, dishId, eventId: null,
        caption: this.optString(args, 'caption') ?? null,
        capturedAtUtcMillis: this.optNumber(args, 'capturedAtUtcMillis') ?? now,
        addedAtUtcMillis: now, mimeType: stored.mimeType, temperatureContextJson: null,
      }, undefined, [assetId]);
      return { photoId, assetId, mealId: meal.id };
    }, args => ({ photoId: this.idFor(args, 'photo'), assetId }));
  }

  async updatePhoto(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['photoId', 'caption', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const photo = work.live('photos', args.photoId, 'Photo');
      if (!('caption' in args)) throw new ToolFailure('Nothing to update: provide a caption.');
      work.update('photos', photo, { caption: this.optString(args, 'caption') ?? null });
      return { photoId: photo.id };
    });
  }

  async removePhoto(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['photoId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const photo = work.live('photos', args.photoId, 'Photo');
      const assetId = str(photo.assetId);
      work.tombstone('photos', photo);
      return { photoId: photo.id, assetId: assetId ?? null };
    });
  }


  private static readonly INSIGHTS_VERSION = 'mcp-insights-v1';
  private dishName(data: LibraryData, dishId: unknown): string | null {
    if (typeof dishId !== 'string') return null;
    const dish = data.byType.get('dishes')?.get(dishId);
    return dish ? str(dish.name) ?? null : null;
  }

  async temperatureHistory(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'dishId', 'measurementType', 'mode', 'limit']);
    const data = await this.loadLibrary(principal);
    const meal = this.record(data, 'cooks', args.mealId, 'Meal');
    let readings = this.records(data, 'readings').filter(r => r.cookId === meal.id);
    if (typeof args.dishId === 'string') readings = readings.filter(r => r.dishId === args.dishId);
    if (typeof args.measurementType === 'string') readings = readings.filter(r => r.measurementType === args.measurementType);
    readings.sort((a, b) => (num(a.measuredAtUtcMillis) ?? 0) - (num(b.measuredAtUtcMillis) ?? 0));
    const mode = this.optString(args, 'mode') ?? 'reduced';
    if (mode === 'raw') {
      const limit = Math.min(Math.max(Math.trunc(this.optNumber(args, 'limit') ?? 100), 1), 500);
      const page = readings.slice(0, limit);
      return {
        snapshotVersion: data.version, mealId: meal.id, mode, algorithm: PrivateLibraryService.INSIGHTS_VERSION,
        totalReadings: readings.length, returned: page.length,
        readings: page.map(r => ({ at: iso(r.measuredAtUtcMillis), dishId: r.dishId ?? null, dish: this.dishName(data, r.dishId), probe: r.probeName ?? null, type: r.measurementType, value: r.value, unit: r.unit, quality: r.qualityStatus ?? null })),
      };
    }
    if (mode !== 'reduced') throw new ToolFailure("mode must be 'reduced' or 'raw'.");
    // Reduced: 15-minute interval statistics per (dish, type, unit), the
    // same interval the community curves use.
    const interval = 900000;
    const groups = new Map<string, { dishId: unknown; type: unknown; unit: unknown; start: number; values: number[] }>();
    for (const r of readings) {
      const at = num(r.measuredAtUtcMillis); const value = num(r.value);
      if (at === undefined || value === undefined) continue;
      const start = Math.floor(at / interval) * interval;
      const key = `${String(r.dishId)}|${String(r.measurementType)}|${String(r.unit)}|${start}`;
      const group = groups.get(key) ?? { dishId: r.dishId, type: r.measurementType, unit: r.unit, start, values: [] };
      group.values.push(value); groups.set(key, group);
    }
    const series = [...groups.values()].sort((a, b) => a.start - b.start).map(g => ({
      dishId: g.dishId ?? null, dish: this.dishName(data, g.dishId), type: g.type, unit: g.unit,
      intervalStart: iso(g.start), samples: g.values.length,
      min: Math.min(...g.values), max: Math.max(...g.values),
      avg: Math.round((g.values.reduce((a, b) => a + b, 0) / g.values.length) * 10) / 10,
    }));
    return { snapshotVersion: data.version, mealId: meal.id, mode, algorithm: PrivateLibraryService.INSIGHTS_VERSION, totalReadings: readings.length, intervals: series };
  }

  async cookSummary(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId']);
    const data = await this.loadLibrary(principal);
    const meal = this.record(data, 'cooks', args.mealId, 'Meal');
    const dishes = this.records(data, 'dishes').filter(d => d.cookId === meal.id);
    const readings = this.records(data, 'readings').filter(r => r.cookId === meal.id);
    const results = this.records(data, 'results').filter(r => r.cookId === meal.id);
    const summarizeDish = (dish: Payload) => {
      const own = readings.filter(r => r.dishId === dish.id && typeof r.value === 'number');
      const byType = new Map<string, number[]>();
      for (const r of own) { const list = byType.get(String(r.measurementType)) ?? []; list.push(num(r.value)!); byType.set(String(r.measurementType), list); }
      return {
        dishId: dish.id, name: dish.name, foodType: dish.foodType,
        peakByType: Object.fromEntries([...byType.entries()].map(([type, values]) => [type, Math.max(...values)])),
        readingCount: own.length,
        results: results.filter(r => r.dishId === dish.id).map(r => ({ type: r.resultType, value: r.numericValue ?? r.textValue ?? null, unit: r.unit ?? null, notes: r.notes ?? null })),
      };
    };
    const started = num(meal.startedAtUtcMillis); const ended = num(meal.endedAtUtcMillis);
    return {
      snapshotVersion: data.version, algorithm: PrivateLibraryService.INSIGHTS_VERSION,
      meal: {
        mealId: meal.id, title: meal.title, status: meal.status, smokerName: meal.smokerName ?? null,
        startedAt: iso(meal.startedAtUtcMillis), endedAt: iso(meal.endedAtUtcMillis),
        durationMinutes: started !== undefined && ended !== undefined ? Math.round((ended - started) / 60000) : null,
      },
      dishes: dishes.map(summarizeDish),
      cookLevelResults: results.filter(r => !r.dishId).map(r => ({ type: r.resultType, value: r.numericValue ?? r.textValue ?? null, unit: r.unit ?? null, notes: r.notes ?? null })),
      noteCount: this.records(data, 'events').filter(e => e.cookId === meal.id).length,
      photoCount: this.records(data, 'photos').filter(p => p.cookId === meal.id).length,
      readingCount: readings.length,
    };
  }

  async compareCooks(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealIds']);
    if (!Array.isArray(args.mealIds) || args.mealIds.length < 2 || args.mealIds.length > 4 || args.mealIds.some(x => typeof x !== 'string')) {
      throw new ToolFailure('mealIds must be an array of 2 to 4 meal ids.');
    }
    const summaries = [];
    for (const mealId of args.mealIds as string[]) {
      summaries.push(await this.cookSummary(principal, { mealId }));
    }
    return { algorithm: PrivateLibraryService.INSIGHTS_VERSION, cooks: summaries };
  }

  async personalInsights(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, []);
    const data = await this.loadLibrary(principal);
    const meals = this.records(data, 'cooks');
    const dishes = this.records(data, 'dishes');
    const results = this.records(data, 'results');
    const completed = meals.filter(m => m.status === 'completed');
    const durations = completed.map(m => { const s = num(m.startedAtUtcMillis); const e = num(m.endedAtUtcMillis); return s !== undefined && e !== undefined && e > s ? (e - s) / 3600000 : undefined; }).filter((x): x is number => x !== undefined);
    const foodCounts = new Map<string, number>();
    for (const dish of dishes) foodCounts.set(String(dish.foodType ?? 'other'), (foodCounts.get(String(dish.foodType ?? 'other')) ?? 0) + 1);
    const ratings = results.filter(r => typeof r.numericValue === 'number' && /rating|score/i.test(String(r.resultType ?? '')))
      .map(r => ({ dishId: r.dishId ?? null, dish: this.dishName(data, r.dishId), value: num(r.numericValue)! })).sort((a, b) => b.value - a.value);
    const byMonth = new Map<string, number>();
    for (const meal of meals) { const at = num(meal.startedAtUtcMillis); if (at === undefined) continue; const key = new Date(at).toISOString().slice(0, 7); byMonth.set(key, (byMonth.get(key) ?? 0) + 1); }
    return {
      snapshotVersion: data.version, algorithm: PrivateLibraryService.INSIGHTS_VERSION,
      totals: { meals: meals.length, completedMeals: completed.length, dishes: dishes.length, photos: this.records(data, 'photos').length, notes: this.records(data, 'events').length, readings: this.records(data, 'readings').length },
      averageCompletedCookHours: durations.length ? Math.round((durations.reduce((a, b) => a + b, 0) / durations.length) * 10) / 10 : null,
      dishesByFoodType: Object.fromEntries([...foodCounts.entries()].sort((a, b) => b[1] - a[1])),
      topRated: ratings.slice(0, 5),
      mealsByMonth: Object.fromEntries([...byMonth.entries()].sort()),
      sampleCounts: { meals: meals.length, durations: durations.length, ratings: ratings.length },
    };
  }

  async communityBenchmarks(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['bucket', 'metric']);
    if (!this.benchmarks) throw new ToolFailure('Community benchmarks are not available on this server.');
    const { run, figures } = await this.benchmarks.latestFigures(this.optString(args, 'bucket'), this.optString(args, 'metric'));
    return { run, figures: figures.slice(0, 100), note: 'Anonymous community aggregates only (minimum group sizes enforced by the aggregation job); no individual cooks are exposed.' };
  }

  async buildRecap(principal: McpPrincipal, args: Record<string, unknown>) {
    const summary = await this.cookSummary(principal, args);
    const meal = summary.meal as { title?: string; status?: string; durationMinutes?: number | null };
    const lines = [
      `# ${meal.title ?? 'Cook'}`,
      `Status: ${meal.status ?? 'unknown'}${meal.durationMinutes ? ` · ${meal.durationMinutes} minutes` : ''}`,
    ];
    for (const dish of summary.dishes as { name?: string; foodType?: string; peakByType?: Record<string, number>; results?: { type?: string; value?: unknown }[] }[]) {
      const peaks = Object.entries(dish.peakByType ?? {}).map(([type, value]) => `${type} peaked at ${value}`).join('; ');
      lines.push(`- ${dish.name ?? 'Dish'} (${dish.foodType ?? 'food'})${peaks ? ` — ${peaks}` : ''}`);
      for (const result of dish.results ?? []) lines.push(`  - ${result.type}: ${String(result.value ?? '')}`);
    }
    if (summary.noteCount) lines.push(`Notes: ${summary.noteCount}. Photos: ${summary.photoCount}. Readings: ${summary.readingCount}.`);
    return { snapshotVersion: summary.snapshotVersion, algorithm: PrivateLibraryService.INSIGHTS_VERSION, mealId: (args.mealId as string), recap: lines.join('\n') };
  }


  /** The dish blueprint a recipe's current revision describes: dish
   * fields, ingredients and targets, exactly as saved. */
  private recipeBlueprint(work: WorkingLibrary, recipe: Payload): { dish: Record<string, unknown>; ingredients: Record<string, unknown>[]; targets: Record<string, unknown>[]; revisionId: string } {
    const revisions = work.liveRecords('recipeRevisions').filter(r => r.recipeId === recipe.id);
    const current = revisions.find(r => r.id === recipe.currentRevisionId) ?? revisions.reduce((a, b) => ((num(b.revisionNumber) ?? 0) > (num(a.revisionNumber) ?? 0) ? b : a), revisions[0]);
    if (!current) throw new ToolFailure(`Recipe "${str(recipe.name) ?? ''}" has no revision to cook from.`);
    let snapshot: Record<string, unknown>;
    try { snapshot = JSON.parse(str(current.snapshotJson) ?? '{}') as Record<string, unknown>; }
    catch { throw new ToolFailure(`Recipe "${str(recipe.name) ?? ''}" could not be read.`); }
    const draft = (typeof snapshot.draft === 'object' && snapshot.draft !== null ? snapshot.draft : {}) as Record<string, unknown>;
    const dish = (Array.isArray(draft.dishes) ? draft.dishes[0] ?? {} : {}) as Record<string, unknown>;
    return {
      revisionId: str(current.id)!,
      dish: {
        name: str(dish.name) ?? str(recipe.name) ?? 'Dish', foodType: str(dish.foodType) ?? 'other',
        cut: str(dish.cut) ?? '', weightText: str(dish.weight) ?? '', weightUnit: str(dish.weightUnit) ?? 'lb',
        placement: str(dish.placement) ?? '', gradeOrSource: str(dish.grade) ?? '', prepNotes: str(dish.prep) ?? '',
        thicknessNotes: str(dish.thickness) ?? '',
      },
      ingredients: (Array.isArray(dish.ingredients) ? dish.ingredients : []).map(raw => {
        const item = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
        return { name: str(item.name) ?? '', stage: str(item.stage) ?? 'seasoning', brand: str(item.brand) ?? '', amountText: str(item.amount) ?? '', amountUnit: str(item.unit) ?? 'tbsp' };
      }).filter(item => item.name.trim()),
      targets: (Array.isArray(snapshot.targets) ? snapshot.targets : []).map(raw => {
        const item = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
        return { targetType: str(item.type) ?? 'internal', value: num(item.value) ?? 0, unit: str(item.unit) ?? '°F', explanation: str(item.explanation) ?? '' };
      }),
    };
  }

  /** Inserts one dish (plus ingredients, targets and the recipe source
   * link when it came from a recipe) into a meal being built. */
  private insertComposedDish(work: WorkingLibrary, mealId: string, args: Record<string, unknown>, salt: string,
    spec: { name: string; foodType: string; cut?: string; weightValue?: number; weightUnit?: string; placement?: string; gradeOrSource?: string; prepNotes?: string; thicknessNotes?: string },
    extras?: { ingredients: { name: string; amountText?: string; amountUnit?: string; brand?: string; stage?: string }[]; targets: { targetType: string; value: number; unit?: string; explanation?: string }[]; recipeId?: string; revisionId?: string }): string {
    const now = this.clock();
    const dishId = this.idFor(args, 'dish:' + salt);
    work.insert('dishes', {
      id: dishId, cookId: mealId,
      name: spec.name, foodType: spec.foodType, cut: spec.cut ?? '',
      weightValue: spec.weightValue ?? null, weightUnit: spec.weightUnit ?? 'lb',
      startingCondition: null, boneIn: null, placement: spec.placement ?? '',
      gradeOrSource: spec.gradeOrSource ?? '', thicknessNotes: spec.thicknessNotes ?? '',
      prepNotes: spec.prepNotes ?? '', createdAtUtcMillis: now, updatedAtUtcMillis: now,
    });
    for (const [index, ingredient] of (extras?.ingredients ?? []).entries()) {
      work.insert('ingredients', {
        id: this.idFor(args, 'ing:' + salt + ':' + index), cookId: mealId, dishId,
        name: ingredient.name, stage: ingredient.stage ?? 'seasoning', brand: ingredient.brand ?? '',
        amountText: ingredient.amountText ?? '', amountUnit: ingredient.amountUnit ?? 'tbsp', amountValue: null,
        appliedAtUtcMillis: null, notes: null, createdAtUtcMillis: now,
      });
    }
    for (const [index, target] of (extras?.targets ?? []).entries()) {
      work.insert('targets', {
        id: this.idFor(args, 'target:' + salt + ':' + index), cookId: mealId, dishId,
        targetType: target.targetType, value: target.value, unit: target.unit ?? '°F', scope: 'dish',
        explanation: target.explanation ?? '', sourceReference: extras?.recipeId ? 'recipe:' + extras.recipeId : null,
        createdAtUtcMillis: now,
      });
    }
    if (extras?.recipeId && extras?.revisionId) {
      work.insert('dishRecipeSources', { dishId, recipeId: extras.recipeId, recipeRevisionId: extras.revisionId, appliedAtUtcMillis: now }, dishId + ':' + extras.recipeId);
    }
    return dishId;
  }

  async composeMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['title', 'smokerName', 'dishes', 'idempotencyKey', 'expectedSnapshotVersion']);
    if (!Array.isArray(args.dishes) || !args.dishes.length || args.dishes.length > 20) throw new ToolFailure('dishes must be an array of 1 to 20 entries, each naming a recipeId or a name + foodType.');
    return this.mutate(principal, args, work => {
      const now = this.clock();
      const mealId = this.idFor(args, 'meal');
      const title = this.optString(args, 'title') ?? 'Composed cook';
      work.insert('cooks', {
        id: mealId, title, smokerName: this.optString(args, 'smokerName') ?? null,
        smokerId: null, status: 'active', startedAtUtcMillis: now, endedAtUtcMillis: null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now, timeZoneId: 'UTC',
      });
      work.insert('lifecycles', { cookId: mealId, state: 'legacy_preserved', weatherJson: null, snapshotJson: null, legacyRating: null, legacyOutcome: null, legacyVisibility: null, legacySpotType: null, savedRecipeId: null, updatedAtUtcMillis: now }, mealId, []);
      const dishIds: string[] = [];
      (args.dishes as Record<string, unknown>[]).forEach((entry, index) => {
        const spec = (typeof entry === 'object' && entry !== null ? entry : {}) as Record<string, unknown>;
        if (typeof spec.recipeId === 'string') {
          const recipe = work.live('recipes', spec.recipeId, 'Recipe');
          const blueprint = this.recipeBlueprint(work, recipe);
          dishIds.push(this.insertComposedDish(work, mealId, args, String(index),
            { name: this.optString(spec, 'name') ?? String(blueprint.dish.name), foodType: String(blueprint.dish.foodType), cut: String(blueprint.dish.cut ?? ''), weightUnit: String(blueprint.dish.weightUnit ?? 'lb'), placement: String(blueprint.dish.placement ?? ''), gradeOrSource: String(blueprint.dish.gradeOrSource ?? ''), prepNotes: String(blueprint.dish.prepNotes ?? ''), thicknessNotes: String(blueprint.dish.thicknessNotes ?? '') },
            { ingredients: blueprint.ingredients as never[], targets: blueprint.targets as never[], recipeId: str(recipe.id)!, revisionId: blueprint.revisionId }));
        } else {
          const name = this.optString(spec, 'name'); const foodType = this.optString(spec, 'foodType');
          if (!name?.trim() || !foodType) throw new ToolFailure('Each dish needs a recipeId, or a name and foodType.');
          dishIds.push(this.insertComposedDish(work, mealId, args, String(index), { name, foodType }));
        }
      });
      return { mealId, dishIds, status: 'active' };
    }, args => ({ mealId: this.idFor(args, 'meal') }));
  }

  async addRecipeToMeal(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId', 'recipeId', 'name', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      if (meal.status === 'completed') throw new ToolFailure('That meal is completed; its dishes are final. Duplicate its setup for a new cook instead.');
      const recipe = work.live('recipes', args.recipeId, 'Recipe');
      const blueprint = this.recipeBlueprint(work, recipe);
      const salt = 'add:' + str(recipe.id);
      const dishId = this.insertComposedDish(work, str(meal.id)!, args, salt,
        { name: this.optString(args, 'name') ?? String(blueprint.dish.name), foodType: String(blueprint.dish.foodType), cut: String(blueprint.dish.cut ?? ''), weightUnit: String(blueprint.dish.weightUnit ?? 'lb'), placement: String(blueprint.dish.placement ?? ''), gradeOrSource: String(blueprint.dish.gradeOrSource ?? ''), prepNotes: String(blueprint.dish.prepNotes ?? ''), thicknessNotes: String(blueprint.dish.thicknessNotes ?? '') },
        { ingredients: blueprint.ingredients as never[], targets: blueprint.targets as never[], recipeId: str(recipe.id)!, revisionId: blueprint.revisionId });
      return { mealId: meal.id, dishId, recipeId: recipe.id };
    });
  }


  /** Companion documents (plans, playbooks, checklists, equipment…)
   * and reminder rules: typed-envelope access over companionRecords /
   * reminderRules. The projection validator remains the authority on
   * every document's shape — a malformed document is rejected by the
   * library's own rules on commit, never silently stored. */
  private static readonly PLANNING_KINDS = new Set(['plan', 'playbook', 'serve_plan', 'reference', 'pinned_guide', 'guide_presentation', 'alert', 'trigger']);
  private static readonly LIBRARY_KINDS = new Set(['checklist', 'prep_combo', 'equipment', 'fuel', 'presets']);
  private companionScope(kind: string): 'planning:write' | 'library:write' {
    if (PrivateLibraryService.PLANNING_KINDS.has(kind)) return 'planning:write';
    if (PrivateLibraryService.LIBRARY_KINDS.has(kind)) return 'library:write';
    throw new ToolFailure(`Companion kind "${kind}" is not available through MCP. Available kinds: ${[...PrivateLibraryService.PLANNING_KINDS, ...PrivateLibraryService.LIBRARY_KINDS].join(', ')}.`);
  }

  async listCompanions(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['kind', 'mealId']);
    const data = await this.loadLibrary(principal);
    let rows = this.records(data, 'companionRecords');
    if (typeof args.kind === 'string') rows = rows.filter(r => r.kind === args.kind);
    if (typeof args.mealId === 'string') rows = rows.filter(r => r.cookId === args.mealId);
    return {
      snapshotVersion: data.version, total: rows.length,
      companions: rows.map(r => ({ companionId: r.id, kind: r.kind, title: r.title, mealId: r.cookId ?? null, updatedAt: iso(r.updated), payload: r.payload })),
    };
  }

  async getCompanion(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['companionId']);
    const data = await this.loadLibrary(principal);
    const row = this.record(data, 'companionRecords', args.companionId, 'Companion document');
    return { snapshotVersion: data.version, companion: { companionId: row.id, kind: row.kind, title: row.title, mealId: row.cookId ?? null, createdAt: iso(row.created), updatedAt: iso(row.updated), payload: row.payload } };
  }

  async upsertCompanion(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['companionId', 'kind', 'title', 'mealId', 'payload', 'idempotencyKey', 'expectedSnapshotVersion']);
    const kind = this.optString(args, 'kind');
    if (!kind) throw new ToolFailure('kind is required.');
    const needed = this.companionScope(kind);
    if (!hasScope(principal, needed)) throw new ToolFailure(`This connection does not include the access for ${kind} documents (${needed}). Ask the user to reconnect with that access enabled.`);
    if (typeof args.payload !== 'object' || args.payload === null || Array.isArray(args.payload)) throw new ToolFailure('payload must be the companion document object for its kind.');
    return this.mutate(principal, args, work => {
      const now = this.clock();
      const title = this.optString(args, 'title');
      const mealId = typeof args.mealId === 'string' ? args.mealId : null;
      if (typeof args.companionId === 'string') {
        const existing = work.live('companionRecords', args.companionId, 'Companion document');
        if (existing.kind !== kind) throw new ToolFailure('That document has a different kind; create a new document instead of changing its kind.');
        work.update('companionRecords', existing, { title: title ?? existing.title, cookId: mealId, payload: args.payload, updated: now });
        return { companionId: existing.id, kind, created: false };
      }
      if (!title?.trim()) throw new ToolFailure('title is required for a new companion document.');
      const companionId = this.idFor(args, 'companion');
      work.insert('companionRecords', { id: companionId, kind, title: title.trim(), cookId: mealId, payload: args.payload, created: now, updated: now });
      return { companionId, kind, created: true };
    }, args => (typeof args.companionId === 'string' ? { companionId: args.companionId } : { companionId: this.idFor(args, 'companion') }));
  }

  async deleteCompanion(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['companionId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const row = work.live('companionRecords', args.companionId, 'Companion document');
      const needed = PrivateLibraryService.PLANNING_KINDS.has(String(row.kind)) ? 'planning:write' : 'library:delete';
      if (!hasScope(principal, needed)) throw new ToolFailure(`This connection does not include the access to delete ${String(row.kind)} documents (${needed}).`);
      work.tombstone('companionRecords', row);
      return { companionId: row.id, kind: row.kind };
    });
  }

  async listReminderRules(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['mealId']);
    const data = await this.loadLibrary(principal);
    let rows = this.records(data, 'reminderRules');
    if (typeof args.mealId === 'string') rows = rows.filter(r => r.cookId === args.mealId);
    return {
      snapshotVersion: data.version, total: rows.length,
      rules: rows.map(r => ({ ruleId: r.id, kind: r.kind, title: r.title, mealId: r.cookId, dishId: r.dishId ?? null, triggerConfig: typeof r.triggerConfig === 'string' ? safeJson(r.triggerConfig) : r.triggerConfig, updatedAt: iso(r.updatedAtUtcMillis) })),
      note: 'Rules sync to the devices; whether a rule is active on a device is decided there, never by a synced flag.',
    };
  }

  async upsertReminderRule(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['ruleId', 'mealId', 'dishId', 'kind', 'title', 'triggerConfig', 'idempotencyKey', 'expectedSnapshotVersion']);
    const kind = this.optString(args, 'kind');
    const title = this.optString(args, 'title');
    if (!kind || !title?.trim()) throw new ToolFailure('kind and title are required.');
    if (typeof args.triggerConfig !== 'object' || args.triggerConfig === null || Array.isArray(args.triggerConfig)) throw new ToolFailure('triggerConfig must be an object.');
    if ((args.triggerConfig as Record<string, unknown>).type !== kind) throw new ToolFailure('triggerConfig.type must match kind (the library enforces this too).');
    return this.mutate(principal, args, work => {
      const meal = work.live('cooks', args.mealId, 'Meal');
      const now = this.clock();
      const triggerConfig = JSON.stringify(args.triggerConfig);
      if (typeof args.ruleId === 'string') {
        const existing = work.live('reminderRules', args.ruleId, 'Reminder rule');
        work.update('reminderRules', existing, { kind, title: title.trim(), triggerConfig, updatedAtUtcMillis: now });
        return { ruleId: existing.id, created: false };
      }
      const ruleId = this.idFor(args, 'rule');
      work.insert('reminderRules', {
        id: ruleId, cookId: meal.id, dishId: typeof args.dishId === 'string' ? args.dishId : null,
        kind, title: title.trim(), triggerConfig, schemaVersion: 1,
        sourceMilestoneId: null, sourceRecipeRevisionId: null,
        createdAtUtcMillis: now, updatedAtUtcMillis: now,
      });
      return { ruleId, created: true };
    }, args => (typeof args.ruleId === 'string' ? { ruleId: args.ruleId } : { ruleId: this.idFor(args, 'rule') }));
  }

  async deleteReminderRule(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['ruleId', 'idempotencyKey', 'expectedSnapshotVersion']);
    return this.mutate(principal, args, work => {
      const row = work.live('reminderRules', args.ruleId, 'Reminder rule');
      work.tombstone('reminderRules', row);
      return { ruleId: row.id };
    });
  }


  private communitySource(): McpCommunitySource {
    if (!this.community) throw new ToolFailure('Community recipes are not available on this server.');
    return this.community;
  }
  private async communityOp<T>(run: () => Promise<T>): Promise<T> {
    try { return await run(); }
    catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) throw new ToolFailure('That community recipe could not be found (it may have been removed or unpublished).');
      if (failure instanceof ApiError) throw new ToolFailure(`Community lookup failed (${failure.code}). Try again shortly.`);
      throw failure;
    }
  }

  async searchCommunity(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['query', 'food', 'sort', 'cursor']);
    const result = await this.communityOp(() => this.communitySource().discover({
      query: this.optString(args, 'query'), food: this.optString(args, 'food'),
      sort: this.optString(args, 'sort'), cursor: this.optString(args, 'cursor'),
    }));
    return { community: result, note: 'Community recipes are read-only here: saving one keeps a private copy pinned to this exact public revision, with its credit. Nothing can be published, liked or commented on through MCP.' };
  }

  async getCommunityRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['itemId']);
    const itemId = this.optString(args, 'itemId');
    if (!itemId) throw new ToolFailure('itemId is required.');
    const detail = await this.communityOp(() => this.communitySource().get(itemId));
    return { item: detail };
  }

  async saveCommunityRecipe(principal: McpPrincipal, args: Record<string, unknown>) {
    this.requireFields(args, ['itemId', 'name', 'idempotencyKey', 'expectedSnapshotVersion']);
    const itemId = this.optString(args, 'itemId');
    if (!itemId) throw new ToolFailure('itemId is required.');
    const detail = await this.communityOp(() => this.communitySource().get(itemId));
    const source = (detail.payload?.recipes ?? [])[0];
    if (!source) throw new ToolFailure('That community item has no recipe to save.');
    // The public recipe document is a codec snapshot; normalize it
    // through the same builder as private recipes and pin the source.
    const snapshot = this.recipeSnapshot({ name: this.optString(args, 'name') ?? str(source.name) }, source);
    const credit = (detail.credit ?? {}) as Record<string, unknown>;
    snapshot.publicSource = {
      itemId, revisionId: detail.revisionId,
      displayName: str(credit.displayName) ?? str(credit.handle) ?? 'PitTech cook',
      removed: false, relativeTiming: true,
    };
    return this.mutate(principal, args, work => {
      const now = this.clock();
      const recipeId = this.idFor(args, 'recipe');
      const revisionId = this.idFor(args, 'revision:1');
      work.insert('recipes', {
        id: recipeId, name: snapshot.name, description: snapshot.description,
        tagsJson: JSON.stringify(snapshot.tags), currentRevisionId: revisionId,
        createdAtUtcMillis: now, updatedAtUtcMillis: now, archivedAtUtcMillis: null, favorite: false,
      });
      work.insert('recipeRevisions', {
        id: revisionId, recipeId, parentRevisionId: null, revisionNumber: 1,
        snapshotSchemaVersion: 1, snapshotJson: JSON.stringify(snapshot),
        createdAtUtcMillis: now, changeNotes: 'Saved from the community by your AI assistant',
        sourceCookId: null, sourceDishId: null,
      });
      return { recipeId, revisionId, savedFrom: { itemId, revisionId: detail.revisionId } };
    }, args => ({ recipeId: this.idFor(args, 'recipe'), revisionId: this.idFor(args, 'revision:1') }));
  }

  async connectionStatus(principal: McpPrincipal) {
    const grant = await this.grants.byId(principal.grantId);
    return {
      connected: true,
      connection: grant ? {
        id: grant.id, nickname: grant.nickname,
        via: grant.clientId ? 'oauth' : 'personal_access_token',
        clientId: grant.clientId, scopes: [...grant.scopes],
        createdAt: new Date(grant.createdAt).toISOString(),
        expiresAt: new Date(grant.expiresAt).toISOString(),
        lastUsedAt: grant.lastUsedAt === null ? null : new Date(grant.lastUsedAt).toISOString(),
      } : null,
      accountUserId: principal.userId,
      note: 'PitTech Pro is active for this account. If Pro lapses, this connection stops working until it is renewed; the library itself is never changed by that.',
    };
  }

  async capabilities(principal: McpPrincipal) {
    const visible = this.tools().filter(tool => tool.scopes.every(scope => hasScope(principal, scope)));
    return {
      product: 'PitTech',
      surface: 'mcp',
      yourScopes: [...principal.scopes],
      allScopes: [...MCP_SCOPES],
      toolsAvailableToYou: visible.map(tool => tool.name),
      dataAreas: ['meals and cook logs', 'recipes', 'cook plans', 'photos', 'private library sync'],
      limits: [
        'Your own library only — nothing here can see another account.',
        'Community content is read-only benchmarks; nothing can publish, like, comment or message.',
        'Equipment is read-only status; nothing can start, stop or set a grill.',
        'No billing, subscription, credential or account changes — those stay in the PitTech app.',
        'Reads come from the last synced snapshot; the phone may hold newer changes that have not synced yet.',
      ],
    };
  }

  async syncStatus(principal: McpPrincipal) {
    const status = await this.snapshots.status(principal.userId);
    if (status.version === 0) {
      return {
        hasSyncedLibrary: false, snapshotVersion: 0, snapshotUpdatedAt: null,
        recordCounts: {}, totalRecords: 0,
        note: 'Nothing has synced from the PitTech app yet. Ask the user to open PitTech while signed in so the first sync can run; until then there is no library to read.',
      };
    }
    const { envelope } = await this.snapshots.read(principal.userId);
    const counts: Record<string, number> = {};
    let total = 0;
    for (const record of envelope?.records ?? []) { if (record.deleted) continue; counts[record.recordType] = (counts[record.recordType] ?? 0) + 1; total++; }
    return {
      hasSyncedLibrary: true, snapshotVersion: status.version,
      snapshotUpdatedAt: status.updatedAt === null ? null : new Date(status.updatedAt).toISOString(),
      recordCounts: counts, totalRecords: total,
      note: 'This is the last snapshot the phone synced to the server. The phone may have newer changes that have not synced yet.',
    };
  }
}
