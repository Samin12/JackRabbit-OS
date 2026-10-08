// Timeline/store logic of the desktop web UI (no DOM needed).
// Run: node --test companion/desktop/tests/*.mjs

import assert from 'node:assert/strict';
import { test } from 'node:test';

import { toMs } from '../web/format.js';
import { ConversationStore, Timeline, conversationIdOf, parseCompletion, parseT3Update } from '../web/store.js';

const C = 'c_0123456789abcdef0123';
let seq = 0;
const ev = (type, fields = {}) => {
  seq += 1;
  return { id: `${C}:${seq}`, conversationId: C, seq, type, at: 1_790_000_000_000 + seq * 1000, ...fields };
};

test('events apply once, by id', () => {
  const t = new Timeline(C);
  const user = ev('message.user', { text: 'hello' });
  assert.equal(t.apply(user), true);
  assert.equal(t.apply(user), false);
  assert.equal(t.apply({ ...user }), false);
  assert.equal(t.sorted().length, 1);
  assert.equal(t.sorted()[0].kind, 'user');
});

test('assistant deltas stream into one bubble and done wins over late deltas', () => {
  const t = new Timeline(C);
  t.apply(ev('message.assistant.delta', { messageId: 'a1', text: 'Sure' }));
  t.apply(ev('message.assistant.delta', { messageId: 'a1', text: 'Sure, here' }));
  let [item] = t.sorted();
  assert.equal(item.text, 'Sure, here');
  assert.equal(item.streaming, true);
  t.apply(ev('message.assistant.done', { messageId: 'a1', text: 'Sure, here it is.' }));
  t.apply(ev('message.assistant.delta', { messageId: 'a1', text: 'Sure, he' }));
  [item] = t.sorted();
  assert.equal(item.text, 'Sure, here it is.');
  assert.equal(item.streaming, false);
  assert.equal(t.sorted().length, 1);
});

test('an older delta (lower seq) never replaces a newer draft', () => {
  const t = new Timeline(C);
  const first = ev('message.assistant.delta', { messageId: 'a2', text: 'One' });
  const second = ev('message.assistant.delta', { messageId: 'a2', text: 'One two' });
  t.apply(second);
  t.apply(first);
  assert.equal(t.sorted()[0].text, 'One two');
});

test('interrupted answers and dropped sessions stop streaming', () => {
  const t = new Timeline(C);
  t.apply(ev('message.assistant.delta', { messageId: 'a3', text: 'Eleven minutes, and' }));
  t.apply(ev('message.assistant.interrupted', { messageId: 'a3' }));
  assert.equal(t.sorted()[0].interrupted, true);
  t.apply(ev('message.assistant.delta', { messageId: 'a4', text: 'Next' }));
  t.apply(ev('session.ended', { reason: 'network_lost' }));
  const a4 = t.items.get('a:a4');
  assert.equal(a4.streaming, false);
  assert.ok(t.sorted().some((i) => i.kind === 'divider' && i.label === 'Connection dropped'));
});

test('cards update in place and dismissals mark them', () => {
  const t = new Timeline(C);
  t.apply(ev('card.shown', { card: { id: 'k', title: 'Packing', body: [] } }));
  t.apply(ev('card.updated', { card: { id: 'k', title: 'Packing (2)', body: [] } }));
  let cards = t.sorted().filter((i) => i.kind === 'card');
  assert.equal(cards.length, 1);
  assert.equal(cards[0].card.title, 'Packing (2)');
  assert.equal(cards[0].updates, 1);
  t.apply(ev('card.dismissed', { ids: ['k'] }));
  cards = t.sorted().filter((i) => i.kind === 'card');
  assert.equal(cards[0].dismissed, true);
});

test('card tool calls are hidden; other tools show; mac_look blobs become images once', () => {
  const t = new Timeline(C);
  t.apply(ev('tool.completed', { tool: 'show_card', toolCallId: 'x1', result: '{}' }));
  t.apply(ev('tool.completed', { tool: 'mac_look', toolCallId: 'x2', blobId: 'sha256:ab', width: 1024, height: 640, result: '{"ok":true}' }));
  t.apply(ev('image', { blobId: 'sha256:ab', source: 'mac_screenshot' }));
  const kinds = t.sorted().map((i) => i.kind);
  assert.deepEqual(kinds, ['tool', 'image']);
  assert.equal(t.sorted()[1].source, 'mac_screenshot');
});

test('ui.generating → generated; a late generating never downgrades', () => {
  const t = new Timeline(C);
  t.apply(ev('ui.generating', { artifactId: 'u1', title: 'Sleep' }));
  t.apply(ev('ui.generated', { artifactId: 'u1', title: 'Sleep this week', summary: 'Hours per night' }));
  t.apply(ev('ui.generating', { artifactId: 'u1', title: 'Sleep' }));
  const [item] = t.sorted();
  assert.equal(item.status, 'ready');
  assert.equal(item.title, 'Sleep this week');
  t.apply(ev('ui.failed', { artifactId: 'u2', error: 'timed out' }));
  assert.equal(t.items.get('ui:u2').status, 'failed');
});

test('T3 envelopes and background results are unwrapped', () => {
  const envelope = '[T3 update] Host-delivered status… ask\n--- BEGIN T3 UPDATE ---\n[T3 update] “Fix login” in Website finished. Last message: All 42 tests pass.\n--- END T3 UPDATE ---';
  assert.deepEqual(parseT3Update(envelope), { line: '“Fix login” in Website finished.', lastMessage: 'All 42 tests pass.' });
  assert.equal(parseCompletion('--- BEGIN BACKGROUND RESULT DATA ---\nTop picks: DF54.\n--- END BACKGROUND RESULT DATA ---'), 'Top picks: DF54.');
  const t = new Timeline(C);
  t.apply(ev('host.t3_update', { text: envelope, payload: { threadId: 't1', projectTitle: 'Website', status: 'finished' } }));
  const [item] = t.sorted();
  assert.equal(item.kind, 't3');
  assert.equal(item.projectTitle, 'Website');
  assert.equal(item.threadId, 't1');
});

test('UI events read in the second person', () => {
  const t = new Timeline(C);
  t.apply(ev('ui.event', { text: '[UI event] The user tapped Approve on the T3 card.' }));
  assert.equal(t.sorted()[0].text, 'You tapped Approve on the T3 card.');
});

test('items sort by time, then arrival', () => {
  const t = new Timeline(C);
  t.apply({ id: 'b', type: 'message.user', at: 2000, text: 'second' });
  t.apply({ id: 'a', type: 'message.user', at: 1000, text: 'first' });
  t.apply({ id: 'c', type: 'message.user', at: 2000, text: 'third' });
  assert.deepEqual(t.sorted().map((i) => i.text), ['first', 'second', 'third']);
});

test('the store tracks summaries from live events without double counting', () => {
  const store = new ConversationStore();
  store.mergeList([{ conversationId: 'c_old', title: 'Old', startedAt: 1000, lastAt: 2000, live: false, messageCount: 4 }]);
  const started = { id: 'c_new0000000000000000:1', conversationId: 'c_new0000000000000000', type: 'conversation.started', at: 5000 };
  let result = store.applyEvent(started);
  assert.equal(result.isNew, true);
  const said = { id: 'c_new0000000000000000:2', conversationId: 'c_new0000000000000000', type: 'message.user', at: 6000, text: 'Make a chart of my sleep' };
  store.applyEvent(said);
  store.applyEvent(said);
  const summary = store.get('c_new0000000000000000');
  assert.equal(summary.title, 'Make a chart of my sleep');
  assert.equal(summary.messageCount, 1);
  assert.equal(summary.live, true);
  assert.deepEqual(store.list().map((c) => c.conversationId), ['c_new0000000000000000', 'c_old']);
  store.applyEvent({ id: 'c_new0000000000000000:3', conversationId: 'c_new0000000000000000', type: 'conversation.ended', at: 7000 });
  assert.equal(store.get('c_new0000000000000000').live, false);
  result = store.applyEvent({ type: 'message.user', id: 'x', text: 'no conversation' });
  assert.equal(result, null);
});

test('conversation ids come from the event or its "<conversationId>:<seq>" id', () => {
  assert.equal(conversationIdOf({ conversationId: 'c_1' }), 'c_1');
  assert.equal(conversationIdOf({ id: 'c_abc:12' }), 'c_abc');
  assert.equal(conversationIdOf({ id: 'rt:abc:call_1' }), null);
});

test('timestamps accept ms, seconds and ISO strings', () => {
  assert.equal(toMs(1_790_000_000_000), 1_790_000_000_000);
  assert.equal(toMs(1_790_000_000), 1_790_000_000_000);
  assert.equal(toMs('2026-10-07T12:00:00Z'), Date.parse('2026-10-07T12:00:00Z'));
  assert.equal(toMs(''), null);
});
