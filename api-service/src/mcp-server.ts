import { hasScope, type McpPrincipal } from './mcp-grants.ts';
import { ToolFailure, type PrivateLibraryService, type ToolDefinition } from './mcp-library.ts';

/**
 * MCP protocol core (issue #114, M2): JSON-RPC dispatch for the
 * Streamable HTTP transport, deliberately transport-agnostic so the
 * same core can be tested without HTTP and reused if a second
 * transport ever appears. Stateless by design: PitTech tools need no
 * server-side session, so initialize is a negotiation, not a login —
 * authentication already happened at the HTTP layer, per request,
 * before any of this runs.
 */

export const MCP_PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26'] as const;
export const MCP_LATEST_PROTOCOL = MCP_PROTOCOL_VERSIONS[0];
export const MCP_SERVER_INFO = { name: 'pittech', version: '1.0.0' } as const;

type RpcId = string | number | null;
interface RpcResponse { jsonrpc: '2.0'; id: RpcId; result?: unknown; error?: { code: number; message: string } }

const rpcError = (id: RpcId, code: number, message: string): RpcResponse => ({ jsonrpc: '2.0', id, error: { code, message } });
const rpcResult = (id: RpcId, result: unknown): RpcResponse => ({ jsonrpc: '2.0', id, result });

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** MCP tool results carry content blocks; structuredContent mirrors the
 * JSON for clients that prefer it. Tool-level failures are results with
 * isError, per spec — not protocol errors. */
function toolResult(value: unknown) {
  // A handler may return rich content blocks directly (e.g. an image
  // for get_photo): { content: [...], structuredContent?: {...} }.
  if (isRecord(value) && Array.isArray(value.content)) {
    return { content: value.content, structuredContent: isRecord(value.structuredContent) ? value.structuredContent : undefined, isError: false };
  }
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }], structuredContent: isRecord(value) ? value : { value }, isError: false };
}
function toolFailure(message: string) {
  return { content: [{ type: 'text', text: message }], isError: true };
}

export class McpServer {
  private library: PrivateLibraryService;
  constructor(library: PrivateLibraryService) { this.library = library; }

  private visibleTools(principal: McpPrincipal): ToolDefinition[] {
    return this.library.tools().filter(tool => tool.scopes.every(scope => hasScope(principal, scope)));
  }

  /** Handles one JSON-RPC message. Returns null for notifications
   * (which take no response). Batches are rejected: the current
   * protocol revision removed them. */
  async handle(principal: McpPrincipal, message: unknown): Promise<RpcResponse | null> {
    if (Array.isArray(message)) return rpcError(null, -32600, 'JSON-RPC batches are not supported');
    if (!isRecord(message) || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
      return rpcError(isRecord(message) && (typeof message.id === 'string' || typeof message.id === 'number') ? message.id : null, -32600, 'Invalid request');
    }
    const id: RpcId = typeof message.id === 'string' || typeof message.id === 'number' ? message.id : null;
    const isNotification = !('id' in message);
    const params = isRecord(message.params) ? message.params : {};
    try {
      switch (message.method) {
        case 'initialize': {
          const requested = typeof params.protocolVersion === 'string' ? params.protocolVersion : '';
          const protocolVersion = (MCP_PROTOCOL_VERSIONS as readonly string[]).includes(requested) ? requested : MCP_LATEST_PROTOCOL;
          return rpcResult(id, {
            protocolVersion,
            capabilities: { tools: {} },
            serverInfo: { ...MCP_SERVER_INFO },
            instructions: 'PitTech is the user\'s own BBQ cooking library: meals, recipes, logs, plans and photos from their account. Reads reflect the last synced snapshot. Nothing here can publish, control a grill, or change billing or account settings.',
          });
        }
        case 'ping': return rpcResult(id, {});
        case 'tools/list': {
          const tools = this.visibleTools(principal).map(tool => ({
            name: tool.name, title: tool.title, description: tool.description, inputSchema: tool.inputSchema,
          }));
          return rpcResult(id, { tools });
        }
        case 'tools/call': {
          const name = typeof params.name === 'string' ? params.name : '';
          const args = isRecord(params.arguments) ? params.arguments : {};
          const tool = this.library.tools().find(candidate => candidate.name === name);
          if (!tool) return isNotification ? null : rpcResult(id, toolFailure(`Unknown tool: ${name || '(missing name)'}`));
          if (!tool.scopes.every(scope => hasScope(principal, scope))) {
            return isNotification ? null : rpcResult(id, toolFailure(`This connection does not include the access ${tool.name} needs (${tool.scopes.join(', ')}). Ask the user to reconnect with that access enabled, from PitTech → Connect your AI.`));
          }
          try {
            const value = await tool.handler(principal, args);
            return isNotification ? null : rpcResult(id, toolResult(value));
          } catch (failure) {
            if (failure instanceof ToolFailure) return isNotification ? null : rpcResult(id, toolFailure(failure.message));
            throw failure;
          }
        }
        default:
          return isNotification ? null : rpcError(id, -32601, `Method not found: ${message.method}`);
      }
    } catch {
      return isNotification ? null : rpcError(id, -32603, 'Internal error');
    }
  }
}
