import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const read = (path: string) => readFile(new URL('../../' + path, import.meta.url), 'utf8');
const cases = JSON.parse(await read('contracts/fixtures/insights-calculations-cases-v1.json'));
test('insights calculation contract stays byte-identical in both native bundles', async () => {
  const contract = await read('contracts/insights-calculations-v1.json');
  assert.equal(contract, await read('app/src/main/assets/insights-calculations-v1.json'));
  assert.equal(contract, await read('ios/Contracts/insights-calculations-v1.json'));
  assert.equal(contract, await read('app/src/test/resources/insights-calculations-v1.json'));
  assert.equal(JSON.parse(contract).calculationVersion, 1);
});
test('insights golden cases pin one archive per scenario with versioned expectations', () => {
  assert.equal(cases.contractVersion, 1);
  assert.equal(cases.cases.length, 14);
  const names = new Set<string>();
  for (const c of cases.cases) {
    assert.ok(c.name && !names.has(c.name), 'duplicate case: ' + c.name); names.add(c.name);
    assert.ok(Number.isFinite(c.asOfUtcMillis), c.name);
    assert.ok(c.archive.cooks.length >= 1, c.name);
    assert.equal(c.expected.calculationVersion, 1, c.name);
    assert.equal(c.expected.cooks.length, c.archive.cooks.length, c.name);
    for (const cook of c.expected.cooks) {
      assert.ok(cook.cookId, c.name);
      assert.ok(Array.isArray(cook.channels), c.name);
      assert.ok(Array.isArray(cook.dishes) && cook.dishes.length >= 1, c.name);
    }
  }
});
