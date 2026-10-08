# SamRabbit Mac bridge (Heptabase journal and Mac control)

The R1 writes your words and activity lines into your Heptabase journal. This
bridge lets it do that through the Heptabase desktop app on your Mac, using the
app's own CLI (`heptabase journal append` / `journal read`). There is no
Heptabase sign-in and nothing to click in Heptabase.

The same bridge also lets the R1's voice orchestrator see and control this Mac
(what is open, reading a window, opening apps and links, simple clicks and
shortcuts, and screenshots once screen vision is allowed). See "Mac control" below.

It also keeps a live copy of every R1 voice conversation for the SamRabbit desktop app.
See "Conversation sync" below.

```
R1 runtime ──HTTP (LAN, bearer token)──▶ samrabbit_bridge.py on the Mac ──▶ heptabase CLI ──▶ Heptabase app
```

## Requirements

- The Heptabase desktop app is running, with its CLI turned on
  (Heptabase > Settings > AI Features). `heptabase --version` should print `0.7.x`.
- macOS system Python 3.9+ (`/usr/bin/python3`). The bridge uses only the standard library.
- The R1 and the Mac are on the same local network.
- For Mac control: the `cua-driver` CLI with its daemon running and Accessibility granted to CuaDriver.app.
  The bridge finds it on `PATH`, in `/Applications/CuaDriver.app`, or in `~/.hermes/tools/cua-driver-*`
  (newest version), or use `--cua-driver <path>` / `SAMRABBIT_CUA_DRIVER`.

## Install

```sh
companion/mac-bridge/install.sh            # options: --port 3780 --host 0.0.0.0 --python /usr/bin/python3
```

You can run it again at any time. It:

1. creates `~/.config/samrabbit/bridge-token` (random, mode 0600) if it doesn't exist yet, and never prints it;
   the same for `~/.config/samrabbit/desktop-token` (the desktop app's own token), and creates the conversation store
   folder `~/Library/Application Support/SamRabbit/sync/` (0700);
2. copies the bridge (`samrabbit_bridge.py`, `samrabbit_mac.py` and `samrabbit_sync.py`) to
   `~/Library/Application Support/SamRabbit/bridge/`;
3. writes `~/Library/LaunchAgents/com.samrabbit.bridge.plist` (RunAtLoad, KeepAlive, a PATH that includes
   `/opt/homebrew/bin`, `--sync-dir` and `--desktop-token-file`, and logs to `~/Library/Logs/samrabbit-bridge.log`);
4. reloads the agent (`launchctl bootout`/`bootstrap`/`kickstart`), waits for `/health`, and prints the bridge URL.

Then point the R1 at the bridge from the R1's management page (Connections > Heptabase journal >
"Connect through your Mac"), and paste the URL and token.

If you use the Heptabase sign-in instead of the bridge, `heptabase-connect.py` in this folder finishes it from the
Mac: `python3 companion/mac-bridge/heptabase-connect.py --r1 https://<R1 address>:8443 --pairing-code <code on the R1>`.
It pairs a management session, listens on the loopback redirect the R1 returns, opens Heptabase's Allow screen,
and forwards the one-time code to the R1, which does the token exchange itself. It uses only the standard library.

`uninstall.sh` stops and removes the agent and keeps the tokens. `uninstall.sh --purge` also deletes both tokens.
Neither ever deletes the synced conversations in `~/Library/Application Support/SamRabbit/sync/`.

To update an installed bridge (for example to add conversation sync), run `install.sh` again: it keeps both tokens
and the R1's pairing, copies the new files and restarts the agent (a few seconds of downtime; the R1's journal and
sync outboxes simply retry).

## HTTP API

Every route needs `Authorization: Bearer <token>`, and the token is compared in constant time. Only loopback and
private-LAN peers are accepted (10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7, fe80::/10).

| Route | Result |
|---|---|
| `GET /health` | `{ok, service, version, cli:{available, version}, app:{reachable, detail}, mac:{…capabilities}, checkedAt}` (cached 10 s) |
| `POST /v1/heptabase/journal/append` `{date:"YYYY-MM-DD", content:"<markdown>"}` | the CLI's `{date, title, contentMd5}` |
| `GET /v1/heptabase/journal/read?date=YYYY-MM-DD` | `{date, title, text, contentMd5}`: the day as plain text lines (paragraphs, headings, `- ` bullets, `1. ` numbers, `[ ]`/`[x]` todos, `+ ` toggles, `> ` quotes; marks removed; nested items indented) |

- `content` is Markdown as the desktop CLI parses it: CommonMark plus Heptabase's list (`+` toggles, `- [ ]` todos)
  and `{{...}}` mention syntax. Hepta Markdown tags such as `<hepta-color>` are **not** parsed and would show as
  literal text, so the R1 sends its gray action lines as italics on this path.
- The body may be at most 64 KB. `content` is written to a private 0600 temp file, passed with
  `--content-file`, and deleted right after.
- Appends run one at a time. Each CLI call has a 30 s limit.
- Errors look like `{"error":{"code","message","retryable","written"[,"reason"]}}`. `written` is `false` when the
  journal definitely wasn't changed and `"unknown"` when it might have been, for example after a CLI timeout. The
  R1's outbox retries `false` errors with backoff. For `"unknown"` it first reads the day back and looks for the
  entry, and resends only if it's missing.

| Status | Code | Meaning |
|---|---|---|
| 401 | `unauthorized` | missing or wrong token |
| 400 / 411 / 413 | `invalid_date`, `invalid_content`, `invalid_json`, `length_required`, `body_too_large` | bad request |
| 422 | `heptabase_rejected` (+ `reason`) | the app refused the content |
| 503 | `heptabase_app_unavailable` | the Heptabase app is closed or its CLI is off (retry later) |
| 503 | `heptabase_cli_missing`, `heptabase_busy`, `heptabase_app_error`, `bridge_busy` | retry later |
| 504 | `heptabase_cli_timeout` | the CLI didn't answer in 30 s (`written: "unknown"`) |

## Mac control

All `/v1/mac/*` routes need the token **and** a loopback or private-LAN peer, even when the bridge runs with
`--allow-any-client`. They drive the Mac only through the `cua-driver` CLI (one call at a time, JSON on stdin, 15 s
per call) and LaunchServices `/usr/bin/open`. There is no AppleScript (no Automation prompts), no shell and no
`kill_app`.

| Route | Result |
|---|---|
| `GET /v1/mac/state` | `{computer, front:{app, window}, visible:[{app, windows}], running:[names], chrome:[{window, activeTab, activeUrl, tabs}], screenVision}`: titles trimmed, URLs without query or fragment |
| `POST /v1/mac/open` `{app}` \| `{url}` \| `{path}` | app: matched against installed apps (`list_apps`), started with `launch_app`, then `bring_to_front` (LaunchServices `open -b` as a fallback) so it really comes forward. url: http(s) only, `open -a "Google Chrome" <url>`. path: inside the home folder only; hidden folders, `~/Library`, apps, scripts, executables, Finder aliases and location files (`.webloc`, `.vncloc`, …) are refused |
| `GET /v1/mac/read?app=&max=` | `{app, window, text, controls, truncated?}`: the front (or named) app's front window as readable text (≤ 6000 chars) plus its button labels, from the accessibility tree (`get_window_state` without a screenshot) |
| `POST /v1/mac/act` `{action, …}` | one allowlisted step in the front (or `app`) window (background delivery first; when cua-driver refuses it, e.g. keys for an app with several windows, it is retried once in the foreground and the answer says `delivery: "foreground"`): `bring_to_front`; `hotkey` `{keys:"cmd+w"\|[…]}` (one key alone = a key press; log out, force quit, lock and delete-file chords are refused); `type_text` `{text ≤ 2000, label?}`; `click` `{label, role?, index?}` (an accessibility element from a fresh snapshot; several matches answer 409 `ambiguous` with `options`); `invoke_menu` `{path:["File","New Window"]}` (no Apple menu, shut down, log out, empty trash, move to trash); `scroll` `{direction, amount?, by?}`. In terminal apps (Terminal, iTerm2, Warp, Ghostty, …) and Script Editor, `type_text` and plain keys (return, arrows, ctrl+c, paste) answer 403 `terminal_blocked`: terminal work goes to a T3 agent (`mac_task`) |
| `GET /v1/mac/screenshot?max=1024&app=` | `{mime:"image/jpeg", base64, width, height, bytes}`: the screen (or the app's window), downscaled with `sips` to at most `max` px and 150 KB. Without Screen Recording it answers **409** `{"code":"screen_recording_required","fix":"Run on the Mac: <path>/cua-driver permissions grant", "error":{…}}` and does not try to capture |

Errors look like `{"error":{"code","message","retryable"[,"fix"][,"suggestions"|"options"]}}`, for example 404
`app_not_found` / `app_not_running` / `element_not_found`, 409 `accessibility_required` / `window_gone` / `ambiguous`,
503 `driver_missing` / `driver_unavailable` / `mac_busy`, 504 `driver_timeout`.

Screen vision needs one manual step on the Mac (it shows macOS consent dialogs, so only you can do it):
`~/.hermes/tools/cua-driver-0.21.0-darwin-arm64/CuaDriver.app/Contents/MacOS/cua-driver permissions grant`,
then allow CuaDriver under Screen & System Audio Recording. `/health` shows the exact command as
`mac.screenRecordingFix` while it is missing.

## Conversation sync

The R1 mirrors every voice conversation here, live, so the SamRabbit desktop app can show it (`samrabbit_sync.py`).
Nothing is ever deleted by the bridge.

```
R1 app ──(device-only)──▶ R1 runtime outbox ──POST /v1/sync/events, PUT /v1/sync/blobs──▶ bridge store
                                                                                           │
                         SamRabbit desktop app ◀──loopback + desktop token: list, events, SSE, blobs
```

Storage: `~/Library/Application Support/SamRabbit/sync/conversations.db` (SQLite, WAL, 0600) and content-addressed
images in `sync/blobs/<2 hex>/<sha256>` (folders 0700, files 0600). The bridge runs sync only when it has a sync folder:
the command line defaults to the one above (`--sync-dir`, `SAMRABBIT_SYNC_DIR`; an empty value turns sync off).

**From the R1** (bearer token + loopback/private-LAN peer, never relaxed by `--allow-any-client`):

| Route | Result |
|---|---|
| `POST /v1/sync/events` `{device:"r1", events:[…]}` (≤ 256 KB) | `{accepted, duplicates, rejected, cursor}`. `INSERT OR IGNORE` by event id, so a resend is harmless; malformed events are counted, never stored |
| `PUT /v1/sync/blobs/<sha256>` raw bytes, `Content-Type` (≤ 1 MB) | `{blobId:"sha256:<hex>", bytes, created}`; the hash is verified, a repeat is a no-op |

Events follow the shared contract: `id, type, conversationId, at` (epoch ms) and usually `sessionId, seq, origin`.
`message.assistant.delta` drafts keep only the newest copy per `messageId` and are removed once
`message.assistant.done`/`.interrupted` arrives.

**For the desktop app** (loopback peer only, a loopback `Host`/`Origin`, and the desktop token from
`~/.config/samrabbit/desktop-token` as header `X-SamRabbit-Desktop` or cookie `sr_desktop`; the R1's bearer token
is not accepted here, and the desktop token is not accepted on the R1 routes):

| Route | Result |
|---|---|
| `GET /v1/sync/conversations?limit=50&before=<lastAt>&q=` | `{conversations:[{conversationId, title, startedAt, lastAt, endedAt, live, messageCount, preview, device, cursor}], cursor, nextBefore?}` newest first. `title` is the first user utterance (or the first user line of a session's transcript); `live` = started, not ended and active in the last 20 minutes; `q` searches titles, previews and message text |
| `GET /v1/sync/conversations/<id>` | `{conversation}` |
| `GET /v1/sync/conversations/<id>/events?after=<cursor>&limit=200` | `{events:[…event, cursor], cursor, more}` in arrival order (sort by `at`/`seq` to display). Keep the returned `cursor` |
| `GET /v1/sync/stream?after=<cursor>` | Server-sent events across all conversations: `id: <cursor>`, `event: sync`, `data: <event JSON with cursor>`; a `: heartbeat` comment every 15 s; `Last-Event-ID` resumes; without `after` it starts at the newest event. At most 8 streams |
| `GET /v1/sync/blobs/<sha256>` | the bytes (`Content-Type` from the store, cached as immutable); 404 `blob_not_found` until it arrives |
| `GET /v1/sync/status` | `{conversations, events, blobs, cursor, lastReceivedAt, version}` |

Other bridge modules (generative UI) add to the same timeline in-process:
`samrabbit_sync.record_local_event(conversation_id, {"type": "ui.generated", …})` (id `mac:<uuid>`, `origin: "mac"`)
and `samrabbit_sync.put_blob(bytes, mime) -> "sha256:<hex>"`. Both return None (never raise) when sync is off.
`samrabbit_sync.desktop_request_denied(handler)` (None = allowed, else a `SyncError` with `status`, `code`,
`payload()`) is the shared desktop-auth rule for their loopback routes; it works even when sync is off.

`GET /v1/mac/screenshot?conversation=<id>` (the R1 runtime passes it for `mac_look` while it syncs images) also keeps
the JPEG in the store (`blobId`) and files an `image` event (`source: "mac_screenshot"`, `imageEventId`); without a
conversation nothing is kept.
`/health` reports `sync: {available, version, desktopToken}`.

Try it against a second instance (never the one the R1 uses):

```sh
python3 -I companion/mac-bridge/samrabbit_bridge.py --port 3794 --token-file /tmp/t/bridge-token \
  --sync-dir /tmp/t/sync --desktop-token-file /tmp/t/desktop-token
curl -s -H "X-SamRabbit-Desktop: $(cat /tmp/t/desktop-token)" http://127.0.0.1:3794/v1/sync/conversations
```
## Generated UIs

When you ask the R1's voice for a chart, a diagram, a dashboard or an explainer, it calls the `ui_generate` voice
tool and the bridge makes the widget on this Mac (`samrabbit_genui.py`, assets in `genui/`):

1. `claude -p` (the headless Claude Code CLI with your own login; default model `claude-sonnet-5-5`) writes one JSON
   object `{title, summary, initialHeight, css, html, jsFunctions, jsExpressions}` following `genui/skill.md`, a
   condensed version of OpenGenerativeUI's skills (MIT, see `genui/NOTICE.md`). The CLI runs with every tool
   off (`--tools ""`), no MCP servers, no skills, plugins or hooks (`--safe-mode`), no session files, in an empty
   temporary folder; the prompt goes in on stdin. Invalid answers (and JavaScript errors at render time) get one
   repair round. The whole Claude phase is limited to 120 s.
2. The widget is assembled like OpenGenerativeUI's `buildFinalFrameContent` (CSP with the four CDN origins for
   scripts and connect, importmap, the design-system CSS mapped to the R1's dark palette, the widget css and html)
   plus a small bridge script (`widget-resize`, `send-prompt`, `open-link`, `widget-ready` postMessages to the
   host page, and a Chart.js helper). Its `sendPrompt` / `openLink` helpers post only during a real user gesture,
   but generated code can post any message itself: the host must check the gesture and treat them as untrusted.
3. agent-browser renders it in a sandboxed iframe at 480 px wide (2x), and `sips` makes the R1 preview JPEG
   (at most 960 px wide and 150 KB).

One request runs at a time (up to 8 wait in a queue); requests still running when the bridge stops are picked up
again on start (if under 30 minutes old). Artifacts stay in `~/Library/Application Support/SamRabbit/artifacts/<id>/`
(`meta.json`, `request.json`, `args.json`, `document.html`, `preview.png`, `preview.jpg`; private to you; kept by
`uninstall.sh`). With the conversation-sync module installed, `ui.generating` / `ui.generated` / `ui.failed` events
and the preview image land in the conversation's timeline.

| Route | Result |
|---|---|
| `POST /v1/ui/generate` `{requestId, prompt ≤ 4000, data? ≤ 24000 chars (string or JSON), conversationId?, size?: "r1"\|"desktop"}` | `202 {artifactId, status:"generating"}` at once. Idempotent per `requestId` (same artifact; `200` with the current state once finished). 503 `genui_busy` when 8 are queued |
| `GET /v1/ui/artifacts/<id>` | `{artifactId, status:"generating"\|"ready"\|"failed", title, summary, error?, errorMessage?, imageBlobId?, width?, height?, conversationId?, createdAt, readyAt?, generationMs?, renderMs?, totalMs?}` |
| `GET /v1/ui/artifacts/<id>/image` | the preview JPEG (`image/jpeg`), 409 `image_not_ready` before it is ready |
| `GET /v1/ui/artifacts/<id>/document` | the assembled HTML document for a sandboxed iframe (`sandbox="allow-scripts"`), served with a `sandbox` CSP; desktop app only |

The R1 uses the bridge token from the local network for the first three routes. The desktop app reads the three GET
routes from loopback with its own token (`~/.config/samrabbit/desktop-token`, mode 0600) in the `X-SamRabbit-Desktop`
header or the `sr_desktop` cookie (and a loopback `Host`, no foreign `Origin`, like the sync module's desktop API);
`/document` answers only the desktop app (403 `desktop_only` for the bearer token). Failures use stable codes: `generation_timeout`, `claude_busy`, `claude_signed_out`,
`claude_missing`, `invalid_widget`, `renderer_missing`, `render_timeout`, `image_too_large`, `interrupted`.

Settings: `--claude`, `--agent-browser`, `--artifacts-dir`, `--genui-model` (or `SAMRABBIT_CLAUDE`,
`SAMRABBIT_AGENT_BROWSER`, `SAMRABBIT_ARTIFACTS_DIR`, `SAMRABBIT_GENUI_MODEL`). To switch the model without
reinstalling, write `{"model": "claude-opus-5-5"}` to `~/.config/samrabbit/genui.json` (read on every
generation). The CLI is found on `PATH`, then `~/.local/bin/claude`; agent-browser on `PATH`, then the newest
`~/.hermes/tools/agent-browser-*`. `/health` reports `genui: {available, claude, renderer, model, queued, sync}`.
## Desktop app page (`/app/`)

`samrabbit_app.py` serves the SamRabbit desktop app's web UI (see `companion/desktop/README.md`) at
`GET /app/…`. It has its own rules, separate from the LAN routes above:

- the peer must be **loopback** (127.0.0.0/8, ::1); LAN peers get 403 even with `--allow-any-client`;
- the **desktop token** (`~/.config/samrabbit/desktop-token`, 0600, created by `companion/desktop/install.sh`)
  must be sent as the header `X-SamRabbit-Desktop` or the cookie `sr_desktop`. The bridge bearer token is not
  accepted here, and the desktop token opens none of the LAN routes;
- files come from `$SAMRABBIT_APP_WEB_DIR`, else `/Applications/SamRabbit.app/Contents/Resources/web`, else
  `companion/desktop/web` next to the bridge checkout. Only regular files with a known extension inside that folder
  are served (no listings, dotfiles, `..` or symlink escapes), with `Cache-Control: no-cache`, `nosniff` and a
  strict Content-Security-Policy on HTML. `/app` redirects to `/app/`.
- 401/403/404/503 answers are small HTML pages (a missing token file or app install says to run
  `companion/desktop/install.sh`). The log line is just `GET /app <status> <ms>`.

## Privacy

- The log has one line per request: method, route, status, duration, and an error code if there is one. It never
  contains journal text, window text, typed text, links, file names, query strings, CLI output or the token. Sync
  routes are logged as templates (`/v1/sync/conversations/{id}/events`), so not even conversation ids or image hashes
  are written; conversation text and image bytes never are.
  contains journal text, window text, typed text, links, file names, query strings, CLI output or the token.
  Generated UIs log only the artifact id, the outcome and timings: never the prompt, the data or the widget.
- Error replies never repeat the CLI's own messages, because they could quote journal content.
- The token file is re-read when it changes. To rotate it, delete the file, run `install.sh`, and connect the R1
  again.

## Tests

```sh
python3 -m unittest discover -s companion/mac-bridge/tests
```

The tests put a fake `heptabase` executable first on `PATH` (`tests/fake_heptabase.py`), drive Mac control through a
fake `cua-driver` / `open` / `lsappinfo` (`tests/fake_cua_driver.py`), and run the installer against a throwaway home
with `SAMRABBIT_SKIP_LAUNCHCTL=1`. `tests/test_sync.py` covers the conversation store, dedupe, drafts, blobs, the auth
matrix (R1 token vs desktop token, loopback vs a real LAN peer through this Mac's own address), SSE and screenshots.
fake `cua-driver` / `open` / `lsappinfo` (`tests/fake_cua_driver.py`), generate UIs with a fake `claude`
(`tests/fake_claude.py`) and a fake renderer (one test renders for real when agent-browser is installed), and run the
installer against a throwaway home with `SAMRABBIT_SKIP_LAUNCHCTL=1`.

## Troubleshooting

- `app: reachable false, detail heptabase_app_unavailable`: open Heptabase, or run `heptabase start`, and check that
  CLI is enabled in Settings > AI Features. Entries wait on the R1 and go out once the app is back.
- Nothing answers on port 3780: run `launchctl print gui/$(id -u)/com.samrabbit.bridge` and
  `tail ~/Library/Logs/samrabbit-bridge.log`.
- The desktop app shows no conversations: `/health` should say `sync.available: true` (else run `install.sh` again
  to install `samrabbit_sync.py`), and `desktopToken: true` (else the desktop token file is missing). The R1's
  management API (`GET /v1/management/conversation-sync`) shows its outbox: `pending`, `lastError`
  (`bridge_outdated` = this bridge predates sync), `lastOkAt`.
- The Mac's IP changed: run `install.sh` again to print the new address, then update the URL on the R1's management page.
