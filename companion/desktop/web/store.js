// Conversation summaries and per-conversation timelines built from sync events.
// Every event is applied at most once (by id); timeline items have stable keys so the view can
// patch them in place (streaming text, card updates) without reloading generated-UI iframes.

import { between, clip, toMs } from './format.js';

const CARD_TOOLS = new Set(['show_card', 'update_card', 'dismiss_card']);
const QUIET_END_REASONS = /^(user|stop|stopped|back|button|side[-_ ]?button|close|closed|ended|normal|idle|timeout|done|finished|hangup)$/i;

export function conversationIdOf(event) {
  if (event && typeof event.conversationId === 'string' && event.conversationId) return event.conversationId;
  const id = event && typeof event.id === 'string' ? event.id : '';
  const colon = id.indexOf(':');
  if (colon > 0 && id.startsWith('c_')) return id.slice(0, colon);
  return null;
}

function eventId(event) {
  if (typeof event.id === 'string' && event.id) return event.id;
  if (typeof event.id === 'number') return String(event.id);
  return `${event.type}:${event.at}:${event.seq ?? ''}:${event.messageId ?? event.artifactId ?? ''}`;
}

function text(value) {
  return typeof value === 'string' ? value : '';
}

/** "[T3 update] …" envelopes: the update line between the markers, split from "Last message:". */
export function parseT3Update(raw) {
  const inner = between(raw, '--- BEGIN T3 UPDATE ---', '--- END T3 UPDATE ---') ?? text(raw);
  let line = inner.replace(/^\s*\[T3 update\]\s*/i, '').trim();
  let lastMessage = '';
  const marker = line.indexOf(' Last message: ');
  if (marker >= 0) {
    lastMessage = line.slice(marker + 15).trim();
    line = line.slice(0, marker).trim();
  }
  return { line, lastMessage };
}

export function parseCompletion(raw) {
  const inner = between(raw, '--- BEGIN BACKGROUND RESULT DATA ---', '--- END BACKGROUND RESULT DATA ---');
  return (inner ?? text(raw)).replace(/^\s*\[[^\]]{1,40}\]\s*/, '').trim();
}

export class Timeline {
  constructor(id) {
    this.id = id;
    this.seen = new Set();
    this.items = new Map();
    this.order = 0;
    this.cursor = null;
    this.loaded = false;
    this.loading = null;
    this.error = null;
    this.openAssistant = null;
    this.ended = false;
    this.lastEventAt = 0;
  }

  get size() {
    return this.items.size;
  }

  item(key, kind, at, fields) {
    let item = this.items.get(key);
    if (!item) {
      item = { key, kind, at, order: this.order++, version: 0, ...fields };
      this.items.set(key, item);
    }
    return item;
  }

  bump(item) {
    item.version += 1;
    return true;
  }

  /** Applies one event; returns true when the timeline changed. */
  apply(event) {
    if (!event || typeof event !== 'object' || typeof event.type !== 'string') return false;
    const id = eventId(event);
    if (this.seen.has(id)) return false;
    const seq = typeof event.seq === 'number' ? event.seq : null;
    // A draft with a message id and a seq is applied by its seq (older or equal drafts are ignored
    // below), so its id need not be remembered: a live conversation sends one every ~300 ms.
    if (!(event.type === 'message.assistant.delta' && typeof event.messageId === 'string' && event.messageId && seq !== null)) {
      this.seen.add(id);
    }
    const at = toMs(event.at) ?? Date.now();
    this.lastEventAt = Math.max(this.lastEventAt, at);
    switch (event.type) {
      case 'message.user': {
        const item = this.item(`u:${id}`, 'user', at, {});
        item.text = text(event.text);
        item.eventType = text(event.eventType);
        item.origin = text(event.origin);
        return this.bump(item);
      }
      case 'message.assistant.delta':
      case 'message.assistant.done':
      case 'message.assistant.interrupted': {
        const messageId = text(event.messageId);
        const key = messageId ? `a:${messageId}` : (this.openAssistant || `a:${id}`);
        const item = this.item(key, 'assistant', at, { text: '', streaming: true, done: false, seq: -1 });
        if (event.type === 'message.assistant.delta') {
          if (item.done || (seq !== null && seq <= item.seq)) return false;
          item.updatedAt = at;
          if (typeof event.text === 'string') item.text = event.text;
          item.streaming = true;
          if (seq !== null) item.seq = seq;
          this.openAssistant = messageId ? null : key;
        } else {
          item.updatedAt = at;
          if (typeof event.text === 'string' && event.text) item.text = event.text;
          item.streaming = false;
          item.done = true;
          item.interrupted = event.type === 'message.assistant.interrupted' || event.interrupted === true;
          if (this.openAssistant === key) this.openAssistant = null;
        }
        return this.bump(item);
      }
      case 'card.shown':
      case 'card.updated': {
        const card = event.card && typeof event.card === 'object' ? event.card : null;
        if (!card || !card.id) return false;
        const item = this.item(`card:${card.id}`, 'card', at, { updates: 0 });
        if (item.card && event.type === 'card.updated') item.updates += 1;
        item.card = card;
        item.dismissed = card.state === 'dismissed';
        item.origin = text(event.origin);
        item.updatedAt = at;
        return this.bump(item);
      }
      case 'card.dismissed': {
        const ids = [];
        if (event.card && event.card.id) ids.push(event.card.id);
        if (typeof event.cardId === 'string') ids.push(event.cardId);
        for (const list of [event.ids, event.dismissed, event.cardIds]) {
          if (Array.isArray(list)) ids.push(...list.filter((value) => typeof value === 'string'));
        }
        let changed = false;
        for (const cardId of ids) {
          const item = this.items.get(`card:${cardId}`);
          if (item && !item.dismissed) {
            item.dismissed = true;
            changed = this.bump(item) || changed;
          }
        }
        return changed;
      }
      case 'host.t3_update': {
        const payload = event.payload && typeof event.payload === 'object' ? event.payload : {};
        const parsed = parseT3Update(event.text);
        const item = this.item(`t3:${id}`, 't3', at, {});
        Object.assign(item, {
          line: parsed.line,
          lastMessage: text(payload.lastMessage) || parsed.lastMessage,
          threadId: text(event.threadId) || text(payload.threadId),
          environmentId: text(event.environmentId) || text(payload.environmentId),
          title: text(event.title) || text(payload.title),
          projectTitle: text(event.projectTitle) || text(payload.projectTitle),
          status: text(event.status) || text(payload.status) || text(event.kind).replace(/^t3\.thread\./, ''),
        });
        return this.bump(item);
      }
      case 'host.note':
      case 'host.completion':
      case 'ui.event': {
        const kind = event.type === 'host.note' ? 'note' : event.type === 'ui.event' ? 'uievent' : 'completion';
        const raw = text(event.text);
        let body = kind === 'completion' ? parseCompletion(raw) : raw.replace(/^\s*\[[^\]]{1,40}\]\s*/, '').trim();
        if (kind === 'uievent') body = body.replace(/^the user\b/i, 'You');
        if (!body) return false;
        const item = this.item(`s:${id}`, kind, at, {});
        item.text = body;
        item.title = text(event.title);
        return this.bump(item);
      }
      case 'image': {
        const blobId = text(event.blobId);
        if (!blobId) return false;
        if (this.items.has(`img:${blobId}`)) return false;
        const item = this.item(`img:${blobId}`, 'image', at, {});
        Object.assign(item, {
          blobId, mime: text(event.mime), width: Number(event.width) || 0, height: Number(event.height) || 0,
          source: text(event.source), caption: text(event.caption),
        });
        return this.bump(item);
      }
      case 'tool.call':
      case 'tool.completed': {
        const tool = text(event.tool) || text(event.name);
        if (CARD_TOOLS.has(tool) && !event.isError) return false;
        const key = `tool:${text(event.toolCallId) || id}`;
        const item = this.item(key, 'tool', at, {});
        Object.assign(item, {
          tool,
          arguments: event.arguments ?? item.arguments ?? null,
          result: event.type === 'tool.completed' ? (event.result ?? null) : (item.result ?? null),
          isError: event.isError === true,
          pending: event.type === 'tool.call' && !item.completed,
          completed: item.completed || event.type === 'tool.completed',
          blobId: text(event.blobId) || item.blobId || '',
        });
        if (tool === 'ui_generate' && !item.isError) item.hidden = true;
        let changed = this.bump(item);
        if (item.blobId && !this.items.has(`img:${item.blobId}`)) {
          const image = this.item(`img:${item.blobId}`, 'image', at + 1, {});
          Object.assign(image, {
            blobId: item.blobId, mime: text(event.mime) || 'image/jpeg', width: Number(event.width) || 0,
            height: Number(event.height) || 0, source: tool === 'mac_look' ? 'mac_screenshot' : 'tool', caption: '',
          });
          changed = this.bump(image) || changed;
        }
        return changed;
      }
      case 'ui.generating':
      case 'ui.generated':
      case 'ui.failed': {
        const artifactId = text(event.artifactId);
        if (!artifactId) return false;
        const item = this.item(`ui:${artifactId}`, 'ui', at, { status: 'generating' });
        const status = event.type === 'ui.generated' ? 'ready' : event.type === 'ui.failed' ? 'failed' : 'generating';
        // a late "generating" never downgrades a finished artifact
        if (status === 'generating' && item.status !== 'generating') return false;
        Object.assign(item, {
          artifactId,
          status,
          title: text(event.title) || item.title || '',
          summary: text(event.summary) || item.summary || '',
          prompt: text(event.prompt) || item.prompt || '',
          imageBlobId: text(event.imageBlobId) || item.imageBlobId || '',
          error: text(event.error) || (event.error && text(event.error.message)) || '',
          width: Number(event.width) || item.width || 0,
          height: Number(event.height) || item.height || 0,
          updatedAt: at,
        });
        return this.bump(item);
      }
      case 'conversation.started': {
        const item = this.item(`d:${id}`, 'divider', at, {});
        item.label = 'Conversation started';
        item.variant = 'start';
        this.ended = false;
        return this.bump(item);
      }
      case 'conversation.ended': {
        const item = this.item(`d:${id}`, 'divider', at, {});
        item.label = 'Conversation ended';
        item.variant = 'end';
        this.ended = true;
        this.closeStreaming();
        return this.bump(item);
      }
      case 'session.connected': {
        if (!event.reconnect) return false;
        const item = this.item(`d:${id}`, 'divider', at, {});
        item.label = 'Reconnected';
        item.variant = 'reconnect';
        return this.bump(item);
      }
      case 'session.ended': {
        this.closeStreaming();
        const reason = text(event.reason);
        if (!reason || QUIET_END_REASONS.test(reason)) return true;
        const item = this.item(`d:${id}`, 'divider', at, {});
        item.label = /error|fail|drop|lost|network|provider/i.test(reason) ? 'Connection dropped' : 'Session ended';
        item.variant = 'warn';
        return this.bump(item);
      }
      case 'session.finalized': {
        const item = this.item(`d:${id}`, 'saved', at, {});
        item.summary = text(event.summary);
        item.memoryCount = Number(event.memoryCount) || 0;
        return this.bump(item);
      }
      default:
        return false;
    }
  }

  /** A dropped session never finishes its streaming bubble. */
  closeStreaming() {
    for (const item of this.items.values()) {
      if (item.kind === 'assistant' && item.streaming) {
        item.streaming = false;
        item.done = true;
        this.bump(item);
      }
    }
    this.openAssistant = null;
  }

  sorted() {
    const list = [];
    for (const item of this.items.values()) if (!item.hidden) list.push(item);
    list.sort((a, b) => (a.at - b.at) || (a.order - b.order));
    return list;
  }

  /** Plain-text transcript (for "Copy transcript"). */
  transcript(title) {
    const lines = [title ? `# ${title}` : '# SamRabbit conversation', ''];
    for (const item of this.sorted()) {
      const when = new Date(item.at).toLocaleString();
      if (item.kind === 'user') lines.push(`[${when}] You: ${item.text}`);
      else if (item.kind === 'assistant' && item.text) lines.push(`[${when}] SamRabbit: ${item.text}`);
      else if (item.kind === 'card' && item.card) lines.push(`[${when}] [Card] ${item.card.title || ''}`);
      else if (item.kind === 'ui') lines.push(`[${when}] [Generated UI] ${item.title || item.artifactId}`);
      else if (item.kind === 'image') lines.push(`[${when}] [Image: ${item.source || 'image'}]`);
      else if (item.kind === 't3') lines.push(`[${when}] [T3] ${item.line}`);
      else if (item.kind === 'tool') lines.push(`[${when}] [${item.tool}]`);
      else if (item.text) lines.push(`[${when}] [${item.kind}] ${item.text}`);
    }
    return lines.join('\n');
  }
}

function normalizeSummary(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const id = text(raw.conversationId) || text(raw.id);
  if (!id) return null;
  const startedAt = toMs(raw.startedAt);
  const lastAt = toMs(raw.lastAt) ?? startedAt ?? Date.now();
  return {
    conversationId: id,
    title: text(raw.title),
    startedAt: startedAt ?? lastAt,
    lastAt,
    live: raw.live === true,
    messageCount: Number(raw.messageCount) || 0,
    preview: text(raw.preview),
  };
}

export class ConversationStore {
  constructor() {
    this.byId = new Map();
    this.timelines = new Map();
    this.counted = new Set();
  }

  /** Merges a server list; returns ids that were not known before. */
  mergeList(list) {
    const added = [];
    for (const raw of Array.isArray(list) ? list : []) {
      const summary = normalizeSummary(raw);
      if (!summary) continue;
      const existing = this.byId.get(summary.conversationId);
      if (!existing) {
        added.push(summary.conversationId);
        this.byId.set(summary.conversationId, summary);
      } else {
        existing.title = summary.title || existing.title;
        existing.startedAt = Math.min(existing.startedAt, summary.startedAt);
        existing.live = summary.live; // the bridge decides what is live (it sees every device event)
        if (summary.lastAt >= existing.lastAt) {
          existing.lastAt = summary.lastAt;
          existing.preview = summary.preview || existing.preview;
          existing.messageCount = summary.messageCount || existing.messageCount;
        }
      }
    }
    return added;
  }

  get(id) {
    return this.byId.get(id) || null;
  }

  list() {
    return [...this.byId.values()].sort((a, b) => b.lastAt - a.lastAt);
  }

  timeline(id) {
    let timeline = this.timelines.get(id);
    if (!timeline) {
      timeline = new Timeline(id);
      this.timelines.set(id, timeline);
    }
    return timeline;
  }

  /** Applies a live event: {conversationId, isNew, summaryChanged, timelineChanged}. */
  applyEvent(event) {
    const id = conversationIdOf(event);
    if (!id) return null;
    const at = toMs(event.at) ?? Date.now();
    let summary = this.byId.get(id);
    const isNew = !summary;
    if (!summary) {
      summary = { conversationId: id, title: '', startedAt: at, lastAt: at, live: true, messageCount: 0, preview: '' };
      this.byId.set(id, summary);
    }
    let summaryChanged = isNew;
    if (at > summary.lastAt) {
      summary.lastAt = at;
      summaryChanged = true;
    }
    const key = eventId(event);
    const timeline = this.timelines.get(id);
    // Only messages are counted, so only their ids are remembered (the page runs for weeks: every
    // streamed draft and card update would otherwise stay in this set forever).
    const counts = event.type === 'message.user' || event.type === 'message.assistant.done';
    const firstTime = counts && !this.counted.has(key) && !(timeline && timeline.seen.has(key));
    if (firstTime) this.counted.add(key);
    switch (event.type) {
      case 'conversation.started':
      case 'session.connected':
        if (!summary.live && at >= summary.lastAt - 1000) {
          summary.live = true;
          summaryChanged = true;
        }
        break;
      case 'conversation.ended':
        summary.live = false;
        summaryChanged = true;
        break;
      case 'message.user':
        if (!summary.title) summary.title = clip(event.text, 80);
        summary.preview = clip(event.text, 160);
        if (firstTime) summary.messageCount += 1;
        summaryChanged = true;
        break;
      case 'message.assistant.done':
        if (text(event.text)) summary.preview = clip(event.text, 160);
        if (firstTime) summary.messageCount += 1;
        summaryChanged = true;
        break;
      case 'ui.generated':
        summary.preview = `Generated UI: ${clip(event.title || 'untitled', 120)}`;
        summaryChanged = true;
        break;
      default:
        break;
    }
    const timelineChanged = timeline ? timeline.apply(event) : false;
    return { conversationId: id, isNew, summaryChanged, timelineChanged };
  }
}
