# SamRabbit for iPhone and Apple Watch

Native apps that control SamRabbit (the R1's voice orchestrator, T3 Code tasks, the calendar, the Heptabase
journal, the Mac, generated UIs and the R1's conversations) through one hub: the Mac bridge's mobile API
(`/v1/mobile/*`, see `CONTRACTS-WAVE4`). Phone, widgets and watch talk only to the bridge, with a
per-device bearer token, over the local network (or Tailscale).

```
apple/
  project.yml            XcodeGen spec (the .xcodeproj is generated, never committed or hand-edited)
  generate.sh            generates SamRabbit.xcodeproj (and renders the icon if it is missing)
  Config/Signing.xcconfig   the one place for signing (DEVELOPMENT_TEAM)
  Shared/SamRabbitKit/   Swift package used by every target (models, client, storage, orb, theme, ...)
  Shared/Intents/        App Intents compiled into the app and the widget extension
  iOS/App/               the iPhone app (SamRabbit, com.samrabbit.mobile)
  iOS/Widgets/           widget extension (com.samrabbit.mobile.widgets): widgets, control, Live Activity
  iOS/WidgetViews/       widget faces, shared by the extension and the app (gallery, render harness)
  iOS/Resources/         asset catalog (AppIcon from the orb, accent and launch colours)
  watchOS/App/           the Apple Watch app (SamRabbitWatch, com.samrabbit.mobile.watchkitapp), embedded in the iPhone app
  watchOS/Widgets/       complications (com.samrabbit.mobile.watchkitapp.widgets)
  watchOS/ComplicationViews/  complication faces, shared by the complications and the watch app's preview renderer
  watchOS/UITests/       the watch walkthrough (real taps against the fake bridge) and the watch-face setup
  watchOS/Resources/     the watch's asset catalog (the same orb icon)
  tools/render_icon.swift   renders the 1024 px icon with SamRabbitKit's orb math
  dev/fake_bridge.py     the whole mobile API with fixtures, for simulator work and tests
  dev/run-sim.sh         build, install and launch on a simulator
  dev/run-watch.sh       build, install and launch the watch app on the watch paired with the booted iPhone
```

## Quick start (simulator, no Apple ID needed)

```sh
apple/generate.sh                                   # needs XcodeGen 2.46+ (brew install xcodegen)
/usr/bin/python3 -I apple/dev/fake_bridge.py        # fake bridge on 127.0.0.1:3799, pairing code SAMRABBT
apple/dev/run-sim.sh                                # build + install + launch on the booted iPhone simulator
xcrun simctl openurl booted 'samrabbit://pair?h=127.0.0.1:3799&c=SAMRABBT&n=Fake%20Mac'
```

Or in the app: Settings > Enter code manually, address `127.0.0.1:3799`, code `SAMR-ABBT`.

The Apple Watch pairs itself through the iPhone (no code on the watch):

```sh
xcrun simctl pair <watch-udid> <iphone-udid>        # once; e.g. Apple Watch Series 12 (46mm) with iPhone 18 Pro
apple/dev/run-watch.sh                              # build + install + launch on that watch
apple/dev/run-watch.sh -- -SamRabbitPage needs      # open on a page (status, needs, working, upnext, quick)
```

Installing the iPhone app also carries the watch app (`SamRabbit.app/Watch/`); on a real iPhone the Watch app
installs it. On the simulator `run-watch.sh` installs it directly.

The fake bridge never touches T3, Google Calendar, Heptabase, the R1 or the live bridge on :3780. It invents
everything in memory (only pairings persist, in `apple/dev/.state/`). Approving, answering and replying move
threads along on their own; new tasks run about 45 s (so the Live Activity has something to show); the live R1
conversation gets a streamed exchange every minute (`--no-chatter` turns that off). Loopback helpers:
`POST /__fake/reset`, `POST /__fake/settle` (finish every pending step), `POST /__fake/chatter`,
`GET /__fake/journal`, `POST /__fake/rerequest {threadId, text?}` (the open approval or question was answered
elsewhere and T3 asks a new one: same thread, new `requestId`) and `POST /__fake/t3 {available}` (T3 Code stops
answering: the summary says `t3.available=false`, every `/v1/mobile/t3/*` route answers 503 `t3_unavailable`).
Pending approvals and questions carry `requestId` (questions also `questionId`) and `respond` refuses a stale id
with 409 `t3_request_not_pending`, exactly like the real bridge.

## Tests

```sh
cd apple/Shared/SamRabbitKit && swift test          # 56 tests on macOS, including the client against the fake bridge
xcodebuild -project apple/SamRabbit.xcodeproj -scheme SamRabbit \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test   # the same suite minus the fake-bridge tests, on iOS
curl -X POST 127.0.0.1:3799/__fake/reset             # then the watch walkthrough (real taps, screenshots attached):
xcodebuild -project apple/SamRabbit.xcodeproj -scheme SamRabbitWatch \
  -destination 'platform=watchOS Simulator,name=Apple Watch Series 12 (46mm)' test
```

The fake-bridge tests start `fake_bridge.py` on a free port per test (macOS only: the iOS simulator cannot spawn
processes). They cover pairing (bad codes, host failover and promotion), the summary, threads
(approve/answer/reply/stop/create, a stale card refused with 409, T3 down while the summary works), conversations
and events into the timeline, SSE, blobs, generated UIs,
calendar, the journal, the Mac, child (watch) tokens and the shared actions/notification planning. The relay tests
run the watch's `BridgeClient` against a closed port, a port that never answers and the fake bridge, with the iPhone's
half of the relay in process: reads and unsent writes go through the phone, a write that may have reached the Mac
(a POST that timed out) is never sent twice, and the bridge's error envelope survives the relay.

The watch walkthrough (`watchOS/UITests/WatchWalkthroughTests.swift`, needs the fake bridge and the paired simulators)
opens every page, approves, answers a question, taps Approve on a card whose request was replaced on the Mac
(refused: "Request changed", the new request shows), opens a task, blocks 30 minutes, types a journal note and an Ask into
the system input sheet, runs a request through the iPhone (`-SamRabbitRoute phone`) and, after the watch's token is
revoked on the bridge, reconnects through the iPhone. `WatchFaceTests` (opt-in, `TEST_RUNNER_SAMRABBIT_FACES=setup`)
adds Infograph, Modular and Activity Digital faces with the SamRabbit complications and screenshots them.

## Installing on your own iPhone and Apple Watch

1. Xcode > Settings > Accounts: add your Apple ID.
2. Create `apple/Config/Signing.local.xcconfig` (git-ignored):
   ```
   DEVELOPMENT_TEAM = ABCDE12345
   CODE_SIGN_STYLE = Automatic
   CODE_SIGN_IDENTITY = Apple Development
   ```
3. `apple/generate.sh --open`, pick your iPhone, Run. Automatic signing registers the App Group
   `group.com.samrabbit.mobile` and the keychain group for every target (the watch app and its complications too).
4. On the Mac: SamRabbit > Pair iPhone… and scan the QR code with the Camera app (or in Settings > Scan).
5. The watch app comes with the iPhone app: install it from the Watch app on the iPhone (My Watch > SamRabbit), or
   run the `SamRabbitWatch` scheme on the watch. It connects through the iPhone by itself; add the complications by
   editing a watch face (SamRabbit > Needs You / Next Up).

## How it is built

**SamRabbitKit** (local Swift package, iOS 26 / watchOS 26 / macOS 26, Swift 6 language mode). Public API:

| Area | Types |
|---|---|
| Models (lenient decoding, ISO-8601 or epoch-ms dates) | `MobileSummary`, `TaskThread`, `ThreadStatus`, `PendingAction`, `ThreadDetail`, `ConversationSummary`, `SyncEvent`, `CalendarEvent`, `Agenda`, `MacState`, `GeneratedArtifact`, `JSONValue`, `MobileSummary.sample()` |
| Client | `BridgeClient` (every mobile route, multi-host failover, `stream(after:)` SSE, `raw(_:)`), `BridgeError`, `LiveSyncFeed` (reconnecting SSE), `ServerSentEventParser` |
| Pairing | `PairLink` (`samrabbit://pair?h=&c=&n=`), `BridgeHost`, `PairingCode`, `Pairer` (tries hosts in order), `PairResponse` |
| Storage | `BridgeAccount` (pairing in the App Group, token in the Keychain), `KeychainStore`, `SharedContainer`, `SummaryCache`, `TaskTracker` |
| Shared behaviour | `SamRabbitActions` (ask, block, note, open on Mac, approve, answer, refresh + widget reload, spoken "what needs me"), `AlertPlanner`, `SpokenSummary`, `Formatting` |
| Conversations | `ConversationTimeline` (port of the desktop `store.js`), `TimelineItem`, `GenCard` |
| Look | `SamTheme` (colours, `glassCard()`, `samScreen()`, `StatusChip`, `PulseDot`, `SectionHeader`), `OrbView(mood:)`, `OrbRenderer`, `OrbMood` |
| Watch link | `WatchContext` (applicationContext), `WatchRelay` (sendMessage relay), `BridgeRelay` (the client's second route) |
| Live Activity | `TaskActivityAttributes` (iOS) |

**The orb** is the desktop `OrbRenderer.swift` math, unchanged, evaluated on the CPU: `OrbRenderer.field`
renders only the surface at about one sample per point (soft gradients, bilinear upscaling), and `OrbView`
clips it to a vector circle and draws the halo as a gradient, at 30 fps in a `TimelineView` (paused with Reduce
Motion; `animated: false` for widgets). No Metal shader: it would need the separately downloaded Metal toolchain
on every Mac that builds the app.

**The app**: Home (orb header, quick actions, Needs you with inline Approve/Deny/answers, Working, Up next,
latest conversation), Chats (search, live badges, timeline with cards/images/generated UIs, live over SSE,
generated UIs open interactive in a `WKWebView` with a non-persistent store and a content rule list that only
allows the four CDNs the documents import from: `GeneratedUISandbox`, one rule per scheme and host because WebKit
rejects `|` in `url-filter`; the list is compiled before anything loads and the viewer refuses to open the document
when it does not compile; `SandboxTests` compile it and, on macOS, check in a real web view that a page reaches a
local server without the list and nothing with it), Tasks (sections, Markdown thread detail, approve/deny/answer,
composer with dictation, Stop, New task with a project picker), Mac (state, open app or link, screenshot with
pinch zoom, Generate UI), Settings (QR scan via VisionKit, manual entry, `samrabbit://pair` links, bridge
addresses, notifications, widget gallery). Dictation: the keyboard mic everywhere, plus a mic button
(`SFSpeechRecognizer`, on-device when available).

**Deep links**: `samrabbit://pair?…`, `ask[?text=]`, `note`, `generate[?text=]`, `block[?minutes=]`,
`thread/<id>`, `conversation/<id>`, `tab/<home|chats|tasks|mac|settings>`, `mac/screenshot`.

**Widgets** (`SamRabbitWidgets`): Status (small), Tasks (medium), Up Next (medium, interactive Block 30m),
Dashboard (large, Ask + Block), Needs You (Lock Screen circular + inline), Next Up (Lock Screen rectangular),
the "Ask SamRabbit" control, and the "Task running" Live Activity (Lock Screen + Dynamic Island). The timeline
provider reads the summary the app saved in the App Group and fetches a fresh one itself (8 s budget), every
15 minutes; the app and every action reload the widgets.

**App Intents** (Siri and Shortcuts, phrases in `AppShortcuts.swift`): Ask SamRabbit, Block Time ("Block 30
minutes with SamRabbit"), Add Journal Note, What Needs Me (spoken), Open on Mac, Screenshot My Mac.

**Notifications** (no push): `BGAppRefreshTask` (`com.samrabbit.mobile.refresh`, about every 15 min) and the
foreground refresh (every 20 s) run `AlertPlanner`: a local notification for each new needs-you item and for
tasks started from the phone that finish. The Simulator has no `BGTaskScheduler` ("not available on this
platform"), so the background part only runs on a device; to try it there from the debugger:
`e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.samrabbit.mobile.refresh"]`.

## What the app expects where the contract leaves room

Field names follow CONTRACTS-WAVE4 exactly; decoding is lenient everywhere (unknown fields ignored, missing ones
defaulted, alternative names accepted), so these are the shapes `fake_bridge.py` serves:

- Dates: ISO-8601 text (with or without fractional seconds or an offset) or epoch milliseconds.
- `GET /conversations` → `{conversations:[{conversationId,title,startedAt,lastAt,endedAt,live,messageCount,preview,device,cursor}],cursor,nextBefore?}`; events → `{events:[{…,cursor}],cursor,more}`; `/stream` like the desktop stream (`: ready <cursor>`, `id:`/`event: sync`/`data:`).
- `GET /t3/projects` → `{projects:[{projectId|id, name|title}]}`. `POST /t3/threads` → `{threadId,title,projectName}`. Message/respond/stop → any 2xx.
- `pending` → `{kind:"approval"|"question", requestId, questionId?, text, options?}`. Approve / Deny / Answer send `{decision, requestId}` / `{answer, requestId}` for the request the card shows; 409 `t3_request_not_pending` means it changed: the app and the watch say "That request changed, check it again" and refresh. A card without a `requestId` (summary threads carry no pending details) only opens the thread.
- The summary and the thread list are fetched side by side and fail on their own: while T3 Code is not running (`/t3/threads` 503 `t3_unavailable`, `t3_app_missing`, …) Home, the orb, the calendar and the widgets stay fresh and only Needs you / Tasks (and the watch's task pages) say "T3 not connected", over the last list.
- `pending.options` for questions: strings or `{value|decision, label}` objects.
- `GET /calendar/agenda` → `{events:[{eventId?,title,startsAt,endsAt,allDay,location,meetingUrl}]}`; block/events → `{event:{…}}` (an optional `dryRun:true` is shown as "test copy").
- `POST /journal` → `{recorded,state:"sent"|"queued",date}`.
- `GET /mac/state` → the `/v1/mac/state` shape plus `name`/`online` (`computer`, `front:{app,window}`, `visible:[{app,windows}]`, `running`, `screenLocked`, `screenVision`).
- `POST /ui/generate` → `{artifactId}`; artifact status as `/v1/ui/artifacts/<id>`; `/document` is served as HTML.
- `POST /devices/child` → `{token,deviceId,…}` like `pair`.
- Errors: `{error:{code,message,retryable}}`; 401 means "pair again".

## The Apple Watch app

**Pages** (Digital Crown or swipe, a vertically paged `TabView`):

1. **Status**: the animated orb (24 fps, still on the always-on display), "2 need you" / "1 working" / "All clear",
   R1 live or last seen, and a big **Ask** button: the system text input (dictation first on a watch) starts a T3
   task with automatic placement.
2. **Needs you**: a card per waiting task with **Approve** / **Deny** for approvals, the offered answers as buttons for
   questions, and **Reply** / **Answer** by dictation. Tap a card for the task.
3. **Working**: running tasks; a task shows its latest messages with Reply (dictation), Approve / Deny and **Stop**.
4. **Up next**: the next events from the summary, with "Now" / "in 25m", place or video call.
5. **Quick**: **Block 30m** (a Focus block from now), **Journal note** (your own words, by dictation), and which Mac,
   which route (direct or via iPhone) and how fresh, with a refresh.

A result line slides in after every action; failures say why ("Mac and iPhone are out of reach").

**Pairing and networking.** The watch never pairs on its own. The iPhone issues the watch its own child token
(`POST /v1/mobile/devices/child`, revocable from the Mac) and sends it with the bridge addresses in
`applicationContext` (`WatchContext`); a watch without a pairing asks for it with `sendMessage`
(`WatchContext.requestKey`). The watch stores it like the phone does (App Group + Keychain), so the complications use
it too. Every request goes to the bridge directly (`URLSession`, 6 s). The watch's `BridgeClient` has the iPhone as
its `BridgeRelay` (`watchOS/App/PhoneLink.swift`): a request that reached no address goes to the phone with
`sendMessage` (`WatchRelay`), the phone performs it with its own token and answers with the bridge's status and body.
After a direct failure the watch prefers the phone for two minutes. A POST that may have reached the Mac (it timed
out) is never sent again another way. If the Mac revokes the watch, the status page offers **Reconnect**, which asks
the phone for a new child token (`WatchContext.reissueValue`). Re-pairing the iPhone reissues the watch's token too.

**Complications** (`SamRabbitWatchWidgets`, WidgetKit accessory families): **Needs You**: circular (the count in a
ring of everything open), corner (the count, with the next event along the bezel), inline ("2 need you · 2 working",
shorter when the slot is small); **Next Up**: rectangular (next event, time and "in 20m", working and needs-you
counts). The timeline reads the summary the watch app saved and fetches a fresh one itself, with an entry every 5
minutes for an hour (so "in 20m" stays right) and a refresh every 15 minutes; the app reloads them after each refresh.
Tapping opens the matching page (`samrabbit://tab/needs`, `samrabbit://tab/upnext`).

**Debug launch arguments**: `-SamRabbitPage <status|needs|working|upnext|quick>`, `-SamRabbitRoute phone` (everything
through the iPhone), `-SamRabbitRenderComplications YES` (renders the complication faces into Documents/renders).

**Kit additions for the watch**: `BridgeRelay` and `BridgeClient(relay:)`, `BridgeAccount(relay:)`,
`WatchContext.requestKey`/`reissueValue`, `OrbView(frameRate:)`. The iPhone side (`iOS/App/WatchLink.swift`) answers
context requests and can reissue the child token.

## Screenshots

`~/Movies/SamRabbit-tests/wave4/ios-*.png` (each tab, thread detail, chats with a generated UI, pairing,
Home Screen and Lock Screen widgets added in the simulator, Live Activity) and `widget-renders/` (the
`-SamRabbitRenderWidgets` launch argument renders every widget face with `ImageRenderer` into the app's
Documents/renders), `ios-stale-request-refused.png`, `ios-home-t3-down.png`, `ios-tasks-t3-down.png`. The watch:
`watch-1…9-*.png` (every page and action from the walkthrough, `watch-2b-request-changed.png` the stale approval
refused), `watch-needs-you-t3-down.png` and
`watch-face-*.png` (the complications on Infograph, Modular and Activity Digital faces), plus `watch-renders/`.
