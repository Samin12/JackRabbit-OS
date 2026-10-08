// The bridge's desktop sync API (CONTRACTS-WAVE3 hop 3). Same origin; the desktop token travels
// as the sr_desktop cookie that the native app sets before loading this page.

export class ApiError extends Error {
  constructor(status, code, message, detail = '') {
    super(message);
    this.status = status;
    this.code = code;
    this.detail = detail;
  }

  /** A bridge without the sync module answers its own bearer-token 401 for /v1/sync/*. */
  get syncMissing() {
    return this.status === 404 || (this.status === 401 && /bridge token/i.test(this.detail));
  }
}

async function getJSON(path, { signal } = {}) {
  let response;
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal,
    });
  } catch (error) {
    if (error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'unreachable', 'The SamRabbit bridge is not answering.');
  }
  if (!response.ok) {
    let code = '';
    let detail = '';
    try {
      const body = await response.json();
      code = (body && body.error && body.error.code) || '';
      detail = (body && body.error && body.error.message) || '';
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, code, `HTTP ${response.status}`, String(detail).slice(0, 200));
  }
  try {
    return await response.json();
  } catch {
    throw new ApiError(response.status, 'bad_json', 'The bridge sent something unreadable.');
  }
}

function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** "sha256:<hex>" -> "<hex>" (the path form of a blob id). */
export function blobPath(blobId) {
  const id = String(blobId ?? '');
  return id.startsWith('sha256:') ? id.slice(7) : id;
}

export const api = {
  conversations({ limit = 100, before, q, signal } = {}) {
    return getJSON(`/v1/sync/conversations${query({ limit, before, q })}`, { signal });
  },
  events(conversationId, after, { signal } = {}) {
    return getJSON(`/v1/sync/conversations/${encodeURIComponent(conversationId)}/events${query({ after })}`,
      { signal });
  },
  blobUrl(blobId) {
    return `/v1/sync/blobs/${encodeURIComponent(blobPath(blobId))}`;
  },
  documentUrl(artifactId) {
    return `/v1/ui/artifacts/${encodeURIComponent(artifactId)}/document`;
  },
  async documentText(artifactId) {
    const response = await fetch(this.documentUrl(artifactId), { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) throw new ApiError(response.status, '', `HTTP ${response.status}`);
    return response.text();
  },
};

/** Normalizes one SSE payload: either the event itself or {event, cursor}. */
export function unwrapStreamPayload(data) {
  if (data && typeof data === 'object' && data.event && typeof data.event === 'object' && !data.type) {
    return { event: data.event, cursor: data.cursor ?? null };
  }
  return { event: data, cursor: data && typeof data === 'object' ? (data.cursor ?? null) : null };
}

const BACKOFF_MS = [1000, 2000, 4000, 8000, 15000];

/**
 * GET /v1/sync/stream (SSE, `event: sync`) with our own reconnect: resumes with ?after=<cursor>
 * (the last SSE id, or the cursor inside the payload) and reports state changes so the UI can
 * re-fetch anything it might have missed while the stream was down.
 */
export class SyncStream {
  constructor({ onEvent, onState, onResume }) {
    this.onEvent = onEvent;
    this.onState = onState;
    this.onResume = onResume;
    this.cursor = null;
    this.source = null;
    this.attempt = 0;
    this.timer = 0;
    this.state = 'connecting';
    this.hadError = false;
    this.stopped = false;
  }

  start() {
    this.stopped = false;
    this.open();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    if (this.source) this.source.close();
    this.source = null;
  }

  setState(state) {
    if (state === this.state) return;
    this.state = state;
    this.onState?.(state);
  }

  open() {
    clearTimeout(this.timer);
    if (this.source) this.source.close();
    const url = `/v1/sync/stream${this.cursor != null ? `?after=${encodeURIComponent(this.cursor)}` : ''}`;
    let source;
    try {
      source = new EventSource(url, { withCredentials: true });
    } catch {
      this.retry();
      return;
    }
    this.source = source;
    const handle = (message) => this.message(message);
    source.addEventListener('sync', handle);
    source.addEventListener('message', handle);
    source.addEventListener('open', () => {
      this.attempt = 0;
      this.hadError = false;
      this.setState('live');
      // Every (re)connect: fetch what may have happened while the stream was not open.
      this.onResume?.();
    });
    source.addEventListener('error', () => {
      if (source !== this.source) return;
      source.close();
      this.source = null;
      this.hadError = true;
      this.retry();
    });
  }

  retry() {
    if (this.stopped) return;
    this.setState('reconnecting');
    const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt += 1;
    this.timer = setTimeout(() => this.open(), delay);
  }

  message(message) {
    let data;
    try {
      data = JSON.parse(message.data);
    } catch {
      return;
    }
    const { event, cursor } = unwrapStreamPayload(data);
    if (message.lastEventId) this.cursor = message.lastEventId;
    else if (cursor != null) this.cursor = cursor;
    if (event && typeof event === 'object') this.onEvent?.(event);
  }
}
