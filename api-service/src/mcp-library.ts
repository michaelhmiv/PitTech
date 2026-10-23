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
        name: 'get_sync_status', title: 'Sync status',
        description: 'Reports the state of the user\'s synced library on the server: whether anything has synced yet, the snapshot version and time, and how many records of each kind it holds. Never claims live phone state — the phone may have newer unsynced changes.',
        inputSchema: { ...NO_ARGS_SCHEMA }, scopes: ['library:read'],
        handler: async principal => this.syncStatus(principal),
      },
    ];
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
