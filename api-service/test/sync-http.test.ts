import { test } from 'node:test';
import assert from 'node:assert/strict';
import { privateSyncRoute } from '../src/sync-http.ts';
const id = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
test('private sync routes are exact and template-only, with no owner/query/source parameter', () => {
  assert.equal(privateSyncRoute('/v1/sync', 'GET'), '/v1/sync');
  assert.equal(privateSyncRoute(`/v1/sync/photos/${id}/parts/15`, 'PUT'), '/v1/sync/photos/:id/parts/:index');
  for (const path of [`/v1/sync?userId=${id}`, `/v1/sync/photos/${id}/parts/16`, `/v1/sync/photos/${id}/parts/01`, `/v1/sync/photos/${id}/parts/-1`, `/v1/sync/photos/${id}/complete?user=${id}`, `/v1/sync/photos/${id.toUpperCase()}`, '/v1/sync/photos/../../anything']) assert.equal(privateSyncRoute(path, 'PUT'), undefined);
  assert.equal(privateSyncRoute(`/v1/sync/photos/${id}/complete`, 'GET'), undefined);
  assert.equal(privateSyncRoute('/v1/sync', 'DELETE'), undefined);
});
