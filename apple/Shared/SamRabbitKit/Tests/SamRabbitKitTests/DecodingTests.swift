import Foundation
import Testing
@testable import SamRabbitKit

@Suite("Decoding the mobile API")
struct DecodingTests {
    static let summaryJSON = """
    {"generatedAt":"2026-10-08T18:44:19Z",
     "mac":{"name":"Samin's MacBook Pro","online":true,"screenLocked":false},
     "r1":{"lastSeenAt":1791484999000,"live":true,"liveConversationId":"c_focus0001","liveTitle":"Weekly focus review"},
     "t3":{"available":true,"needsYou":2,"working":1,"threads":[
        {"threadId":"t1","title":"Deploy","project":"Assistant","status":"needs_approval","updatedAt":"2026-10-08T18:42:18.250Z","summary":"Waiting"},
        {"threadId":"t2","title":"Fix login","project":"Website","status":"needs-input","updatedAt":"2026-10-08T18:39:18Z"},
        {"threadId":"t3","title":"Report","project":"Assistant","status":"working","updatedAt":"2026-10-08T18:43:38+00:00"},
        {"threadId":"t4","title":"Mystery","status":"teleporting"},
        {"title":"no id, dropped"}]},
     "calendar":{"available":true,"next":[
        {"title":"Standup","startsAt":"2026-10-08T19:05:00Z","endsAt":"2026-10-08T19:20:00Z","allDay":false,"location":null,"meetingUrl":"https://meet.google.com/abc"},
        {"title":"All day thing","startsAt":"2026-10-09","endsAt":"2026-10-10","allDay":true}]},
     "latestConversation":{"conversationId":"c_focus0001","title":"Weekly focus review","lastAt":1791484900000,"preview":"Done"},
     "journal":{"available":true},
     "somethingNew":{"x":1}}
    """

    @Test func summaryDecodesEveryField() throws {
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data(Self.summaryJSON.utf8))
        #expect(summary.generatedAt == BridgeDates.date(from: "2026-10-08T18:44:19Z"))
        #expect(summary.mac.name == "Samin's MacBook Pro")
        #expect(summary.mac.online)
        #expect(summary.mac.screenLocked == false)
        #expect(summary.r1.live)
        #expect(summary.r1.liveConversationId == "c_focus0001")
        #expect(summary.r1.lastSeenAt == Date(timeIntervalSince1970: 1_791_484_999))
        #expect(summary.t3.available)
        #expect(summary.t3.needsYou == 2)
        #expect(summary.t3.working == 1)
        #expect(summary.t3.threads.count == 4) // the id-less one is dropped, not fatal
        #expect(summary.t3.threads[0].status == .needsApproval)
        #expect(summary.t3.threads[0].projectName == "Assistant")
        #expect(summary.t3.threads[1].status == .needsInput)
        #expect(summary.t3.threads[3].status == .unknown)
        #expect(summary.t3.threads[0].updatedAt != nil)
        #expect(summary.t3.threads[2].updatedAt == BridgeDates.date(from: "2026-10-08T18:43:38Z"))
        #expect(summary.calendar.next.count == 2)
        #expect(summary.calendar.next[0].meetingUrl?.host == "meet.google.com")
        #expect(summary.calendar.next[1].allDay)
        #expect(summary.calendar.next[1].startsAt != nil)
        #expect(summary.latestConversation?.conversationId == "c_focus0001")
        #expect(summary.journal.available)
    }

    @Test func summaryRoundTripsThroughTheCache() throws {
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data(Self.summaryJSON.utf8))
        let cache = SummaryCache(container: .temporary())
        cache.save(summary, at: Date(timeIntervalSince1970: 1_800_000_000))
        let entry = try #require(cache.load())
        #expect(entry.summary == summary)
        #expect(entry.savedAt == Date(timeIntervalSince1970: 1_800_000_000))
    }

    @Test func emptySummaryUsesDefaults() throws {
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data("{}".utf8))
        #expect(summary.t3.threads.isEmpty)
        #expect(!summary.r1.live)
        #expect(summary.latestConversation == nil)
        #expect(OrbMood.from(summary) == .idle)
        #expect(OrbMood.from(nil) == .offline)
    }

    @Test func pendingOptionsAcceptStringsAndObjects() throws {
        let json = """
        {"threads":[
          {"threadId":"a","title":"A","projectId":"p","projectName":"Web","status":"needs_input",
           "pending":{"kind":"question","text":"Where?","options":["/home","/pricing"]}},
          {"threadId":"b","title":"B","status":"needs_approval",
           "pending":{"kind":"approval","text":"Run it?","options":[{"decision":"approve","label":"Approve"},{"value":"deny","label":"Deny"}]}},
          {"threadId":"c","title":"C","status":"working","pending":null}]}
        """
        let list = try BridgeJSON.decode(ThreadList.self, from: Data(json.utf8))
        #expect(list.threads.count == 3)
        #expect(list.threads[0].pending?.kind == .question)
        #expect(list.threads[0].pending?.options.map(\.value) == ["/home", "/pricing"])
        #expect(list.threads[1].pending?.kind == .approval)
        #expect(list.threads[1].pending?.options.map(\.value) == ["approve", "deny"])
        #expect(list.threads[1].pending?.options.map(\.label) == ["Approve", "Deny"])
        #expect(list.threads[2].pending == nil)
        #expect(list.threads[0].pending?.requestId == nil)
        #expect(list.threads[0].pending?.canRespond == false)
    }

    /// The real bridge's `pending_view`: the request and question ids decode and survive the cache.
    @Test func pendingCarriesTheRequestIds() throws {
        let json = """
        {"threads":[
          {"threadId":"a","title":"A","status":"needs_approval",
           "pending":{"kind":"approval","requestId":"req-a1","requestKind":"command","text":"Run it?",
                      "options":[{"decision":"accept","label":"Yes"},{"decision":"decline","label":"No"}]}},
          {"threadId":"b","title":"B","status":"needs_input",
           "pending":{"kind":"question","requestId":"req-b1","header":"Page","text":"Where?","options":["/home"],
                      "allowCustom":true,"multiSelect":false,
                      "questions":[{"id":"q-1","header":"Page","text":"Where?","options":["/home"]}]}},
          {"threadId":"c","title":"C","status":"needs_input",
           "pending":{"kind":"question","requestId":"req-c1","questionId":"q-9","text":"Why?"}}]}
        """
        let list = try BridgeJSON.decode(ThreadList.self, from: Data(json.utf8))
        #expect(list.threads.map { $0.pending?.requestId } == ["req-a1", "req-b1", "req-c1"])
        #expect(list.threads.map { $0.pending?.questionId } == [nil, "q-1", "q-9"])
        #expect(list.threads.allSatisfy { $0.pending?.canRespond == true })
        let summary = MobileSummary(t3: TaskOverview(available: true, needsYou: 3, threads: list.threads))
        let cache = SummaryCache(container: .temporary())
        cache.save(summary)
        #expect(cache.load()?.summary.t3.threads.map { $0.pending?.requestId } == ["req-a1", "req-b1", "req-c1"])
        #expect(cache.load()?.summary.t3.threads[1].pending?.questionId == "q-1")
    }

    /// One notification per T3 request: the same request is never announced twice, a new one is.
    @Test func needsYouIsAnnouncedOncePerRequest() {
        func thread(_ requestId: String, _ text: String) -> TaskThread {
            TaskThread(threadId: "t", title: "Deploy", status: .needsApproval,
                       pending: PendingAction(kind: .approval, text: text, requestId: requestId))
        }
        let first = AlertPlanner.plan(threads: [thread("r1", "Run deploy")], announced: [:], tracked: [])
        #expect(first.alerts.count == 1)
        let again = AlertPlanner.plan(threads: [thread("r1", "Run deploy (edited)")], announced: first.announced, tracked: [])
        #expect(again.alerts.isEmpty)
        let next = AlertPlanner.plan(threads: [thread("r2", "Run deploy")], announced: again.announced, tracked: [])
        #expect(next.alerts.count == 1)
    }

    @Test func threadDetailDecodes() throws {
        let json = """
        {"thread":{"threadId":"t","title":"Fix","status":"needs_input","projectName":"Web"},
         "messages":[{"role":"user","text":"Fix it","at":"2026-10-08T18:00:00Z"},
                     {"role":"assistant","text":"**Done**","at":1791480000000},
                     {"role":"tool","text":"ran tests"}],
         "pending":{"kind":"question","text":"Which page?","options":["/home"]}}
        """
        let detail = try BridgeJSON.decode(ThreadDetail.self, from: Data(json.utf8))
        #expect(detail.messages.map(\.role) == [.user, .assistant, .tool])
        #expect(detail.messages[1].at == Date(timeIntervalSince1970: 1_791_480_000))
        #expect(detail.pending?.text == "Which page?")
    }

    @Test(arguments: [
        ("needs_approval", ThreadStatus.needsApproval), ("needs-approval", .needsApproval),
        ("needs_input", .needsInput), ("working", .working), ("done", .done), ("error", .error),
        ("idle", .idle), ("whatever", .unknown),
    ])
    func statusSpellings(raw: String, expected: ThreadStatus) {
        #expect(ThreadStatus(raw: raw) == expected)
    }

    @Test func datesAcceptIsoAndEpochForms() {
        let reference = Date(timeIntervalSince1970: 1_791_484_000)
        #expect(BridgeDates.date(from: "2026-10-08T18:26:40Z") == reference)
        #expect(BridgeDates.date(from: "2026-10-08T18:26:40.000Z") == reference)
        #expect(BridgeDates.date(from: "2026-10-08T14:26:40-04:00") == reference)
        #expect(BridgeDates.date(from: "2026-10-08T18:26:40+0000") == reference)
        #expect(BridgeDates.date(fromNumber: 1_791_484_000_000) == reference)
        #expect(BridgeDates.date(fromNumber: 1_791_484_000) == reference)
        #expect(BridgeDates.date(from: "2026-10-08") != nil)
        #expect(BridgeDates.date(from: "next tuesday") == nil)
        #expect(JSONValue.number(1_791_484_000_000).date == reference)
    }

    @Test func conversationsAndEventsDecode() throws {
        let page = try BridgeJSON.decode(ConversationPage.self, from: Data("""
        {"conversations":[{"conversationId":"c_abc","title":"Hi","startedAt":1791480000000,"lastAt":1791480100000,
          "endedAt":null,"live":true,"messageCount":3,"preview":"Hello","device":"r1","cursor":12}],
         "cursor":40,"nextBefore":1791480100000}
        """.utf8))
        #expect(page.conversations.first?.live == true)
        #expect(page.conversations.first?.messageCount == 3)
        #expect(page.cursor == 40)
        #expect(page.nextBefore == 1_791_480_100_000)

        let events = try BridgeJSON.decode(EventPage.self, from: Data("""
        {"events":[{"id":"c_abc:000001","type":"message.user","conversationId":"c_abc","at":1791480000000,"text":"Hi","cursor":5},
                   {"type":"message.assistant.done","id":"c_abc:000002","at":1791480001000,"text":"Hello","cursor":6}],
         "cursor":6,"more":false}
        """.utf8))
        #expect(events.events.count == 2)
        #expect(events.events[0].type == "message.user")
        #expect(events.events[1].conversationId == "c_abc") // from the id prefix
        #expect(events.events[1].cursor == 6)
    }

    @Test func macStateAndArtifactDecode() throws {
        let mac = try BridgeJSON.decode(MacState.self, from: Data("""
        {"computer":"Studio","front":{"app":"T3 Code","window":"Fix"},"visible":[{"app":"Chrome","windows":["Cal"]}],
         "running":["Finder","Chrome"],"screenVision":true,"screenLocked":false}
        """.utf8))
        #expect(mac.name == "Studio")
        #expect(mac.front?.app == "T3 Code")
        #expect(mac.visible.first?.windows == ["Cal"])
        #expect(mac.running.count == 2)

        let artifact = try BridgeJSON.decode(GeneratedArtifact.self, from: Data("""
        {"artifactId":"ui_0123456789abcdef01234567","status":"ready","title":"Chart","summary":"s",
         "imageBlobId":"sha256:ab","width":960,"height":620}
        """.utf8))
        #expect(artifact.status == .ready)
        #expect(artifact.width == 960)
    }

    @Test func errorEnvelopeMapsToBridgeError() {
        let locked = BridgeError.from(status: 409, data: Data(#"{"error":{"code":"screen_locked","message":"Locked","retryable":false}}"#.utf8))
        #expect(locked.code == "screen_locked")
        #expect(locked.shortDescription == "Screen locked")
        #expect(BridgeError.from(status: 401, data: Data("{}".utf8)) == .unauthorized)
        #expect(BridgeError.from(status: 503, data: Data("oops".utf8)).isRetryable)
    }

    @Test func spokenSummaryReadsNaturally() throws {
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data(Self.summaryJSON.utf8))
        let now = try #require(BridgeDates.date(from: "2026-10-08T18:45:00Z"))
        let text = SpokenSummary.whatNeedsMe(summary, now: now)
        #expect(text.hasPrefix("Two tasks need you: approve “Deploy” and answer “Fix login”."))
        #expect(text.contains("One is working."))
        #expect(text.contains("Next: Standup in 20 min."))
        #expect(SpokenSummary.whatNeedsMe(MobileSummary()).hasPrefix("Nothing needs you"))
    }
}
