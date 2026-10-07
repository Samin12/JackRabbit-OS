package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class T3FakeBackendTest {
    private static final long NOW = 1_791_400_000_000L;

    @Test
    public void seedCoversEveryStatusAndPendingKind() {
        T3FakeBackend fake = new T3FakeBackend(NOW);
        T3Model.Snapshot snapshot = T3Model.Snapshot.from(fake.threads(NOW, 40));
        assertEquals(2, snapshot.counts.needsYou);
        assertEquals(2, snapshot.counts.working);
        assertEquals(1, snapshot.counts.error);
        assertTrue(snapshot.counts.done >= 3);
        assertEquals("needs-approval", snapshot.threads.get(0).status);
        T3Model.Detail approval = T3Model.Detail.from(fake.thread(NOW, "fake-deploy"));
        assertEquals(1, approval.approvals.size());
        assertEquals(3, approval.approvals.get(0).buttons().size());
        T3Model.Detail question = T3Model.Detail.from(fake.thread(NOW, "fake-layout"));
        assertEquals(3, question.inputs.get(0).questions.get(0).options.size());
        assertNull(fake.thread(NOW, "missing"));
    }

    @Test
    public void approvingResolvesTheRequestAndFinishesLater() {
        T3FakeBackend fake = new T3FakeBackend(NOW);
        long before = fake.threads(NOW, 40).optLong("revision");
        assertTrue(fake.approve(NOW, "fake-deploy", "req-remount-1", "accept"));
        assertFalse(fake.approve(NOW, "fake-deploy", "req-remount-1", "accept")); // already resolved
        T3Model.Detail working = T3Model.Detail.from(fake.thread(NOW + 1_000L, "fake-deploy"));
        assertEquals("working", working.thread.status);
        assertTrue(working.approvals.isEmpty());
        T3Model.Detail done = T3Model.Detail.from(fake.thread(NOW + 10_000L, "fake-deploy"));
        assertEquals("done", done.thread.status);
        assertTrue(done.thread.unread);
        assertNotEquals(before, fake.threads(NOW + 10_000L, 40).optLong("revision"));
    }

    @Test
    public void createSendAnswerStopAndSeen() throws Exception {
        T3FakeBackend fake = new T3FakeBackend(NOW);
        String id = fake.create(NOW, "Write a haiku about the scroll wheel please, a long title here", "p-bookedin");
        T3Model.Detail created = T3Model.Detail.from(fake.thread(NOW, id));
        assertNotNull(created);
        assertEquals("working", created.thread.status);
        assertEquals("bookedin-web", created.thread.projectTitle);
        assertTrue(created.thread.title.endsWith("…"));
        assertTrue(fake.send(NOW, "fake-rename", "Continue"));
        assertEquals("working", T3Model.Detail.from(fake.thread(NOW, "fake-rename")).thread.status);
        assertTrue(fake.answer(NOW, "fake-layout", "codex-async:4f1c:call_layout",
                new JSONObject().put("0", "Until I open them")));
        T3Model.Detail answered = T3Model.Detail.from(fake.thread(NOW, "fake-layout"));
        assertTrue(answered.inputs.isEmpty());
        assertEquals("Until I open them", answered.messages.get(answered.messages.size() - 1).text);
        assertTrue(fake.interrupt(NOW, "fake-login"));
        assertEquals("Stopped", T3Model.Detail.from(fake.thread(NOW, "fake-login")).thread.statusLabel);
        assertTrue(fake.seen("fake-stripe"));
        assertFalse(T3Model.Detail.from(fake.thread(NOW, "fake-stripe")).thread.unread);
    }

    @Test
    public void unchangedStateKeepsRevisionStable() {
        T3FakeBackend fake = new T3FakeBackend(NOW);
        long first = fake.threads(NOW, 40).optLong("revision");
        assertEquals(first, fake.threads(NOW + 30_000L, 40).optLong("revision"));
    }
}
