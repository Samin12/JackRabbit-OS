// Time and text formatting.

const DAY = 86_400_000;

/** Epoch ms from epoch ms, epoch seconds, a numeric string or an ISO date; null otherwise. */
export function toMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value < 1e11 ? value * 1000 : value;
  if (typeof value === 'string' && value.trim()) {
    const number = Number(value);
    if (Number.isFinite(number)) return toMs(number);
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
  }
  return null;
}

export function startOfDay(ms) {
  const date = new Date(ms);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

export function dayLabel(ms, now = Date.now()) {
  const day = startOfDay(ms);
  const today = startOfDay(now);
  const diff = Math.round((today - day) / DAY);
  if (diff <= 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  const date = new Date(ms);
  if (diff < 7) return date.toLocaleDateString(undefined, { weekday: 'long' });
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleDateString(undefined, sameYear
    ? { weekday: 'short', month: 'long', day: 'numeric' }
    : { month: 'long', day: 'numeric', year: 'numeric' });
}

export function timeLabel(ms) {
  return new Date(ms).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** "10:42 AM" today, "Yesterday" / "Mon" this week, else "Sep 29". */
export function shortWhen(ms, now = Date.now()) {
  const diff = Math.round((startOfDay(now) - startOfDay(ms)) / DAY);
  if (diff <= 0) return timeLabel(ms);
  if (diff === 1) return 'Yesterday';
  const date = new Date(ms);
  if (diff < 7) return date.toLocaleDateString(undefined, { weekday: 'short' });
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function stamp(ms, now = Date.now()) {
  const label = dayLabel(ms, now);
  return `${label} at ${timeLabel(ms)}`;
}

export function relativeTime(ms, now = Date.now()) {
  const seconds = Math.max(0, (now - ms) / 1000);
  if (seconds < 45) return 'just now';
  if (seconds < 90) return '1 min ago';
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 5400) return '1 hr ago';
  if (seconds < 86400) return `${Math.round(seconds / 3600)} hr ago`;
  return shortWhen(ms, now);
}

export function clip(text, max) {
  const flat = String(text ?? '').replace(/\s+/g, ' ').trim();
  return flat.length > max ? `${flat.slice(0, max - 1).trimEnd()}…` : flat;
}

/** The text between two marker lines, or null when the markers are missing. */
export function between(text, begin, end) {
  const source = String(text ?? '');
  const start = source.indexOf(begin);
  if (start < 0) return null;
  const from = start + begin.length;
  const stop = source.indexOf(end, from);
  return source.slice(from, stop < 0 ? undefined : stop).trim();
}

export function humanize(name) {
  const words = String(name ?? '').replace(/[_.-]+/g, ' ').trim();
  return words ? words[0].toUpperCase() + words.slice(1) : 'Tool';
}

export function hostOf(url) {
  try {
    return new URL(url).host.replace(/^www\./, '');
  } catch {
    return String(url ?? '');
  }
}

export function baseName(path) {
  const parts = String(path ?? '').split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : String(path ?? '');
}

export function duration(ms) {
  const total = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function plural(count, word, many = `${word}s`) {
  return `${count} ${count === 1 ? word : many}`;
}

/** Parses tool results that are JSON text; returns the original value otherwise. */
export function parseMaybeJson(value) {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || !'[{'.includes(trimmed[0])) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

export function pretty(value) {
  const parsed = parseMaybeJson(value);
  if (typeof parsed === 'string') return parsed;
  try {
    return JSON.stringify(parsed, null, 2);
  } catch {
    return String(parsed);
  }
}
