# SamRabbit Mac bridge (Heptabase journal and Mac control)

The R1 writes your words and activity lines into your Heptabase journal. This
bridge lets it do that through the Heptabase desktop app on your Mac, using the
app's own CLI (`heptabase journal append` / `journal read`). There is no
Heptabase sign-in and nothing to click in Heptabase.

The same bridge also lets the R1's voice orchestrator see and control this Mac
(what is open, reading a window, opening apps and links, simple clicks and
shortcuts, and screenshots once screen vision is allowed). See "Mac control" below.

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
2. copies the bridge (`samrabbit_bridge.py` and `samrabbit_mac.py`) to `~/Library/Application Support/SamRabbit/bridge/`;
3. writes `~/Library/LaunchAgents/com.samrabbit.bridge.plist` (RunAtLoad, KeepAlive, a PATH that includes
   `/opt/homebrew/bin`, and logs to `~/Library/Logs/samrabbit-bridge.log`);
4. reloads the agent (`launchctl bootout`/`bootstrap`/`kickstart`), waits for `/health`, and prints the bridge URL.

Then point the R1 at the bridge. You can do this from the R1's management page (Connections > Heptabase journal >
"Connect through your Mac", then paste the URL and token), or with the Mac helper
`~/jr-toolchain/heptabase-bridge-connect.sh`, which installs the bridge and configures the R1 over a paired
management session.

`uninstall.sh` stops and removes the agent and keeps the token. `uninstall.sh --purge` also deletes the token.

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
   host page, the last two only on a real user gesture, and a Chart.js helper).
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
| `GET /v1/ui/artifacts/<id>/document` | the assembled HTML document for a sandboxed iframe (`sandbox="allow-scripts"`), served with a `sandbox` CSP |

The R1 uses the bridge token from the local network. The desktop app may read the three GET routes from loopback
with its own token (`~/.config/samrabbit/desktop-token`, mode 0600) in the `X-SamRabbit-Desktop` header or the
`sr_desktop` cookie. Failures use stable codes: `generation_timeout`, `claude_busy`, `claude_signed_out`,
`claude_missing`, `invalid_widget`, `renderer_missing`, `render_timeout`, `image_too_large`, `interrupted`.

Settings: `--claude`, `--agent-browser`, `--artifacts-dir`, `--genui-model` (or `SAMRABBIT_CLAUDE`,
`SAMRABBIT_AGENT_BROWSER`, `SAMRABBIT_ARTIFACTS_DIR`, `SAMRABBIT_GENUI_MODEL`). To switch the model without
reinstalling, write `{"model": "claude-opus-5-5"}` to `~/.config/samrabbit/genui.json` (read on every
generation). The CLI is found on `PATH`, then `~/.local/bin/claude`; agent-browser on `PATH`, then the newest
`~/.hermes/tools/agent-browser-*`. `/health` reports `genui: {available, claude, renderer, model, queued, sync}`.

## Privacy

- The log has one line per request: method, route, status, duration, and an error code if there is one. It never
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
fake `cua-driver` / `open` / `lsappinfo` (`tests/fake_cua_driver.py`), generate UIs with a fake `claude`
(`tests/fake_claude.py`) and a fake renderer (one test renders for real when agent-browser is installed), and run the
installer against a throwaway home with `SAMRABBIT_SKIP_LAUNCHCTL=1`.

## Troubleshooting

- `app: reachable false, detail heptabase_app_unavailable`: open Heptabase, or run `heptabase start`, and check that
  CLI is enabled in Settings > AI Features. Entries wait on the R1 and go out once the app is back.
- Nothing answers on port 3780: run `launchctl print gui/$(id -u)/com.samrabbit.bridge` and
  `tail ~/Library/Logs/samrabbit-bridge.log`.
- The Mac's IP changed: run `heptabase-bridge-connect.sh` again, or update the URL on the R1's management page.
