// Renders a Timeline (store.js) into the conversation view. Nodes are keyed by item key and only
// rebuilt when their item changes, so generated-UI iframes are never reloaded by new events.

import { api } from './api.js';
import { renderCard } from './cards.js';
import { h, textBlocks } from './dom.js';
import { icon } from './icons.js';
import { baseName, clip, dayLabel, hostOf, humanize, parseMaybeJson, plural, pretty, startOfDay, timeLabel } from './format.js';

const GAP_MS = 20 * 60 * 1000;
const STREAM_STALE_MS = 45 * 1000;

// ---------------------------------------------------------------------------- tools

const TOOL_LABELS = {
  mac_status: () => 'Checked what’s open on your Mac',
  mac_open: (a) => (a.app ? `Opened ${a.app} on your Mac`
    : a.url ? `Opened ${hostOf(a.url)} in Chrome`
      : a.path ? `Opened ${baseName(a.path)} on your Mac` : 'Opened something on your Mac'),
  mac_read: (a) => (a.app ? `Read the ${a.app} window` : 'Read the front window on your Mac'),
  mac_act: (a) => ({
    bring_to_front: `Brought ${a.app || 'an app'} to the front`,
    hotkey: a.keys ? `Pressed ${Array.isArray(a.keys) ? a.keys.join(', ') : a.keys}` : 'Pressed a shortcut',
    type_text: `Typed into ${a.label || a.app || 'the front window'}`,
    click: a.label ? `Clicked “${a.label}”` : 'Clicked on your Mac',
    invoke_menu: Array.isArray(a.path) ? `Chose ${a.path.join(' › ')}` : 'Used a menu',
    scroll: `Scrolled ${a.direction || ''}`.trim(),
  })[a.action] || 'Did a step on your Mac',
  mac_look: (a) => (a.app ? `Looked at ${a.app}` : 'Looked at your screen'),
  mac_task: (a) => (a.title ? `Handed “${a.title}” to the Mac agent` : 'Handed a task to the Mac agent'),
  t3_list_threads: () => 'Checked your T3 threads',
  t3_read_thread: () => 'Read a T3 thread',
  t3_new_thread: (a) => (a.title ? `Started T3 thread “${a.title}”` : 'Started a T3 thread'),
  t3_send_message: () => 'Sent a message to a T3 thread',
  t3_respond: (a) => (a.decision ? `Answered T3 (${a.decision})` : 'Answered a T3 question'),
  t3_stop: () => 'Stopped a T3 thread',
  journal_add: () => 'Added to your Heptabase journal',
  journal_read: () => 'Read your journal',
  journal_pause: () => 'Paused journaling',
  web_search: (a) => (a.query ? `Searched the web for “${clip(a.query, 60)}”` : 'Searched the web'),
  calendar_list_upcoming: () => 'Checked your calendar',
  calendar_search: () => 'Searched your calendar',
  calendar_read_event: () => 'Read a calendar event',
  calendar_create_event: (a) => (a.title || a.summary ? `Added “${a.title || a.summary}” to your calendar` : 'Added a calendar event'),
  calendar_update_event: () => 'Updated a calendar event',
  calendar_delete_event: () => 'Removed a calendar event',
  calendar_confirm_action: () => 'Confirmed a calendar change',
  goal_start: () => 'Started a background task',
  goal_inspect: () => 'Checked a background task',
  goal_cancel: () => 'Cancelled a background task',
  get_device_status: () => 'Checked the R1',
  ui_generate: () => 'Asked your Mac to build a UI',
  show_card: () => 'Showed a card', update_card: () => 'Updated a card', dismiss_card: () => 'Dismissed a card',
};

function toolLabel(tool, args) {
  const make = TOOL_LABELS[tool];
  if (make) {
    try {
      return make(args && typeof args === 'object' ? args : {});
    } catch {
      // fall through
    }
  }
  if (/^tasks?_/.test(tool)) return `Tasks: ${humanize(tool.replace(/^tasks?_/, ''))}`;
  if (/^mail_/.test(tool)) return `Mail: ${humanize(tool.slice(5))}`;
  if (/^memory_/.test(tool)) return `Memory: ${humanize(tool.slice(7))}`;
  return humanize(tool);
}

function toolIcon(tool) {
  if (tool === 'mac_look') return 'eye';
  if (tool === 'mac_open') return 'window';
  if (tool === 'mac_act') return 'cursor';
  if (tool === 'mac_read') return 'doc';
  if (tool === 'mac_task') return 'bolt';
  if (tool.startsWith('mac_')) return 'monitor';
  if (tool.startsWith('t3_')) return 'code';
  if (tool.startsWith('journal_')) return 'book';
  if (tool === 'web_search') return 'globe';
  if (tool.startsWith('calendar_')) return 'calendar';
  if (/^tasks?_/.test(tool)) return 'task';
  if (tool.startsWith('mail_')) return 'mail';
  if (tool.startsWith('goal_')) return 'bolt';
  if (tool.startsWith('memory_')) return 'archive';
  if (tool === 'ui_generate' || tool.endsWith('_card')) return 'sparkles';
  return 'wrench';
}

function toolOutcome(item) {
  const result = parseMaybeJson(item.result);
  if (item.pending) return { status: 'running', detail: 'Working…' };
  if (result && typeof result === 'object') {
    const error = result.error && typeof result.error === 'object' ? result.error : null;
    if (item.isError || error || result.ok === false) {
      return { status: 'error', detail: clip((error && (error.message || error.code)) || result.message || 'Failed', 90) };
    }
    if (typeof result.say === 'string') return { status: 'ok', detail: clip(result.say, 90), result };
    return { status: 'ok', detail: '', result };
  }
  if (item.isError) return { status: 'error', detail: clip(String(result || 'Failed'), 90) };
  return { status: 'ok', detail: '' };
}

// ---------------------------------------------------------------------------- items

function renderUser(item) {
  const caption = item.eventType === 'genui.action' ? 'Tapped on a card'
    : item.eventType === 'debug.say' ? 'Debug input' : '';
  return h('div', { class: 'msg msg-user', title: timeLabel(item.at) },
    h('div', { class: 'bubble' }, textBlocks(item.text)),
    caption ? h('div', { class: 'msg-caption', text: caption }) : null);
}

function assistantBody(item, streaming) {
  const blocks = textBlocks(item.text);
  if (!blocks.length && streaming) {
    return [h('div', { class: 'typing' }, h('span'), h('span'), h('span'))];
  }
  if (streaming && blocks.length) blocks[blocks.length - 1].append(h('span', { class: 'caret' }));
  if (item.interrupted) blocks.push(h('div', { class: 'msg-note' }, icon('stop', { size: 11 }), 'Interrupted'));
  return blocks;
}

function renderAssistant(item, ctx, streaming) {
  return h('div', { class: `msg msg-assistant${streaming ? ' streaming' : ''}`, title: timeLabel(item.at) },
    h('img', { class: 'avatar', src: 'orb.svg', alt: '' }),
    h('div', { class: 'msg-body' }, assistantBody(item, streaming)));
}

function patchAssistant(node, item, ctx, streaming) {
  const body = node.querySelector('.msg-body');
  if (!body) return false;
  node.classList.toggle('streaming', streaming);
  body.replaceChildren(...assistantBody(item, streaming));
  return true;
}

function renderCardItem(item) {
  return h('div', { class: 'row row-card' }, renderCard(item.card, { dismissed: item.dismissed, updates: item.updates }));
}

function iconButton(name, label, onClick, extra = '') {
  return h('button', { class: `btn-ghost ${extra}`.trim(), type: 'button', title: label, onclick: onClick },
    icon(name, { size: 14 }), h('span', { text: label }));
}

function renderUi(item, ctx) {
  const ready = item.status === 'ready';
  const failed = item.status === 'failed';
  const eyebrow = ready ? 'Generated UI' : failed ? 'Couldn’t build this UI' : 'Building a UI on your Mac…';
  const head = h('header', { class: 'ui-head' },
    h('div', { class: 'ui-icon' }, icon(failed ? 'alert' : 'sparkles', { size: 16 })),
    h('div', { class: 'ui-titles' },
      h('div', { class: 'ui-eyebrow', text: eyebrow }),
      h('h3', { class: 'ui-title', text: item.title || (ready ? 'Untitled UI' : 'New UI') }),
      item.summary ? h('div', { class: 'ui-summary', text: item.summary }) : null),
    ready ? h('div', { class: 'ui-actions' },
      iconButton('expand', 'Open full size', () => ctx.openArtifact(item)),
      iconButton('copy', 'Copy HTML', (event) => ctx.copyHtml(item, event.currentTarget))) : null);
  let content;
  if (ready) {
    const iframe = h('iframe', {
      class: 'ui-iframe',
      sandbox: 'allow-scripts',
      src: api.documentUrl(item.artifactId),
      referrerpolicy: 'no-referrer',
      loading: 'lazy',
      title: item.title || 'Generated UI',
      style: `height:${ctx.frameHeight(item.artifactId)}px`,
    });
    iframe.dataset.artifactId = item.artifactId;
    ctx.registerFrame(iframe, item.artifactId);
    content = h('div', { class: 'ui-frame' }, iframe);
  } else if (failed) {
    content = h('div', { class: 'ui-failed', text: item.error || 'The Mac could not generate this UI.' });
  } else {
    content = h('div', { class: 'ui-building' },
      h('div', { class: 'ui-shimmer' }, h('span'), h('span'), h('span')),
      h('div', { class: 'ui-building-text', text: item.prompt ? clip(item.prompt, 140) : 'Claude is designing it on your Mac. It shows up here the moment it’s ready.' }));
  }
  return h('section', { class: `ui-card status-${item.status}` }, head, content);
}

const SOURCE_LABELS = {
  camera: ['camera', 'R1 camera'],
  mac_screenshot: ['monitor', 'Mac screenshot'],
  generated_ui: ['sparkles', 'Generated UI preview'],
  tool: ['image', 'Image'],
};

function renderImage(item, ctx) {
  const [glyph, label] = SOURCE_LABELS[item.source] || ['image', 'Image'];
  const ratio = item.width && item.height ? `aspect-ratio:${item.width} / ${item.height}` : '';
  return h('figure', { class: `shot source-${item.source || 'image'}` },
    h('button', { class: 'shot-frame', type: 'button', title: 'Click to zoom', style: ratio, onclick: () => ctx.openImage(item) },
      h('img', { src: api.blobUrl(item.blobId), alt: item.caption || label, loading: 'lazy', decoding: 'async' })),
    h('figcaption', null, icon(glyph, { size: 13 }), h('span', { text: item.caption || label }),
      item.width && item.height ? h('span', { class: 'dims', text: `${item.width}×${item.height}` }) : null));
}

function section(title, value) {
  if (value == null || value === '' || (typeof value === 'object' && !Object.keys(value).length)) return null;
  const text = pretty(value);
  return h('div', { class: 'tool-section' }, h('div', { class: 'tool-section-title', text: title }),
    h('pre', { text: text.length > 4000 ? `${text.slice(0, 4000)}…` : text }));
}

function renderTool(item, ctx) {
  const outcome = toolOutcome(item);
  const args = parseMaybeJson(item.arguments);
  const label = toolLabel(item.tool, args);
  const threadId = outcome.result && typeof outcome.result === 'object' ? (outcome.result.threadId || '') : '';
  const details = h('details', { class: `tool tool-${outcome.status}` },
    h('summary', null,
      h('span', { class: 'tool-icon' }, icon(toolIcon(item.tool), { size: 14 })),
      h('span', { class: 'tool-label', text: label }),
      outcome.detail ? h('span', { class: 'tool-detail', text: outcome.detail }) : null,
      outcome.status === 'running' ? h('span', { class: 'spinner' }) : null,
      outcome.status === 'error' ? h('span', { class: 'tool-state error' }, icon('alert', { size: 12 })) : null,
      h('span', { class: 'tool-chevron' }, icon('chevronRight', { size: 13 }))),
    h('div', { class: 'tool-body' },
      h('div', { class: 'tool-name', text: item.tool }),
      section('Arguments', args),
      section('Result', item.result),
      item.tool.startsWith('t3_') || item.tool === 'mac_task'
        ? h('button', { class: 'link', type: 'button', onclick: () => ctx.openT3({ threadId }) }, 'Open T3', icon('external', { size: 12 }))
        : null));
  if (ctx.openTools.has(item.key)) details.open = true;
  details.addEventListener('toggle', () => {
    if (details.open) ctx.openTools.add(item.key);
    else ctx.openTools.delete(item.key);
  });
  return details;
}

const T3_STATUS = {
  finished: ['ok', 'Finished'], done: ['ok', 'Finished'], needs_approval: ['warn', 'Needs approval'],
  'needs-approval': ['warn', 'Needs approval'], needs_input: ['warn', 'Needs your input'],
  'needs-input': ['warn', 'Needs your input'], error: ['error', 'Error'], working: ['active', 'Working'],
};

function renderT3(item, ctx) {
  const [tone, label] = T3_STATUS[item.status] || ['active', item.status ? humanize(item.status) : 'Update'];
  return h('div', { class: `sys sys-t3 tone-${tone}` },
    h('div', { class: 'sys-icon' }, icon('code', { size: 15 })),
    h('div', { class: 'sys-main' },
      h('div', { class: 'sys-head' },
        h('span', { class: 'sys-tag', text: 'T3 update' }),
        item.projectTitle ? h('span', { class: 'sys-project', text: item.projectTitle }) : null,
        h('span', { class: `pill tone-${tone}`, text: label }),
        h('span', { class: 'sys-time', text: timeLabel(item.at) })),
      item.title ? h('div', { class: 'sys-title', text: item.title }) : null,
      item.line ? h('div', { class: 'sys-text', text: item.line }) : null,
      item.lastMessage ? h('blockquote', { class: 'sys-quote', text: clip(item.lastMessage, 420) }) : null,
      h('button', { class: 'link', type: 'button', onclick: () => ctx.openT3(item) }, 'Open in T3', icon('external', { size: 12 }))));
}

function renderSystem(item) {
  const variants = {
    note: ['note', 'Host note'],
    uievent: ['hand', 'On the R1'],
    completion: ['bolt', item.title || 'Background task finished'],
  };
  const [glyph, label] = variants[item.kind];
  const long = item.text.length > 280;
  const body = h('div', { class: 'sys-text', text: long ? clip(item.text, 280) : item.text });
  const node = h('div', { class: `sys sys-${item.kind}` },
    h('div', { class: 'sys-icon' }, icon(glyph, { size: 14 })),
    h('div', { class: 'sys-main' },
      h('div', { class: 'sys-head' }, h('span', { class: 'sys-tag', text: label }), h('span', { class: 'sys-time', text: timeLabel(item.at) })),
      body,
      long ? h('button', {
        class: 'link', type: 'button',
        onclick: (event) => {
          body.textContent = item.text;
          event.currentTarget.remove();
        },
      }, 'Show all') : null));
  return node;
}

function renderDivider(item) {
  return h('div', { class: `divider ${item.variant || ''}` }, h('span', null, item.label, ' · ', timeLabel(item.at)));
}

function renderSaved(item) {
  return h('div', { class: 'saved' },
    h('details', null,
      h('summary', null, icon('archive', { size: 13 }), 'Saved to memory',
        item.memoryCount ? ` · ${plural(item.memoryCount, 'memory', 'memories')}` : '',
        item.summary ? h('span', { class: 'tool-chevron' }, icon('chevronRight', { size: 12 })) : null),
      item.summary ? h('p', { text: item.summary }) : null));
}

function renderTimeSeparator(at, now) {
  return h('div', { class: 'timesep' }, h('span', { text: `${dayLabel(at, now)} · ${timeLabel(at)}` }));
}

// ---------------------------------------------------------------------------- view

export class TimelineView {
  constructor(root, ctx) {
    this.root = root;
    this.ctx = ctx;
    this.nodes = new Map();
    this.timelineId = null;
  }

  reset() {
    this.root.replaceChildren();
    this.nodes.clear();
    this.timelineId = null;
  }

  isStreaming(item, live, now) {
    return item.kind === 'assistant' && item.streaming && (live || now - (item.updatedAt || item.at) < STREAM_STALE_MS);
  }

  hasStreaming(timeline, live, now = Date.now()) {
    for (const item of timeline.items.values()) if (this.isStreaming(item, live, now)) return true;
    return false;
  }

  render(timeline, { live = false, now = Date.now() } = {}) {
    if (this.timelineId !== timeline.id) {
      this.reset();
      this.timelineId = timeline.id;
    }
    const wanted = new Set();
    let previous = null;
    let lastAt = 0;
    const place = (key, signature, build, patch) => {
      wanted.add(key);
      let record = this.nodes.get(key);
      if (!record) {
        record = { node: build(), signature };
        this.nodes.set(key, record);
      } else if (record.signature !== signature) {
        if (!(patch && patch(record.node))) {
          const node = build();
          record.node.replaceWith(node);
          record.node = node;
        }
        record.signature = signature;
      }
      const expected = previous ? previous.nextSibling : this.root.firstChild;
      if (record.node !== expected) this.root.insertBefore(record.node, expected);
      previous = record.node;
    };
    for (const item of timeline.sorted()) {
      const dayChanged = !lastAt || startOfDay(lastAt) !== startOfDay(item.at);
      if ((dayChanged || item.at - lastAt > GAP_MS) && item.kind !== 'divider') {
        const at = item.at;
        place(`ts:${item.key}`, `${dayLabel(at, now)}`, () => renderTimeSeparator(at, now));
      }
      lastAt = Math.max(lastAt, item.at);
      const streaming = this.isStreaming(item, live, now);
      const signature = `${item.version}:${streaming ? 1 : 0}`;
      const ctx = this.ctx;
      switch (item.kind) {
        case 'user': place(item.key, signature, () => renderUser(item)); break;
        case 'assistant':
          place(item.key, signature, () => renderAssistant(item, ctx, streaming), (node) => patchAssistant(node, item, ctx, streaming));
          break;
        case 'card': place(item.key, signature, () => renderCardItem(item)); break;
        case 'ui': place(item.key, signature, () => renderUi(item, ctx)); break;
        case 'image': place(item.key, signature, () => renderImage(item, ctx)); break;
        case 'tool': place(item.key, signature, () => renderTool(item, ctx)); break;
        case 't3': place(item.key, signature, () => renderT3(item, ctx)); break;
        case 'note': case 'uievent': case 'completion': place(item.key, signature, () => renderSystem(item)); break;
        case 'divider': place(item.key, signature, () => renderDivider(item)); break;
        case 'saved': place(item.key, signature, () => renderSaved(item)); break;
        default: break;
      }
    }
    for (const [key, record] of this.nodes) {
      if (!wanted.has(key)) {
        record.node.remove();
        this.nodes.delete(key);
      }
    }
  }
}
