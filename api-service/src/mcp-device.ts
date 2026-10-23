import type { Pool } from 'pg';

/** MCP device execution (issue #114, §7): a separate account-bound
 * command path — never an enabled flag smuggled into sync. A phone
 * registers itself (opaque id, name, platform) and opts into named
 * classes of remote app actions. Commands use a closed action enum
 * with per-action schemas, carry an expiry, are claimed by exactly
 * the target device, and count as applied only when that device
 * posts a receipt. States: saved → queued_for_device → applied /
 * needs_attention / failed, with expired and canceled as terminal
 * exits. No action here can touch grill hardware — the enum is
 * app-behavior only, by construction. */

export type DeviceActionState = 'saved' | 'queued_for_device' | 'applied' | 'needs_attention' | 'expired' | 'canceled' | 'failed';
export const DEVICE_ACTION_STATES: readonly DeviceActionState[] = ['saved', 'queued_for_device', 'applied', 'needs_attention', 'expired', 'canceled', 'failed'];

export interface McpDeviceRecord {
  deviceId: string; name: string; platform: string;
  actionClasses: string[]; lastSeenAt: number | null;
  createdAt: number; updatedAt: number;
}
export interface McpDeviceCommandRecord {
  id: string; userId: string; deviceId: string | null; grantId: string;
  action: string; params: Record<string, unknown>; state: DeviceActionState;
  createdAt: number; expiresAt: number;
  claimedAt: number | null; claimedBy: string | null;
  resolvedAt: number | null; receipt: Record<string, unknown> | null;
}
export interface McpDeviceStore {
  upsertDevice(userId: string, device: McpDeviceRecord): Promise<McpDeviceRecord>;
  listDevices(userId: string): Promise<McpDeviceRecord[]>;
  deviceFor(userId: string, deviceId: string): Promise<McpDeviceRecord | undefined>;
  removeDevice(userId: string, deviceId: string, at: number): Promise<boolean>;
  touchDevice(userId: string, deviceId: string, at: number): Promise<void>;
  createCommand(record: McpDeviceCommandRecord): Promise<void>;
  commandFor(userId: string, id: string): Promise<McpDeviceCommandRecord | undefined>;
  listCommands(userId: string, state?: string): Promise<McpDeviceCommandRecord[]>;
  cancelCommand(userId: string, id: string, at: number): Promise<boolean>;
  expireStale(userId: string, now: number): Promise<void>;
  claim(userId: string, deviceId: string, classes: readonly string[], now: number, limit: number): Promise<McpDeviceCommandRecord[]>;
  resolve(userId: string, deviceId: string, commandId: string, receipt: Record<string, unknown>, at: number): Promise<boolean>;
}

type DeviceRow = { device_id: string; name: string; platform: string; action_classes: string[]; last_seen_at: string | null; created_at: string; updated_at: string };
type CommandRow = { id: string; device_id: string | null; grant_id: string; action: string; params: Record<string, unknown>; state: DeviceActionState; created_at: string; expires_at: string; claimed_at: string | null; claimed_by: string | null; resolved_at: string | null; receipt: Record<string, unknown> | null };
const toDevice = (row: DeviceRow): McpDeviceRecord => ({
  deviceId: row.device_id, name: row.name, platform: row.platform, actionClasses: row.action_classes ?? [],
  lastSeenAt: row.last_seen_at === null ? null : Number(row.last_seen_at),
  createdAt: Number(row.created_at), updatedAt: Number(row.updated_at),
});
const toCommand = (userId: string) => (row: CommandRow): McpDeviceCommandRecord => ({
  id: row.id, userId, deviceId: row.device_id, grantId: row.grant_id, action: row.action, params: row.params ?? {},
  state: row.state, createdAt: Number(row.created_at), expiresAt: Number(row.expires_at),
  claimedAt: row.claimed_at === null ? null : Number(row.claimed_at), claimedBy: row.claimed_by,
  resolvedAt: row.resolved_at === null ? null : Number(row.resolved_at), receipt: row.receipt,
});
const COMMAND_COLUMNS = 'id, device_id, grant_id, action, params, state, created_at, expires_at, claimed_at, claimed_by, resolved_at, receipt';

export class PostgresMcpDeviceStore implements McpDeviceStore {
  private pool: Pool;
  constructor(pool: Pool) { this.pool = pool; }
  async upsertDevice(userId: string, device: McpDeviceRecord): Promise<McpDeviceRecord> {
    const row = (await this.pool.query(
      `INSERT INTO auth.mcp_devices(user_id, device_id, name, platform, action_classes, last_seen_at, created_at, updated_at)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8)
       ON CONFLICT (user_id, device_id) DO UPDATE SET name=EXCLUDED.name, platform=EXCLUDED.platform,
         action_classes=EXCLUDED.action_classes, last_seen_at=EXCLUDED.last_seen_at, updated_at=EXCLUDED.updated_at
       RETURNING device_id, name, platform, action_classes, last_seen_at, created_at, updated_at`,
      [userId, device.deviceId, device.name, device.platform, JSON.stringify(device.actionClasses), device.lastSeenAt, device.createdAt, device.updatedAt])).rows[0] as DeviceRow;
    return toDevice(row);
  }
  async listDevices(userId: string): Promise<McpDeviceRecord[]> {
    const rows = (await this.pool.query(
      'SELECT device_id, name, platform, action_classes, last_seen_at, created_at, updated_at FROM auth.mcp_devices WHERE user_id=$1 ORDER BY created_at',
      [userId])).rows as DeviceRow[];
    return rows.map(toDevice);
  }
  async deviceFor(userId: string, deviceId: string): Promise<McpDeviceRecord | undefined> {
    const row = (await this.pool.query(
      'SELECT device_id, name, platform, action_classes, last_seen_at, created_at, updated_at FROM auth.mcp_devices WHERE user_id=$1 AND device_id=$2',
      [userId, deviceId])).rows[0] as DeviceRow | undefined;
    return row ? toDevice(row) : undefined;
  }
  async removeDevice(userId: string, deviceId: string, at: number): Promise<boolean> {
    // A device that goes away takes its unclaimed commands with it:
    // queued work is canceled, never transferred to another device.
    await this.pool.query(
      `UPDATE auth.mcp_device_commands SET state='canceled', resolved_at=$3, receipt='{"outcome":"canceled","detail":"device unregistered"}'::jsonb
       WHERE user_id=$1 AND device_id=$2 AND state='queued_for_device'`, [userId, deviceId, at]);
    const result = await this.pool.query('DELETE FROM auth.mcp_devices WHERE user_id=$1 AND device_id=$2', [userId, deviceId]);
    return (result.rowCount ?? 0) > 0;
  }
  async touchDevice(userId: string, deviceId: string, at: number): Promise<void> {
    await this.pool.query('UPDATE auth.mcp_devices SET last_seen_at=$3, updated_at=$3 WHERE user_id=$1 AND device_id=$2', [userId, deviceId, at]);
  }
  async createCommand(record: McpDeviceCommandRecord): Promise<void> {
    await this.pool.query(
      `INSERT INTO auth.mcp_device_commands(id, user_id, device_id, grant_id, action, params, state, created_at, expires_at)
       VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
      [record.id, record.userId, record.deviceId, record.grantId, record.action, JSON.stringify(record.params), record.state, record.createdAt, record.expiresAt]);
  }
  async commandFor(userId: string, id: string): Promise<McpDeviceCommandRecord | undefined> {
    const row = (await this.pool.query(
      `SELECT ${COMMAND_COLUMNS} FROM auth.mcp_device_commands WHERE user_id=$1 AND id=$2`, [userId, id])).rows[0] as CommandRow | undefined;
    return row ? toCommand(userId)(row) : undefined;
  }
  async listCommands(userId: string, state?: string): Promise<McpDeviceCommandRecord[]> {
    const rows = (await this.pool.query(
      `SELECT ${COMMAND_COLUMNS} FROM auth.mcp_device_commands WHERE user_id=$1 AND ($2::text IS NULL OR state=$2) ORDER BY created_at DESC LIMIT 100`,
      [userId, state ?? null])).rows as CommandRow[];
    return rows.map(toCommand(userId));
  }
  async cancelCommand(userId: string, id: string, at: number): Promise<boolean> {
    const result = await this.pool.query(
      `UPDATE auth.mcp_device_commands SET state='canceled', resolved_at=$3, receipt='{"outcome":"canceled","detail":"canceled by request"}'::jsonb
       WHERE user_id=$1 AND id=$2 AND state IN ('saved','queued_for_device')`, [userId, id, at]);
    return (result.rowCount ?? 0) > 0;
  }
  async expireStale(userId: string, now: number): Promise<void> {
    await this.pool.query(
      `UPDATE auth.mcp_device_commands SET state='expired', resolved_at=$2 WHERE user_id=$1 AND state IN ('saved','queued_for_device') AND expires_at<=$2`,
      [userId, now]);
  }
  async claim(userId: string, deviceId: string, classes: readonly string[], now: number, limit: number): Promise<McpDeviceCommandRecord[]> {
    await this.expireStale(userId, now);
    // A claim is re-issuable after 10 minutes without a receipt (the
    // claiming app died mid-apply); the device-side ledger keeps the
    // side effect itself idempotent.
    const rows = (await this.pool.query(
      `UPDATE auth.mcp_device_commands SET claimed_at=$3, claimed_by=$2
       WHERE id IN (
         SELECT id FROM auth.mcp_device_commands
         WHERE user_id=$1 AND device_id=$2 AND state='queued_for_device' AND expires_at>$3
           AND split_part(action, '.', 1) = ANY($5)
           AND (claimed_at IS NULL OR claimed_at<=$3-600000)
         ORDER BY created_at LIMIT $4
       ) RETURNING ${COMMAND_COLUMNS}`,
      [userId, deviceId, now, limit, [...classes]])).rows as CommandRow[];
    return rows.map(toCommand(userId));
  }
  async resolve(userId: string, deviceId: string, commandId: string, receipt: Record<string, unknown>, at: number): Promise<boolean> {
    const outcome = receipt.outcome;
    if (outcome !== 'applied' && outcome !== 'needs_attention' && outcome !== 'failed') return false;
    const result = await this.pool.query(
      `UPDATE auth.mcp_device_commands SET state=$4, resolved_at=$5, receipt=$6
       WHERE user_id=$1 AND id=$2 AND device_id=$3 AND claimed_by=$3 AND state='queued_for_device'`,
      [userId, commandId, deviceId, outcome, at, JSON.stringify(receipt)]);
    return (result.rowCount ?? 0) > 0;
  }
}

/** The opt-in class an action belongs to (what a device registers
 * for): the family before the dot. */
export function actionClassOf(action: string): string {
  return action.split('.', 1)[0];
}

const reqString = (params: Record<string, unknown>, field: string): string => {
  const value = params[field];
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${field} is required.`);
  return value;
};
const optString = (params: Record<string, unknown>, field: string): string | undefined => {
  const value = params[field];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') throw new Error(`${field} must be a string.`);
  return value;
};
const reqInt = (params: Record<string, unknown>, field: string, min: number, max: number): number => {
  const value = params[field];
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${field} must be an integer between ${min} and ${max}.`);
  return value;
};
const onlyKeys = (params: Record<string, unknown>, allowed: string[]) => {
  for (const key of Object.keys(params)) if (!allowed.includes(key)) throw new Error(`Unknown parameter: ${key}. Allowed: ${allowed.join(', ')}.`);
};

/** The closed action enum with per-action schemas (§7). Returns the
 * normalized params to store; throws a user-facing Error otherwise.
 * There is deliberately no free-form action: an action the enum does
 * not name cannot be requested, full stop. */
export function validateDeviceAction(action: unknown, params: unknown): { action: string; params: Record<string, unknown> } {
  if (typeof action !== 'string') throw new Error('action is required.');
  if (typeof params !== 'object' || params === null || Array.isArray(params)) throw new Error('params must be an object.');
  const p = params as Record<string, unknown>;
  switch (action) {
    case 'reminder.activate': case 'reminder.cancel': case 'reminder.acknowledge':
      onlyKeys(p, ['ruleId']); return { action, params: { ruleId: reqString(p, 'ruleId') } };
    case 'reminder.snooze':
      onlyKeys(p, ['ruleId', 'minutes']); return { action, params: { ruleId: reqString(p, 'ruleId'), minutes: reqInt(p, 'minutes', 1, 1440) } };
    case 'timer.start': {
      onlyKeys(p, ['label', 'durationMinutes', 'mealId']);
      const label = reqString(p, 'label');
      if (label.length > 80) throw new Error('label must be at most 80 characters.');
      const out: Record<string, unknown> = { label, durationMinutes: reqInt(p, 'durationMinutes', 1, 1440) };
      const mealId = optString(p, 'mealId'); if (mealId) out.mealId = mealId;
      return { action, params: out };
    }
    case 'timer.stop':
      onlyKeys(p, ['timerId']); return { action, params: { timerId: reqString(p, 'timerId') } };
    case 'guidance.apply_playbook':
      onlyKeys(p, ['playbookId', 'mealId']); return { action, params: { playbookId: reqString(p, 'playbookId'), mealId: reqString(p, 'mealId') } };
    case 'collection.pause': case 'collection.resume':
      onlyKeys(p, []); return { action, params: {} };
    case 'collection.set_cadence': {
      // The products' real cadence set (GrillSamplingPolicy): a
      // requested cadence the apps cannot honor is refused here
      // rather than silently rounded on the device.
      onlyKeys(p, ['intervalSeconds']);
      const seconds = p.intervalSeconds;
      if (typeof seconds !== 'number' || ![15, 30, 60, 120, 300].includes(seconds)) throw new Error('intervalSeconds must be one of 15, 30, 60, 120 or 300.');
      return { action, params: { intervalSeconds: seconds } };
    }
    case 'probe.assign':
      onlyKeys(p, ['probeId', 'dishId']); return { action, params: { probeId: reqString(p, 'probeId'), dishId: reqString(p, 'dishId') } };
    case 'probe.unassign':
      onlyKeys(p, ['probeId']); return { action, params: { probeId: reqString(p, 'probeId') } };
    case 'preferences.update': {
      onlyKeys(p, ['themeMode', 'temperatureUnit', 'weightUnit']);
      const out: Record<string, unknown> = {};
      const theme = optString(p, 'themeMode');
      if (theme !== undefined) { if (!['system', 'light', 'dark'].includes(theme)) throw new Error('themeMode must be "system", "light" or "dark".'); out.themeMode = theme; }
      const temp = optString(p, 'temperatureUnit');
      if (temp !== undefined) { if (!['°F', '°C'].includes(temp)) throw new Error('temperatureUnit must be "°F" or "°C".'); out.temperatureUnit = temp; }
      const weight = optString(p, 'weightUnit');
      if (weight !== undefined) { if (!['lb', 'kg', 'oz', 'g'].includes(weight)) throw new Error('weightUnit must be "lb", "kg", "oz" or "g".'); out.weightUnit = weight; }
      if (!Object.keys(out).length) throw new Error('preferences.update needs at least one of themeMode, temperatureUnit, weightUnit.');
      return { action, params: out };
    }
    default:
      throw new Error(`Unsupported action "${action}". Supported: reminder.activate, reminder.cancel, reminder.snooze, reminder.acknowledge, timer.start, timer.stop, guidance.apply_playbook, collection.pause, collection.resume, collection.set_cadence, probe.assign, probe.unassign, preferences.update.`);
  }
}

/** Actions that bind to a meal and therefore refuse finished meals. */
export function actionMealId(action: string, params: Record<string, unknown>): string | undefined {
  if (action.startsWith('reminder.')) return undefined; // bound via the rule's meal, checked by the caller through the rule
  if (action === 'timer.start') return typeof params.mealId === 'string' ? params.mealId : undefined;
  if (action === 'guidance.apply_playbook') return typeof params.mealId === 'string' ? params.mealId : undefined;
  return undefined;
}
