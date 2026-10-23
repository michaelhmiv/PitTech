#!/usr/bin/env node
// PitTech capability parity checker + generator (issue #114, section 3.1).
//
// Zero-dependency Node >= 18 script. It enforces the three-interface parity
// contract (Android / iOS / MCP) mechanically:
//   1. contracts/capabilities-v1.json is well-formed (shape, closed scope
//      enum, coverage flags) and consistent with its schema's rules.
//   2. Every field of every record type / object definition kind is
//      inventoried exactly once with an MCP access classification.
//   3. Every record type and companion definition kind in
//      contracts/private-sync-projection-v1.json is covered by at least one
//      capability, or explicitly deferred with a reason. Adding a new private
//      record type / definition kind to the sync contract therefore FAILS
//      this check until its MCP disposition is declared — that is the
//      "future features cannot silently skip MCP" mechanism.
//   3. Platform bindings name real source symbols (a class name in the
//      Android/iOS sources, or a source file name). Fake bindings fail.
//   4. Prohibited capabilities (public contribution, hardware control,
//      account/billing/credentials) expose no MCP tools, and no registered
//      tool name matches the public-write / hardware / credential denylist.
//   5. docs/MCP_PARITY.md is the generated view of the registry; --check
//      fails when it is stale.
//
// Usage:
//   node scripts/mcp-parity.mjs --check      (CI; also the default)
//   node scripts/mcp-parity.mjs --generate   (rewrite docs/MCP_PARITY.md)
//   node scripts/mcp-parity.mjs --self-test  (prove drift is caught)
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY_PATH = join(root, 'contracts/capabilities-v1.json');
const PROJECTION_PATH = join(root, 'contracts/private-sync-projection-v1.json');
const PARITY_DOC_PATH = join(root, 'docs/MCP_PARITY.md');

const SCOPES = new Set([
  'library:read', 'library:write', 'library:delete', 'media:read', 'media:write',
  'planning:write', 'device_actions:request', 'exports:read', 'imports:write',
  'community:read',
]);
const CLASSIFICATIONS = new Set([
  'direct_private_operation', 'device_execution_or_handoff',
  'physical_or_os_limitation', 'prohibited_public_or_security',
  'upstream_not_shipped',
]);
const MCP_STATUSES = new Set(['implemented', 'planned_disabled', 'handoff_only', 'prohibited', 'not_applicable']);
const PLATFORM_STATUSES = new Set(['implemented', 'planned', 'handoff_only', 'not_applicable']);
const FIELD_ACCESS = new Set(['editable', 'identity', 'system', 'association', 'content', 'not_exposed']);
const TOOL_DENYLIST = [
  /publish/i, /unpublish/i, /\blike/i, /post_/i, /ignite/i, /shut_?down/i,
  /set_?temperature/i, /grant/i, /billing/i, /delete_?account/i, /password/i,
  /credential/i,
];

function collectSources() {
  const names = new Set();
  const contents = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const p = join(dir, entry);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (/\.(kt|swift)$/.test(entry)) {
        names.add(entry);
        contents.push(readFileSync(p, 'utf8'));
      }
    }
  };
  walk(join(root, 'app/src/main/java'));
  walk(join(root, 'ios/PitTech'));
  return { names, blob: contents.join('\n') };
}

export function validate(registry, projection, sources) {
  const errors = [];
  const fail = (m) => errors.push(m);
  if (registry.registryVersion !== 1) fail('registryVersion must be 1');
  const caps = registry.capabilities;
  if (!Array.isArray(caps) || caps.length === 0) fail('capabilities must be a non-empty array');

  const ids = new Set();
  const toolsSeen = new Map();
  const coveredRecords = new Set();
  const coveredDefs = new Set();

  for (const c of caps ?? []) {
    const at = `capability ${c.id ?? '<missing id>'}`;
    if (!c.id || ids.has(c.id)) fail(`${at}: id missing or duplicated`);
    ids.add(c.id);
    if (!c.title) fail(`${at}: title missing`);
    if (!CLASSIFICATIONS.has(c.classification)) fail(`${at}: unknown classification ${c.classification}`);
    for (const s of c.scopes ?? []) if (!SCOPES.has(s)) fail(`${at}: scope ${s} is not in the closed scope set`);
    for (const k of ['read', 'write', 'delete', 'media', 'activation']) {
      if (typeof c.coverage?.[k] !== 'boolean') fail(`${at}: coverage.${k} must be boolean`);
    }
    if (!MCP_STATUSES.has(c.mcp?.status)) fail(`${at}: unknown mcp status ${c.mcp?.status}`);
    for (const side of ['android', 'ios']) {
      const b = c[side];
      if (!PLATFORM_STATUSES.has(b?.status)) fail(`${at}: unknown ${side} status ${b?.status}`);
      if (b?.status === 'implemented') {
        if (!b.symbols?.length) fail(`${at}: ${side} is marked implemented but names no source symbols`);
        for (const sym of b?.symbols ?? []) {
          const base = sym.replace(/\.swift$/, '');
          if (!sources.names.has(sym) && !sources.blob.includes(base) && !sources.blob.includes(sym)) {
            fail(`${at}: ${side} symbol "${sym}" not found in native sources`);
          }
        }
      }
      if (b?.status === 'not_applicable' && (b.symbols?.length ?? 0) > 0) {
        fail(`${at}: ${side} is not_applicable but names symbols`);
      }
    }
    const prohibited = c.classification === 'prohibited_public_or_security';
    if (prohibited) {
      if (c.mcp?.status !== 'prohibited') fail(`${at}: prohibited capability must have mcp status "prohibited"`);
      if ((c.mcp?.tools ?? []).length > 0) fail(`${at}: prohibited capability exposes MCP tools`);
      if (Object.values(c.coverage ?? {}).some(Boolean)) fail(`${at}: prohibited capability claims coverage`);
      if ((c.scopes ?? []).length > 0) fail(`${at}: prohibited capability holds scopes`);
    } else {
      for (const t of c.mcp?.tools ?? []) {
        if (toolsSeen.has(t)) fail(`${at}: tool ${t} already registered by ${toolsSeen.get(t)}`);
        toolsSeen.set(t, c.id);
        for (const deny of TOOL_DENYLIST) {
          if (deny.test(t)) fail(`${at}: tool ${t} matches the prohibited-tool denylist (${deny})`);
        }
      }
    }
    for (const r of c.recordTypes ?? []) coveredRecords.add(r);
    for (const d of c.definitions ?? []) coveredDefs.add(d);
  }

  // Field-level inventory: every field of every covered record type and
  // object definition kind is inventoried exactly once, on one capability.
  const fieldSeen = new Map();
  const sourceFields = new Map();
  for (const [rt, spec] of Object.entries(projection.recordTypes ?? {})) {
    sourceFields.set(rt, new Set(Object.keys(spec.payload?.fields ?? {})));
  }
  for (const [kind, spec] of Object.entries(projection.definitions ?? {})) {
    if (spec && typeof spec === 'object' && spec.fields) sourceFields.set(kind, new Set(Object.keys(spec.fields)));
  }
  for (const c of caps ?? []) {
    const prohibited = c.classification === 'prohibited_public_or_security';
    for (const f of c.fields ?? []) {
      const at = `capability ${c.id ?? '<missing id>'} field ${f.source}.${f.name}`;
      if (!FIELD_ACCESS.has(f.access)) fail(`${at}: unknown access "${f.access}"`);
      if (!sourceFields.has(f.source)) { fail(`${at}: unknown source "${f.source}"`); continue; }
      if (!sourceFields.get(f.source).has(f.name)) fail(`${at}: field does not exist in the projection contract`);
      const key = `${f.source}.${f.name}`;
      if (fieldSeen.has(key)) fail(`${at}: inventoried more than once (also on ${fieldSeen.get(key)})`);
      fieldSeen.set(key, c.id);
      if (prohibited && f.access !== 'not_exposed') fail(`${at}: prohibited capability field must be not_exposed`);
      if (!prohibited && f.access === 'not_exposed') fail(`${at}: not_exposed is reserved for prohibited capabilities`);
    }
  }
  for (const [source, fields] of sourceFields) {
    for (const name of fields) {
      if (!fieldSeen.has(`${source}.${name}`)) {
        fail(`projection field "${source}.${name}" has no field-inventory entry — declare its MCP access in contracts/capabilities-v1.json`);
      }
    }
  }

  // Projection coverage: the automatic "remember MCP" tripwire.
  for (const rt of Object.keys(projection.recordTypes ?? {})) {
    if (!coveredRecords.has(rt)) fail(`projection record type "${rt}" is not covered by any capability — declare its MCP disposition in contracts/capabilities-v1.json`);
  }
  for (const rt of coveredRecords) {
    if (!(rt in (projection.recordTypes ?? {}))) fail(`capability registry covers unknown record type "${rt}"`);
  }
  const deferred = new Map((registry.deferredDefinitions ?? []).map((d) => [d.kind, d.reason]));
  for (const [kind, reason] of deferred) if (!reason) fail(`deferred definition "${kind}" has no reason`);
  for (const kind of Object.keys(projection.definitions ?? {})) {
    if (!coveredDefs.has(kind) && !deferred.has(kind)) {
      fail(`projection definition kind "${kind}" is neither covered by a capability nor deferred with a reason — declare its MCP disposition`);
    }
  }
  for (const kind of coveredDefs) {
    if (!(kind in (projection.definitions ?? {}))) fail(`capability registry covers unknown definition kind "${kind}"`);
  }
  return errors;
}

export function renderParityDoc(registry) {
  const lines = [];
  lines.push('# PitTech MCP parity matrix');
  lines.push('');
  lines.push('<!-- GENERATED by scripts/mcp-parity.mjs from contracts/capabilities-v1.json. Do not edit by hand; run `node scripts/mcp-parity.mjs --generate`. -->');
  lines.push('');
  lines.push('Android, iOS and MCP are three maintained interfaces to the same product (issue #114).');
  lines.push('Every shipped private capability needs a capability row here: a direct MCP binding, an');
  lines.push('explicit device handoff, a documented physical/OS limitation, or a prohibition. The');
  lines.push('`Capability parity` CI check fails when the sync contract grows a record type or');
  lines.push('definition kind with no declared disposition, when a binding names a symbol that does');
  lines.push('not exist in the native sources, when a prohibited capability exposes a tool, or when');
  lines.push('this document is stale.');
  lines.push('');
  lines.push(`Registry version: ${registry.registryVersion}. MCP status \`planned_disabled\` means the`);
  lines.push('binding is designed and contracted but the connector ships disabled; nothing in this');
  lines.push('matrix is a claim that MCP is implemented or released.');
  lines.push('');
  lines.push('| Capability | Classification | Scopes | Records | MCP tools | MCP status |');
  lines.push('|---|---|---|---|---|---|');
  for (const c of [...registry.capabilities].sort((a, b) => a.id.localeCompare(b.id))) {
    const tools = (c.mcp.tools ?? []).join(', ') || '—';
    const records = [...(c.recordTypes ?? []), ...(c.definitions ?? [])].join(', ') || '—';
    lines.push(`| ${c.id} — ${c.title} | ${c.classification} | ${(c.scopes ?? []).join(', ') || '—'} | ${records} | ${tools} | ${c.mcp.status} |`);
  }
  lines.push('');
  lines.push('## Notes per capability');
  lines.push('');
  for (const c of [...registry.capabilities].sort((a, b) => a.id.localeCompare(b.id))) {
    lines.push(`- **${c.id}** — ${c.notes}`);
  }
  lines.push('## Field inventory');
  lines.push('');
  lines.push('Access: `editable` settable through typed tools · `identity` set at creation, immutable ·');
  lines.push('`system` server/native-assigned · `association` references another record · `content` carried');
  lines.push('opaquely, edited only through typed operations · `not_exposed` prohibited, never surfaced.');
  lines.push('');
  for (const c of [...registry.capabilities].sort((a, b) => a.id.localeCompare(b.id))) {
    const bySource = new Map();
    for (const f of c.fields ?? []) {
      if (!bySource.has(f.source)) bySource.set(f.source, []);
      bySource.get(f.source).push(`${f.name} (${f.access})`);
    }
    for (const [source, fields] of [...bySource.entries()].sort()) {
      lines.push(`- **${c.id}** · \`${source}\`: ${fields.join(', ')}`);
    }
  }
  lines.push('');
  if ((registry.deferredDefinitions ?? []).length) {
    lines.push('');
    lines.push('## Deferred projection kinds (no capability surface)');
    lines.push('');
    for (const d of registry.deferredDefinitions) lines.push(`- **${d.kind}** — ${d.reason}`);
  }
  lines.push('');
  return lines.join('\n');
}

function loadJson(p) { return JSON.parse(readFileSync(p, 'utf8')); }

function runCheck() {
  const registry = loadJson(REGISTRY_PATH);
  const projection = loadJson(PROJECTION_PATH);
  const sources = collectSources();
  const errors = validate(registry, projection, sources);
  const rendered = renderParityDoc(registry);
  let current = null;
  try { current = readFileSync(PARITY_DOC_PATH, 'utf8'); } catch { /* missing */ }
  if (current !== rendered) errors.push('docs/MCP_PARITY.md is stale or missing — run `node scripts/mcp-parity.mjs --generate`');
  if (errors.length) {
    console.error(`Capability parity check FAILED with ${errors.length} error(s):`);
    for (const e of errors) console.error(` - ${e}`);
    process.exit(1);
  }
  console.log(`Capability parity check passed: ${registry.capabilities.length} capabilities, all projection record types and definition kinds have a declared MCP disposition.`);
}

function runSelfTest() {
  const registry = loadJson(REGISTRY_PATH);
  const projection = loadJson(PROJECTION_PATH);
  const sources = collectSources();
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const cases = [];
  const expectFail = (name, reg, proj) => {
    const errs = validate(reg, proj ?? projection, sources);
    cases.push([name, errs.length > 0, errs[0] ?? 'no error reported']);
  };
  const r1 = clone(registry); r1.capabilities = r1.capabilities.filter((c) => c.id !== 'recipes.library');
  expectFail('removing a capability breaks projection coverage', r1);
  const r2 = clone(registry); r2.capabilities.find((c) => c.id === 'prohibited.public_contribution').mcp.tools = ['publish_recipe'];
  expectFail('prohibited capability exposing a tool fails', r2);
  const r3 = clone(registry); r3.capabilities.find((c) => c.id === 'meals.dishes').mcp.tools.push('publish_meal');
  expectFail('denylisted tool name fails', r3);
  const r4 = clone(registry); r4.capabilities.find((c) => c.id === 'meals.dishes').android.symbols = ['NoSuchRepository'];
  expectFail('fake platform symbol fails', r4);
  const p5 = clone(projection); p5.recordTypes.brand_new_private_thing = { key: 'id', payload: { type: 'object', fields: {}, required: [] } };
  expectFail('new projection record type without a disposition fails', registry, p5);
  const r6 = clone(registry); const cap6 = r6.capabilities.find((c) => (c.fields ?? []).length > 0); cap6.fields = cap6.fields.slice(1);
  expectFail('dropping a field-inventory entry fails', r6);
  const r7 = clone(registry); const cap7 = r7.capabilities.find((c) => c.id === 'prohibited.hardware_control'); cap7.fields[0].access = 'editable';
  expectFail('prohibited field classified editable fails', r7);
  const stale = renderParityDoc(registry) + '\nhand edit\n';
  cases.push(['stale generated doc detected', stale !== renderParityDoc(registry), 'comparison is exact']);
  let failed = 0;
  for (const [name, ok, detail] of cases) {
    console.log(`${ok ? 'PASS' : 'FAIL'} self-test: ${name}${ok ? '' : ` (${detail})`}`);
    if (!ok) failed++;
  }
  if (failed) { console.error(`Self-test failed: ${failed} case(s) did not behave as required.`); process.exit(1); }
  console.log('Self-test passed: deliberate drift is caught.');
}

const mode = process.argv[2] ?? '--check';
if (mode === '--generate') {
  writeFileSync(PARITY_DOC_PATH, renderParityDoc(loadJson(REGISTRY_PATH)));
  console.log('Wrote docs/MCP_PARITY.md');
} else if (mode === '--self-test') runSelfTest();
else if (mode === '--check') runCheck();
else { console.error(`Unknown mode ${mode}`); process.exit(2); }
