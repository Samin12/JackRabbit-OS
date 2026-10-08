# SamRabbit desktop app

A native macOS app that mirrors every R1 voice conversation live, so no thread gets lost: what you
said, the answers (streaming), GenUI cards, T3 updates, tool calls, screenshots and camera photos,
and the UIs the Mac generates — all in one searchable timeline per conversation.

```
R1 app ─▶ R1 runtime ─▶ Mac bridge (sync store) ─▶ SamRabbit.app  (WKWebView ◀─ /app/ + /v1/sync/*)
```

- `SamRabbit.app` (bundle id `com.samrabbit.desktop`) is a small Swift/SwiftUI shell: a Dock app with a
  menu bar extra (live status, R1 last seen, recent conversations, Open SamRabbit) and one window that hosts
  a `WKWebView` on `http://127.0.0.1:3780/app/`.
- The UI itself is plain HTML/CSS/ES modules in `web/` (no build step). The bridge serves it at `/app/`
  from the installed bundle (`Contents/Resources/web`) through `companion/mac-bridge/samrabbit_app.py`.
- Data comes from the bridge's desktop sync API (CONTRACTS-WAVE3 hop 3): `GET /v1/sync/conversations`,
  `GET /v1/sync/conversations/<id>/events?after=`, the live `GET /v1/sync/stream` (SSE), blobs and
  generated-UI documents. Generated UIs run in sandboxed iframes (`sandbox="allow-scripts"`, no same-origin).

## Install

```sh
companion/desktop/install.sh            # options: --no-open, --no-login-item, --apps-dir /Applications
companion/mac-bridge/install.sh         # the bridge must be new enough to serve /app/ and /v1/sync/*
```

`install.sh` can be run again at any time. It builds the app (`build.sh`), replaces
`/Applications/SamRabbit.app` (quitting it first), creates the desktop token
`~/.config/samrabbit/desktop-token` (random, 0600, never printed) when missing, installs the login item
(LaunchAgent `com.samrabbit.desktop.login`: `open -g -a SamRabbit --args --login`, which starts it in
the background without a window), opens the app, and tells you whether the bridge already serves the page.

`uninstall.sh` removes the app and the login item (`--purge` also removes the desktop token, the log and
the app's settings).

Build only: `companion/desktop/build.sh` → `companion/desktop/build/SamRabbit.app` (needs Xcode's command
line tools; ~5 s; ad-hoc signed). The app icon is rendered from the R1's fluid-orb math
(`tools/render_icon.swift` + `Sources/OrbRenderer.swift`) and packed with `iconutil`.

## How it connects

- The app reads the desktop token and sets it as the `sr_desktop` cookie (HttpOnly, SameSite=Lax) in the web
  view's cookie store before loading the page; the first request also carries `X-SamRabbit-Desktop`. The
  bridge accepts the desktop token only from loopback peers; it opens nothing else on the bridge.
- Until the page loads, the window shows a native "Waiting for the SamRabbit bridge…" screen that says what
  is missing (bridge not running, bridge too old to serve `/app/`, token missing or rejected) and retries
  every few seconds. Once loaded, the page handles bridge restarts itself (SSE reconnect with backoff, resume
  by cursor, gap fill).
- Independently of the window, the app follows the SSE stream natively for the menu bar status and for
  notifications ("New conversation on your R1", "Generated UI ready"). Notifications are silent, appear only
  while SamRabbit isn't frontmost, and are skipped when permission is off (the menu then offers
  "Turn On Notifications…").
- Closing the window keeps SamRabbit running (menu bar + notifications); clicking the Dock icon reopens it.

Settings (for development; loopback URLs only):

```sh
defaults write com.samrabbit.desktop BaseURL http://127.0.0.1:3790/app/   # default http://127.0.0.1:3780/app/
defaults write com.samrabbit.desktop TokenFile /path/to/desktop-token     # default ~/.config/samrabbit/desktop-token
defaults delete com.samrabbit.desktop BaseURL                              # back to the real bridge
```

(`--base-url` / `--token-file` arguments and `SAMRABBIT_APP_URL` / `SAMRABBIT_DESKTOP_TOKEN_FILE` work too.)

Log: `~/Library/Logs/samrabbit-desktop.log` (0600) — states, HTTP statuses and counts only; never the token or
conversation content. Lines like `page loaded`, `page: status api=ok conversations=12 live=1` and
`page: diag images=3 imagesFailed=0 frames=2` confirm what the window loaded.

## Developing the UI

`dev/fake_sync_server.py` is a stdlib stand-in for the bridge's sync API with realistic sample conversations
(cards, a Mac screenshot, an R1 camera photo, T3 updates, tool calls, generated UIs) and a live conversation
that streams in:

```sh
python3 companion/desktop/dev/fake_sync_server.py --port 3790          # --empty, --no-sync, --live-loop 60
open http://127.0.0.1:3790/app/?chrome=1                               # needs the sr_desktop cookie, so use:
SR_TOKEN_FILE=~/.config/samrabbit/desktop-token companion/desktop/dev/shoot.sh /tmp/shots 3790
curl -X POST -H "X-SamRabbit-Desktop: $(cat ~/.config/samrabbit/desktop-token)" http://127.0.0.1:3790/dev/live
```

`shoot.sh` drives headless Chrome (agent-browser) and takes screenshots; `STEPS="open:<id> top shot:name …"`
scripts states. `?chrome=1` draws stand-in traffic lights so the page looks like it does in the app.

Event types the UI renders (`web/store.js`): `message.user`, `message.assistant.delta|done|interrupted`,
`card.shown|updated|dismissed`, `host.t3_update`, `host.note`, `host.completion`, `ui.event`, `image`,
`tool.call|completed` (GenUI card tools hidden, `ui_generate` hidden behind its UI card, `blobId` on a tool shows
the image), `ui.generating|generated|failed`, `conversation.started|ended`, `session.connected` (reconnects),
`session.ended` (failures only), `session.finalized`. Unknown types are ignored; every event is applied once by
`id`.

## Tests

```sh
node --test companion/desktop/tests/store.test.mjs          # timeline/store logic
python3 -m unittest discover -s companion/desktop/tests     # install.sh / uninstall.sh in a throwaway home
python3 -m unittest discover -s companion/mac-bridge/tests  # includes the /app/ route (test_desktop_app.py)
```
