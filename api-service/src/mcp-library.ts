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
  insert(type: string, payload: Payload, recordIdOverride?: string) {
    const recordId = recordIdOverride ?? this.keyOf(type, payload);
    this.rows.push({ recordType: type as SyncRecord['recordType'], recordId, deleted: false, payload, assetIds: [], version: { counter: this.stamp, clientId: MCP_WRITER_CLIENT_ID } });
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
  private clock: () => number;
  constructor(grants: McpGrants, snapshots: SnapshotReader,
    options: { writer?: SnapshotReadWriter; writesEnabled?: boolean; clock?: () => number } = {}) {
    this.grants = grants; this.snapshots = snapshots;
    this.writer = options.writer; this.writesEnabled = options.writesEnabled === true;
    this.clock = options.clock ?? Date.now;
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
        name: 'remove_dish', title: 'Remove a dish',
        description: 'Removes one dish from its meal, along with its ingredients, targets and results.',
        inputSchema: { type: 'object', properties: { dishId: { type: 'string' }, ...WRITE_ARGS }, required: ['dishId'], additionalProperties: false },
        scopes: ['library:delete'],
        handler: async (principal: McpPrincipal, args: Record<string, unknown>) => this.removeDish(principal, args),
      },
      ] as ToolDefinition[]) : []),
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
