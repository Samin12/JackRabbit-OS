# SamRabbit for iPhone (and Apple Watch)

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
  tools/render_icon.swift   renders the 1024 px icon with SamRabbitKit's orb math
  dev/fake_bridge.py     the whole mobile API with fixtures, for simulator work and tests
  dev/run-sim.sh         build, install and launch on a simulator
```

## Quick start (simulator, no Apple ID needed)

```sh
apple/generate.sh                                   # needs XcodeGen 2.46+ (brew install xcodegen)
/usr/bin/python3 -I apple/dev/fake_bridge.py        # fake bridge on 127.0.0.1:3799, pairing code SAMRABBT
apple/dev/run-sim.sh                                # build + install + launch on the booted iPhone simulator
xcrun simctl openurl booted 'samrabbit://pair?h=127.0.0.1:3799&c=SAMRABBT&n=Fake%20Mac'
```

Or in the app: Settings > Enter code manually, address `127.0.0.1:3799`, code `SAMR-ABBT`.

The fake bridge never touches T3, Google Calendar, Heptabase, the R1 or the live bridge on :3780. It invents
everything in memory (only pairings persist, in `apple/dev/.state/`). Approving, answering and replying move
threads along on their own; new tasks run about 45 s (so the Live Activity has something to show); the live R1
conversation gets a streamed exchange every minute (`--no-chatter` turns that off). Loopback helpers:
`POST /__fake/reset`, `POST /__fake/settle` (finish every pending step), `POST /__fake/chatter`,
`GET /__fake/journal`.

## Tests

```sh
cd apple/Shared/SamRabbitKit && swift test          # 40 tests on macOS, including the client against the fake bridge
xcodebuild -project apple/SamRabbit.xcodeproj -scheme SamRabbit \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test   # the same suite minus the fake-bridge tests, on iOS
```

The fake-bridge tests start `fake_bridge.py` on a free port per test (macOS only: the iOS simulator cannot spawn
processes). They cover pairing (bad codes, host failover and promotion), the summary, threads
(approve/answer/reply/stop/create), conversations and events into the timeline, SSE, blobs, generated UIs,
calendar, the journal, the Mac, child (watch) tokens and the shared actions/notification planning.

## Installing on your own iPhone

1. Xcode > Settings > Accounts: add your Apple ID.
2. Create `apple/Config/Signing.local.xcconfig` (git-ignored):
   ```
   DEVELOPMENT_TEAM = ABCDE12345
   CODE_SIGN_STYLE = Automatic
   CODE_SIGN_IDENTITY = Apple Development
   ```
3. `apple/generate.sh --open`, pick your iPhone, Run. Automatic signing registers the App Group
   `group.com.samrabbit.mobile` and the keychain group for both targets.
4. On the Mac: SamRabbit > Pair iPhone… and scan the QR code with the Camera app (or in Settings > Scan).

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
| Watch link | `WatchContext` (applicationContext), `WatchRelay` (sendMessage relay) |
| Live Activity | `TaskActivityAttributes` (iOS) |

**The orb** is the desktop `OrbRenderer.swift` math, unchanged, evaluated on the CPU: `OrbRenderer.field`
renders only the surface at about one sample per point (soft gradients, bilinear upscaling), and `OrbView`
clips it to a vector circle and draws the halo as a gradient, at 30 fps in a `TimelineView` (paused with Reduce
Motion; `animated: false` for widgets). No Metal shader: it would need the separately downloaded Metal toolchain
on every Mac that builds the app.

**The app**: Home (orb header, quick actions, Needs you with inline Approve/Deny/answers, Working, Up next,
latest conversation), Chats (search, live badges, timeline with cards/images/generated UIs, live over SSE,
generated UIs open interactive in a `WKWebView` with a non-persistent store and a content rule list that only
allows the four CDNs the documents import from), Tasks (sections, Markdown thread detail, approve/deny/answer,
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
- `pending.options` for questions: strings or `{value|decision, label}` objects.
- `GET /calendar/agenda` → `{events:[{eventId?,title,startsAt,endsAt,allDay,location,meetingUrl}]}`; block/events → `{event:{…}}` (an optional `dryRun:true` is shown as "test copy").
- `POST /journal` → `{recorded,state:"sent"|"queued",date}`.
- `GET /mac/state` → the `/v1/mac/state` shape plus `name`/`online` (`computer`, `front:{app,window}`, `visible:[{app,windows}]`, `running`, `screenLocked`, `screenVision`).
- `POST /ui/generate` → `{artifactId}`; artifact status as `/v1/ui/artifacts/<id>`; `/document` is served as HTML.
- `POST /devices/child` → `{token,deviceId,…}` like `pair`.
- Errors: `{error:{code,message,retryable}}`; 401 means "pair again".

## For the Apple Watch builder

Add the two targets to `project.yml` (the placeholder comment marks the spot): `SamRabbitWatch`
(`com.samrabbit.mobile.watchkitapp`, watchOS application) and `SamRabbitWatchWidgets`
(`com.samrabbit.mobile.watchkitapp.widgets`, app-extension with `NSExtensionPointIdentifier =
com.apple.widgetkit-extension`), both depending on `- package: SamRabbitKit`, with the App Group entitlement;
then embed the watch app in `SamRabbit` (`- target: SamRabbitWatch`). On the watch:

- `WatchContext(applicationContext:)` gives hosts + a child token (the phone issues it with
  `POST /v1/mobile/devices/child` and resends it on every pairing change; `WatchContext.unpairedKey` means
  forget it). Store it as a `BridgePairing` with `BridgeAccount.shared.save(_:token:)` and use
  `BridgeAccount.shared.client()` directly.
- When a direct request fails, send `WatchRelay.Request(request).message` with `sendMessage`; the phone
  (`iOS/App/WatchLink.swift`) answers `WatchRelay.Response` (status + body, 0 = the phone could not reach the Mac
  either).
- Reuse `OrbView(mood:)`, `SamTheme`, `StatusStyle`, `SpokenSummary`, `SamRabbitActions`, `SummaryCache` and
  `MobileSummary.sample()` for complications.

## Screenshots

`~/Movies/SamRabbit-tests/wave4/ios-*.png` (each tab, thread detail, chats with a generated UI, pairing,
Home Screen and Lock Screen widgets added in the simulator, Live Activity) and `widget-renders/` (the
`-SamRabbitRenderWidgets` launch argument renders every widget face with `ImageRenderer` into the app's
Documents/renders).
