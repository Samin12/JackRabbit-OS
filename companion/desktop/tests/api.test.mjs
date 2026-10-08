// Paging rules of the desktop sync API client (no DOM needed).
// Run: node --test companion/desktop/tests/*.mjs

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { EVENTS_PAGE, blobPath, pageHasMore, unwrapStreamPayload } from '../web/api.js';

test('the bridge sync API says "more"; it is followed until false', () => {
  assert.equal(pageHasMore({ events: [{}], cursor: 200, more: true }, undefined), true);
  assert.equal(pageHasMore({ events: [{}], cursor: 400, more: false }, 200), false);
  // a full page with more:false still stops (no extra request)
  assert.equal(pageHasMore({ events: new Array(EVENTS_PAGE).fill({}), cursor: 900, more: false }, 400), false);
});

test('hasMore is accepted too, and without a flag a non-empty page asks again', () => {
  assert.equal(pageHasMore({ events: [{}], cursor: 5, hasMore: true }, 1), true);
  assert.equal(pageHasMore({ events: [{}], cursor: 5, hasMore: false }, 1), false);
  assert.equal(pageHasMore({ events: [{}], cursor: 5 }, 1), true);
  assert.equal(pageHasMore({ events: [], cursor: 5 }, 1), false);
});

test('a page that does not advance the cursor always stops (no endless loop)', () => {
  assert.equal(pageHasMore({ events: [{}], cursor: 7, more: true }, 7), false);
  assert.equal(pageHasMore({ events: [{}], cursor: '7', more: true }, 7), false);
  assert.equal(pageHasMore({ events: [{}], more: true }, 7), false);
  assert.equal(pageHasMore(null, 7), false);
});

test('blob ids and stream payloads', () => {
  assert.equal(blobPath('sha256:abc'), 'abc');
  assert.equal(blobPath('abc'), 'abc');
  assert.deepEqual(unwrapStreamPayload({ event: { type: 'x' }, cursor: 3 }), { event: { type: 'x' }, cursor: 3 });
  assert.deepEqual(unwrapStreamPayload({ type: 'x', cursor: 4 }), { event: { type: 'x', cursor: 4 }, cursor: 4 });
});
