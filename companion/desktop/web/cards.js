// GenUI cards (GenCardCodec JSON from the R1) rendered as HTML cards.
// Blocks: text, stat, kv, list, checklist, progress, bars, weather, timer, divider.

import { h } from './dom.js';
import { GEN_ICONS, WEATHER_ICONS, icon } from './icons.js';
import { duration, toMs } from './format.js';

export const ACCENTS = {
  blue: '#1a73f2', violet: '#7c6cff', cyan: '#5ca2ff', mint: '#68decc',
  pink: '#ff5ca8', amber: '#ffc45c', red: '#ff6363', green: '#60d6a0',
};
const STATUS_COLORS = { ok: '#60d6a0', warn: '#ffc45c', error: '#ff6363', active: '#a0c7ff', idle: '#8c98ac' };
const WEATHER_COLORS = {
  sunny: '#ffc45c', 'clear-night': '#a0c7ff', 'partly-cloudy': '#ffd88a', cloudy: '#b8c4d8', rain: '#5ca2ff',
  storm: '#7c6cff', snow: '#e8f1ff', fog: '#9aa6ba', wind: '#68decc',
};

function str(value) {
  return value == null ? '' : String(value);
}

function statusDot(status) {
  if (!status || !STATUS_COLORS[status]) return null;
  return h('span', { class: `status-dot status-${status}`, title: status });
}

function blockText(block) {
  const style = ['body', 'muted', 'lead'].includes(block.style) ? block.style : 'body';
  return h('p', { class: `gb-text gb-text-${style}`, text: str(block.text) });
}

function blockStat(block) {
  const trend = block.trend;
  const trendIcon = trend === 'up' ? 'trendUp' : trend === 'down' ? 'trendDown' : trend === 'flat' ? 'minus' : null;
  return h('div', { class: 'gb-stat' },
    h('div', { class: 'gb-stat-value', text: str(block.value) }),
    h('div', { class: 'gb-stat-meta' },
      block.label ? h('span', { class: 'gb-stat-label', text: str(block.label) }) : null,
      block.delta || trendIcon
        ? h('span', { class: `gb-stat-delta trend-${trend || 'none'}` }, trendIcon ? icon(trendIcon, { size: 13 }) : null,
          block.delta ? str(block.delta) : null)
        : null));
}

function blockKv(block) {
  const pairs = Array.isArray(block.pairs) ? block.pairs : [];
  const columns = Number(block.columns) === 2 ? 2 : 1;
  return h('dl', { class: `gb-kv cols-${columns}` },
    pairs.map((pair) => h('div', { class: 'gb-kv-pair' },
      h('dt', { text: str(pair && pair.k) }),
      h('dd', { text: str(pair && pair.v) }))));
}

function rowIcon(name) {
  const mapped = GEN_ICONS[name];
  return mapped ? h('span', { class: 'gb-row-icon' }, icon(mapped, { size: 15 })) : null;
}

function blockList(block) {
  const items = Array.isArray(block.items) ? block.items : [];
  return h('ul', { class: 'gb-list' }, items.map((item) => {
    const row = item || {};
    return h('li', { class: `gb-row${row.checked ? ' checked' : ''}` },
      row.checked ? h('span', { class: 'gb-row-icon ok' }, icon('check', { size: 15 })) : rowIcon(row.icon),
      h('div', { class: 'gb-row-main' },
        h('div', { class: 'gb-row-title', text: str(row.title ?? row.text) }),
        row.detail ? h('div', { class: 'gb-row-detail', text: str(row.detail) }) : null),
      row.trailing ? h('div', { class: 'gb-row-trailing', text: str(row.trailing) }) : null,
      statusDot(row.status));
  }));
}

function blockChecklist(block) {
  const items = Array.isArray(block.items) ? block.items : [];
  const done = items.filter((item) => item && item.checked).length;
  return h('div', { class: 'gb-checklist' },
    h('ul', null, items.map((item) => {
      const row = item || {};
      return h('li', { class: `gb-check${row.checked ? ' checked' : ''}` },
        h('span', { class: 'gb-check-box' }, row.checked ? icon('check', { size: 12 }) : null),
        h('div', { class: 'gb-row-main' },
          h('div', { class: 'gb-row-title', text: str(row.text ?? row.title) }),
          row.detail ? h('div', { class: 'gb-row-detail', text: str(row.detail) }) : null),
        row.trailing ? h('div', { class: 'gb-row-trailing', text: str(row.trailing) }) : null);
    })),
    items.length > 1 ? h('div', { class: 'gb-check-count', text: `${done} of ${items.length} done` }) : null);
}

function blockProgress(block) {
  const indeterminate = block.indeterminate === true || typeof block.progress !== 'number';
  const value = indeterminate ? 0 : Math.max(0, Math.min(1, block.progress));
  const steps = Array.isArray(block.steps) ? block.steps : [];
  const current = Number.isInteger(block.step) ? block.step : -1;
  return h('div', { class: 'gb-progress' },
    h('div', { class: 'gb-progress-head' },
      block.label ? h('span', { text: str(block.label) }) : h('span'),
      indeterminate ? null : h('span', { class: 'gb-progress-pct', text: `${Math.round(value * 100)}%` })),
    h('div', { class: `gb-track${indeterminate ? ' indeterminate' : ''}` },
      h('div', { class: 'gb-fill', style: indeterminate ? '' : `width:${(value * 100).toFixed(1)}%` })),
    steps.length ? h('ol', { class: 'gb-steps' }, steps.map((step, index) => h('li', {
      class: index < current ? 'done' : index === current ? 'current' : '',
    }, h('span', { class: 'gb-step-dot' }, index < current ? icon('check', { size: 10 }) : null), h('span', { text: str(step) })))) : null);
}

function blockBars(block) {
  const values = (Array.isArray(block.values) ? block.values : []).map((v) => Number(v) || 0);
  const labels = Array.isArray(block.labels) ? block.labels : [];
  const max = Math.max(...values, 0) || 1;
  const highlight = Number.isInteger(block.highlight) ? block.highlight : -1;
  const unit = str(block.unit);
  const fmt = (v) => (Math.round(v * 10) / 10).toString();
  return h('div', { class: 'gb-bars' }, values.map((value, index) => h('div', {
    class: `gb-bar-col${index === highlight ? ' highlight' : ''}`,
    title: `${labels[index] ?? ''} ${fmt(value)}${unit ? ` ${unit}` : ''}`.trim(),
  },
  h('div', { class: 'gb-bar-value', text: `${fmt(value)}${unit && index === highlight ? unit : ''}` }),
  h('div', { class: 'gb-bar-slot' }, h('div', { class: 'gb-bar', style: `height:${Math.max(3, (value / max) * 100).toFixed(1)}%` })),
  h('div', { class: 'gb-bar-label', text: str(labels[index] ?? '') }))));
}

function weatherIcon(condition, size) {
  const name = WEATHER_ICONS[condition] || 'cloud';
  return h('span', { class: 'gb-weather-icon', style: `color:${WEATHER_COLORS[condition] || '#b8c4d8'}` }, icon(name, { size }));
}

function blockWeather(block) {
  const hours = Array.isArray(block.hours) ? block.hours : [];
  return h('div', { class: 'gb-weather' },
    h('div', { class: 'gb-weather-now' },
      weatherIcon(block.condition, 40),
      h('div', { class: 'gb-weather-temp', text: str(block.temp) }),
      h('div', { class: 'gb-weather-meta' },
        h('div', { class: 'gb-weather-cond', text: str(block.condition || '').replace(/-/g, ' ') }),
        block.hi || block.lo ? h('div', { class: 'gb-weather-hilo', text: [block.hi ? `H ${block.hi}` : '', block.lo ? `L ${block.lo}` : ''].filter(Boolean).join('  ') }) : null,
        block.place ? h('div', { class: 'gb-weather-place' }, icon('pin', { size: 12 }), str(block.place)) : null)),
    hours.length ? h('div', { class: 'gb-weather-hours' }, hours.map((hour) => h('div', { class: 'gb-hour' },
      h('div', { class: 'gb-hour-t', text: str(hour && hour.t) }),
      weatherIcon(hour && hour.condition, 18),
      h('div', { class: 'gb-hour-temp', text: str(hour && hour.temp) })))) : null);
}

function timerRemaining(block, now = Date.now()) {
  if (block.done) return 0;
  if (block.paused) return Number(block.pausedRemainingMs) || 0;
  const endsAt = toMs(block.endsAt);
  return endsAt ? Math.max(0, endsAt - now) : 0;
}

function blockTimer(block) {
  const remaining = timerRemaining(block);
  const total = Number(block.totalMs) || 0;
  const running = !block.done && !block.paused && remaining > 0;
  const el = h('div', { class: `gb-timer${running ? ' running' : ''}${block.done || remaining === 0 ? ' done' : ''}` },
    h('div', { class: 'gb-timer-value', text: block.done || remaining === 0 ? 'Done' : duration(remaining) }),
    h('div', { class: 'gb-timer-meta', text: [block.label, block.paused ? 'Paused' : '', total ? `of ${duration(total)}` : ''].filter(Boolean).join(' · ') }),
    h('div', { class: 'gb-track' }, h('div', { class: 'gb-fill', style: `width:${total ? ((1 - remaining / total) * 100).toFixed(1) : 100}%` })));
  if (running) {
    el.dataset.endsAt = String(toMs(block.endsAt));
    el.dataset.totalMs = String(total);
  }
  return el;
}

/** Ticks every running timer block on the page (called once a second by the app). */
export function tickTimers(root, now = Date.now()) {
  for (const el of root.querySelectorAll('.gb-timer.running')) {
    const endsAt = Number(el.dataset.endsAt);
    const total = Number(el.dataset.totalMs) || 0;
    const remaining = Math.max(0, endsAt - now);
    const value = el.querySelector('.gb-timer-value');
    const fill = el.querySelector('.gb-fill');
    if (value) value.textContent = remaining > 0 ? duration(remaining) : 'Done';
    if (fill && total) fill.style.width = `${((1 - remaining / total) * 100).toFixed(1)}%`;
    if (remaining <= 0) el.classList.replace('running', 'done');
  }
}

const BLOCKS = {
  text: blockText, stat: blockStat, kv: blockKv, list: blockList, checklist: blockChecklist,
  progress: blockProgress, bars: blockBars, weather: blockWeather, timer: blockTimer,
  divider: () => h('hr', { class: 'gb-divider' }),
};

function actionChip(action) {
  const style = action && action.style === 'primary' ? ' primary' : action && action.style === 'danger' ? ' danger' : '';
  const glyph = action.say ? 'mic' : action.open ? 'external' : action.timer ? 'timer' : action.dismiss ? 'close' : action.host ? 'bolt' : null;
  return h('span', { class: `gb-action${style}`, title: action.say ? `On the R1 this says “${action.say}”` : 'Available on the R1' },
    glyph ? icon(glyph, { size: 12 }) : null, str(action.label));
}

/** One card. `options.dismissed`, `options.updates` (count) decorate it. */
export function renderCard(card, { dismissed = false, updates = 0 } = {}) {
  const accent = ACCENTS[card.accent] || ACCENTS.blue;
  const compact = card.size === 'compact';
  const live = card.live && typeof card.live === 'object' ? card.live : null;
  const state = dismissed ? 'dismissed' : str(card.state || 'active');
  const badge = dismissed ? h('span', { class: 'gb-badge muted', text: 'Dismissed' })
    : card.terminal || state === 'done' ? h('span', { class: 'gb-badge ok', text: 'Done' })
      : live ? h('span', { class: 'gb-badge live' }, h('span', { class: 'live-dot' }), 'Live')
        : updates > 0 ? h('span', { class: 'gb-badge', text: updates === 1 ? 'Updated' : `Updated ×${updates}` }) : null;
  const mappedIcon = GEN_ICONS[card.icon];
  const liveLines = [card.liveTitle, card.liveSubtitle, card.liveNote].filter(Boolean);
  const body = Array.isArray(card.body) ? card.body : [];
  const actions = Array.isArray(card.actions) ? card.actions.filter((a) => a && a.label) : [];
  return h('article', {
    class: `gcard${compact ? ' compact' : ''} state-${state}`,
    style: `--accent:${accent}`,
  },
  h('div', { class: 'gcard-glow' }),
  h('header', { class: 'gcard-head' },
    mappedIcon ? h('div', { class: 'gcard-icon' }, icon(mappedIcon, { size: compact ? 16 : 18 })) : null,
    h('div', { class: 'gcard-titles' },
      card.eyebrow ? h('div', { class: 'gcard-eyebrow', text: str(card.eyebrow) }) : null,
      h('h3', { class: 'gcard-title', text: str(card.title) }),
      card.subtitle ? h('div', { class: 'gcard-subtitle', text: str(card.subtitle) }) : null),
    badge),
  liveLines.length || card.liveTrailing ? h('div', { class: 'gcard-live' },
    statusDot(card.liveStatus),
    h('div', { class: 'gcard-live-text' }, liveLines.map((line, index) => h('div', { class: index ? 'muted' : '', text: str(line) }))),
    card.liveTrailing ? h('div', { class: 'gcard-live-trailing', text: str(card.liveTrailing) }) : null) : null,
  body.length ? h('div', { class: 'gcard-body' }, body.map((block) => {
    const render = block && BLOCKS[block.type];
    return render ? render(block) : null;
  })) : null,
  actions.length ? h('footer', { class: 'gcard-actions' }, actions.map(actionChip)) : null);
}
