package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** Card-from-announcement mapping (T3 thread updates -> app-built GenUI live cards). */
public class T3AnnouncementCardsTest {
    private static final String THREAD = "8ce4b73c-8eb3-425e-b36a-e8cc6b94c5d6";
    private GenTestSupport.FakeClock clock;
    private GenTestSupport.FakeHost host;
    private GenCardStore store;
    private GenUiController controller;

    @Before public void setUp() {
        clock = new GenTestSupport.FakeClock();
        host = new GenTestSupport.FakeHost();
        store = GenCardStore.inMemory(clock, null);
        controller = new GenUiController(store, null, host);
        controller.onSessionStarted("voice-1");
    }

    private static JSONObject announcement(String kind, JSONObject payload) throws Exception {
        return new JSONObject().put("id", 7).put("kind", kind).put("title", "Fix login redirect")
                .put("text", "“Fix login redirect” in Web app finished.").put("payload", payload)
                .put("createdAt", "2026-10-07T20:00:00.000Z");
    }

    private static JSONObject payload(String status) throws Exception {
        return new JSONObject().put("threadId", THREAD).put("title", "Fix login redirect")
                .put("projectId", "p1").put("projectTitle", "Web app").put("status", status)
                .put("lastMessage", "Done: the redirect now keeps the return URL.");
    }

    private GenCard parse(JSONObject card) {
        GenCardParser.ParseResult result = GenCardParser.parseCard(card, clock.elapsed(), true);
        assertTrue(result.error, result.ok);
        return result.card;
    }

    @Test public void finishedBecomesACompactGreenLiveCardWithOpen() throws Exception {
        JSONObject json = T3AnnouncementCards.cardJson(announcement(T3AnnouncementCards.FINISHED, payload("done")), null);
        GenCard card = parse(json);
        assertEquals("t3-8ce4b73c-8eb3-425e-b36a-e8cc6b94c5d6", card.id);
        assertEquals("Fix login redirect", card.title);
        assertEquals("Finished · Web app", card.subtitle);
        assertEquals("T3 Code", card.eyebrow);
        assertEquals(GenCard.Size.COMPACT, card.size);
        assertEquals(GenCard.Accent.GREEN, card.accent);
        assertEquals(LiveBinding.Type.T3_THREAD, card.live.type);
        assertEquals(THREAD, card.live.threadId);
        assertEquals(1, card.actions.size());
        GenAction open = card.actions.get(0);
        assertEquals("Open", open.label);
        assertEquals(GenAction.Kind.OPEN, open.kind);
        assertEquals("t3:" + THREAD, open.arg);
        assertEquals(THREAD, T3AnnouncementCards.threadFromOpenTarget(open.arg));
        assertEquals("Done: the redirect now keeps the return URL.", card.body.get(0).text);
    }

    @Test public void approvalWithRequestIdGetsDirectApproveAndDeny() throws Exception {
        JSONObject payload = payload("needs-approval").put("requestId", "req:42").put("requestKind", "command")
                .put("detail", "Run npm test");
        GenCard card = parse(T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.NEEDS_APPROVAL, payload), null));
        assertEquals(GenCard.Size.CARD, card.size);
        assertEquals(GenCard.Accent.AMBER, card.accent);
        assertEquals("Needs your approval · Web app", card.subtitle);
        assertEquals("Run npm test", card.body.get(0).text);
        assertEquals(3, card.actions.size());
        assertEquals("Open", card.actions.get(0).label);
        GenAction deny = card.actions.get(1);
        GenAction approve = card.actions.get(2);
        assertEquals(GenAction.Kind.HOST, deny.kind);
        assertEquals(GenAction.Kind.HOST, approve.kind);
        assertEquals(GenAction.Style.PRIMARY, approve.style);
        T3AnnouncementCards.Approval decoded = T3AnnouncementCards.parseApproval(approve.arg);
        assertNotNull(decoded);
        assertTrue(decoded.accept());
        assertEquals(THREAD, decoded.threadId);
        assertEquals("req:42", decoded.requestId);
        assertEquals("decline", T3AnnouncementCards.parseApproval(deny.arg).decision);
    }

    @Test public void approvalWithoutRequestIdFallsBackToSayButtons() throws Exception {
        JSONObject payload = payload("needs-approval").put("requestId", JSONObject.NULL);
        GenCard card = parse(T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.NEEDS_APPROVAL, payload), null));
        assertEquals(3, card.actions.size());
        assertEquals(GenAction.Kind.SAY, card.actions.get(2).kind);
        assertTrue(card.actions.get(2).arg.startsWith("Approve the pending request"));
        assertTrue(card.actions.get(2).arg.contains("Fix login redirect"));
    }

    @Test public void questionGetsAnAnswerSayButton() throws Exception {
        JSONObject payload = payload("needs-input").put("requestId", "q1").put("question", "Which database?");
        GenCard card = parse(T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.NEEDS_INPUT, payload), null));
        assertEquals("Which database?", card.body.get(0).text);
        assertEquals("Answer", card.actions.get(1).label);
        assertEquals(GenAction.Kind.SAY, card.actions.get(1).kind);
    }

    @Test public void errorIsRedAndShowsTheError() throws Exception {
        JSONObject payload = payload("error").put("error", "Provider quota exceeded");
        GenCard card = parse(T3AnnouncementCards.cardJson(announcement(T3AnnouncementCards.ERROR, payload), null));
        assertEquals(GenCard.Accent.RED, card.accent);
        assertEquals("Provider quota exceeded", card.body.get(0).text);
    }

    @Test public void nullFieldsNeverReadAsTheWordNull() throws Exception {
        JSONObject payload = new JSONObject().put("threadId", THREAD).put("title", JSONObject.NULL)
                .put("projectTitle", JSONObject.NULL).put("lastMessage", JSONObject.NULL);
        JSONObject item = new JSONObject().put("kind", T3AnnouncementCards.FINISHED).put("title", JSONObject.NULL)
                .put("payload", payload);
        GenCard card = parse(T3AnnouncementCards.cardJson(item, null));
        assertEquals("T3 thread", card.title);
        assertEquals("Finished", card.subtitle);
        assertTrue(card.body.isEmpty());
    }

    @Test public void notT3OrNoThreadMeansNoCard() throws Exception {
        assertNull(T3AnnouncementCards.cardJson(announcement("calendar.reminder", payload("done")), null));
        assertNull(T3AnnouncementCards.cardJson(announcement(T3AnnouncementCards.FINISHED,
                new JSONObject().put("title", "x")), null));
        assertNull(T3AnnouncementCards.cardJson(null, null));
    }

    @Test public void existingCardForTheThreadKeepsItsId() throws Exception {
        // The model showed its own live card after t3_new_thread; the update refreshes that one.
        controller.execute(GenUiTools.SHOW_CARD, "{\"id\":\"login-fix\",\"title\":\"Login fix\","
                + "\"live\":{\"type\":\"t3-thread\",\"threadId\":\"" + THREAD + "\"}}", clock.elapsed());
        GenCard existing = controller.findLiveCard(LiveBinding.Type.T3_THREAD, THREAD);
        assertNotNull(existing);
        JSONObject json = T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.FINISHED, payload("done")), existing.id);
        GenCard shown = controller.showHostCard(json);
        assertEquals("login-fix", shown.id);
        assertEquals(1, store.stackSize());
        assertSame(shown, store.front());
        assertSame(shown, controller.findLiveCard(LiveBinding.Type.T3_THREAD, THREAD));
    }

    @Test public void hostButtonsAreAppOnlyAndSurvivePersistence() throws Exception {
        JSONObject payload = payload("needs-approval").put("requestId", "r1");
        GenCard card = controller.showHostCard(T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.NEEDS_APPROVAL, payload), null));
        // Restored from disk (trusted codec path): the host buttons and t3: target are kept.
        GenCard restored = GenCardCodec.cardFromJson(GenCardCodec.cardToJson(card, 0L), 0L, clock.elapsed());
        assertEquals(3, restored.actions.size());
        assertEquals(GenAction.Kind.HOST, restored.actions.get(2).kind);
        assertEquals("t3:" + THREAD, restored.actions.get(0).arg);
        // A model payload can never author them: host and unknown open targets are dropped.
        String model = "{\"id\":\"x\",\"title\":\"X\",\"actions\":[{\"label\":\"Approve\",\"host\":\""
                + T3AnnouncementCards.approvalAction("accept", THREAD, "r1") + "\"},"
                + "{\"label\":\"Open\",\"open\":\"t3:" + THREAD + "\"}]}";
        GenCardParser.ParseResult parsed = GenCardParser.parseShow(model, clock.elapsed());
        assertTrue(parsed.ok);
        assertTrue(parsed.card.actions.isEmpty());
    }

    @Test public void modelCannotRewriteACardWithTrustedButtons() throws Exception {
        GenCard card = controller.showHostCard(T3AnnouncementCards.cardJson(announcement(
                T3AnnouncementCards.NEEDS_APPROVAL, payload("needs-approval").put("requestId", "r1")
                        .put("detail", "rm -rf build")), null));
        String out = controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"" + card.id
                + "\",\"title\":\"Run the unit tests\",\"body\":[{\"type\":\"text\",\"text\":\"Safe\"}]}",
                clock.elapsed());
        assertTrue(out, out.startsWith("{\"ok\":false"));
        GenCard shown = controller.findLiveCard(LiveBinding.Type.T3_THREAD, THREAD);
        assertEquals("Fix login redirect", shown.title);
        assertEquals("rm -rf build", shown.body.get(0).text);
        assertEquals(GenAction.Kind.HOST, shown.actions.get(2).kind);
        // Cards without host buttons (e.g. a finished thread) stay editable.
        GenCard done = controller.showHostCard(T3AnnouncementCards.cardJson(
                announcement(T3AnnouncementCards.FINISHED, payload("done")), card.id));
        String ok = controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"" + done.id + "\",\"title\":\"Login fix\"}",
                clock.elapsed());
        assertTrue(ok, ok.startsWith("{\"ok\":true"));
    }

    @Test public void hostActionTapReachesTheHost() throws Exception {
        GenCard card = controller.showHostCard(T3AnnouncementCards.cardJson(announcement(
                T3AnnouncementCards.NEEDS_APPROVAL, payload("needs-approval").put("requestId", "r9")), null));
        controller.onAction(card, card.actions.get(2));
        assertEquals(1, host.hostActions.size());
        assertEquals(T3AnnouncementCards.approvalAction("accept", THREAD, "r9"), host.hostActions.get(0));
    }

    @Test public void approvalActionRoundTripAndRejects() {
        String action = T3AnnouncementCards.approvalAction("decline", "codex-async:31e4ac27:call_x", "req|1");
        // A '|' inside an id would be ambiguous: rejected rather than misread.
        assertNull(T3AnnouncementCards.parseApproval(action));
        T3AnnouncementCards.Approval ok = T3AnnouncementCards.parseApproval(
                T3AnnouncementCards.approvalAction("decline", "codex-async:31e4ac27:call_x", "req-1"));
        assertEquals("codex-async:31e4ac27:call_x", ok.threadId);
        assertFalse(ok.accept());
        assertNull(T3AnnouncementCards.parseApproval("t3.approval|delete|a|b"));
        assertNull(T3AnnouncementCards.parseApproval("say something"));
        assertNull(T3AnnouncementCards.threadFromOpenTarget("calendar"));
    }

    @Test public void afterDecisionKeepsIdAndOnlyOpen() {
        GenCard card = parse(T3AnnouncementCards.afterDecision("t3-abc", THREAD, "Fix login", true));
        assertEquals("t3-abc", card.id);
        assertEquals("Approved · resuming", card.subtitle);
        assertEquals(1, card.actions.size());
        assertEquals(GenAction.Kind.OPEN, card.actions.get(0).kind);
        assertEquals(LiveBinding.Type.T3_THREAD, card.live.type);
    }
}
