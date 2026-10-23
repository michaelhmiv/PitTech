import { MCP_SCOPES, hasScope, type McpGrants, type McpPrincipal, type McpScope } from './mcp-grants.ts';
import type { SyncEnvelope } from './sync-envelope.ts';

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

export class PrivateLibraryService {
  private grants: McpGrants; private snapshots: SnapshotReader;
  constructor(grants: McpGrants, snapshots: SnapshotReader) { this.grants = grants; this.snapshots = snapshots; }

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
