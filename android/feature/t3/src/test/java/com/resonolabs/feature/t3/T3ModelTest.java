package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public final class T3ModelTest {
    private static final String SNAPSHOT = "{\"connected\":true,\"revision\":7,\"updatedAt\":\"2026-10-07T18:00:00Z\","
            + "\"counts\":{\"needsYou\":1,\"working\":1,\"done\":1,\"error\":0},"
            + "\"projects\":[{\"id\":\"p1\",\"title\":\"Agents\"},{\"id\":\"p2\",\"title\":\"Site\"}],"
            + "\"threads\":["
            + "{\"id\":\"t1\",\"projectId\":\"p2\",\"projectTitle\":\"Site\",\"title\":\"Fix login\",\"status\":\"needs-approval\","
            + "\"statusLabel\":\"Needs approval\",\"updatedAt\":\"2026-10-07T17:58:00Z\",\"completedAt\":null,\"unread\":true,"
            + "\"model\":null,\"phase\":null,\"progress\":null},"
            + "{\"id\":\"t2\",\"projectId\":\"p1\",\"projectTitle\":\"Agents\",\"title\":\"\",\"status\":\"working\","
            + "\"statusLabel\":\"Working\",\"updatedAt\":\"2026-10-07T17:59:30Z\",\"phase\":\"Running tests\",\"progress\":40},"
            + "{\"projectId\":\"p1\",\"title\":\"no id is skipped\"},"
            + "{\"id\":\"t3\",\"projectId\":\"p3\",\"projectTitle\":\"Orphan\",\"title\":\"Old\",\"status\":\"done\","
            + "\"updatedAt\":\"2026-10-01T10:00:00Z\",\"unread\":false}]}";

    @Test
    public void snapshotParsesLeniently() throws Exception {
        T3Model.Snapshot snapshot = T3Model.Snapshot.from(new JSONObject(SNAPSHOT));
        assertTrue(snapshot.connected);
        assertEquals(7L, snapshot.revision);
        assertEquals(3, snapshot.threads.size());
        T3Model.Summary first = snapshot.threads.get(0);
        assertEquals("Fix login", first.title);
        assertEquals("", first.phase); // JSON null is not the string "null"
        assertEquals("", first.model);
        assertTrue(first.progress < 0d);
        assertEquals(0L, first.completedAt);
        T3Model.Summary second = snapshot.threads.get(1);
        assertEquals("Untitled thread", second.title);
        assertEquals(0.4d, second.progress, 1e-9); // percentages are normalized
        assertEquals(1, snapshot.counts.needsYou);
    }

    @Test
    public void defaultProjectIsTheMostRecentlyActive() throws Exception {
        T3Model.Snapshot snapshot = T3Model.Snapshot.from(new JSONObject(SNAPSHOT));
        assertEquals("p1", snapshot.defaultProject().id); // t2 updated last
        List<T3Model.Project> picker = snapshot.pickerProjects();
        assertEquals("p1", picker.get(0).id);
        assertEquals("p2", picker.get(1).id);
        assertEquals("p3", picker.get(2).id); // only seen on a thread
        assertEquals("Orphan", picker.get(2).title);
        assertEquals(3, picker.size());
    }

    @Test
    public void countsAreDerivedWhenMissing() throws Exception {
        T3Model.Snapshot snapshot = T3Model.Snapshot.from(new JSONObject(SNAPSHOT.replace(
                "\"counts\":{\"needsYou\":1,\"working\":1,\"done\":1,\"error\":0},", "")));
        assertEquals(1, snapshot.counts.needsYou);
        assertEquals(1, snapshot.counts.working);
        assertEquals(1, snapshot.counts.done);
    }

    private static final String DETAIL = "{\"thread\":{\"id\":\"t1\",\"title\":\"Fix login\",\"status\":\"needs-input\"},"
            + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"text\":\"hi\",\"createdAt\":\"2026-10-07T17:00:00Z\"},"
            + "{\"id\":\"r1\",\"role\":\"system\",\"text\":\"reasoning\"},"
            + "{\"id\":\"m2\",\"role\":\"assistant\",\"text\":\"\",\"streaming\":true},"
            + "{\"id\":\"m3\",\"role\":\"assistant\",\"text\":\"  \",\"streaming\":false}],"
            + "\"pending\":{\"approvals\":[{\"requestId\":\"a1\",\"kind\":\"command\",\"detail\":\"rm -rf build\","
            + "\"options\":[{\"decision\":\"accept\",\"label\":\"Approve\"},{\"decision\":\"decline\",\"label\":\"Decline\"}]}],"
            + "\"inputs\":[{\"requestId\":\"codex-async:1:call\",\"questions\":[{\"id\":\"0\",\"header\":\"Layout\","
            + "\"question\":\"Which?\",\"options\":[\"A\",{\"label\":\"B\"},\"\"],\"allowCustom\":false,\"multiSelect\":true}]},"
            + "{\"requestId\":\"empty\",\"questions\":[]}]},\"activeTurnId\":null}";

    @Test
    public void detailKeepsOnlyUserAndAssistantMessages() throws Exception {
        T3Model.Detail detail = T3Model.Detail.from(new JSONObject(DETAIL));
        assertEquals(2, detail.messages.size());
        assertTrue(detail.messages.get(0).user);
        assertTrue(detail.messages.get(1).streaming); // empty but streaming stays (typing dots)
        assertEquals("", detail.activeTurnId);
        assertEquals(1, detail.approvals.size());
        assertEquals("Run this command?", detail.approvals.get(0).title());
        assertEquals(1, detail.inputs.size()); // input without questions is dropped
        T3Model.Question question = detail.inputs.get(0).questions.get(0);
        assertEquals(List.of("A", "B"), question.options);
        assertFalse(question.allowCustom);
        assertTrue(question.multiSelect);
        assertNull(T3Model.Detail.from(new JSONObject("{\"messages\":[]}")));
    }

    @Test
    public void approvalButtonsOnlyOfferAlwaysWhenListed() throws Exception {
        T3Model.Detail detail = T3Model.Detail.from(new JSONObject(DETAIL));
        List<T3Model.Option> buttons = detail.approvals.get(0).buttons();
        assertEquals(2, buttons.size());
        assertEquals("decline", buttons.get(0).decision);
        assertEquals("Deny", buttons.get(0).label);
        assertEquals("accept", buttons.get(1).decision);

        T3Model.Approval offered = new T3Model.Approval("r", "file-change", "", List.of(
                new T3Model.Option("accept", "Approve"), new T3Model.Option("acceptForSession", "Always allow"),
                new T3Model.Option("decline", "Decline"), new T3Model.Option("cancel", "Cancel")));
        List<T3Model.Option> three = offered.buttons();
        assertEquals(3, three.size());
        assertEquals("acceptForSession", three.get(1).decision);
        assertEquals("Always", three.get(1).label);

        T3Model.Approval unlisted = new T3Model.Approval("r", "permission", "", List.of());
        assertEquals(2, unlisted.buttons().size()); // Deny + Approve defaults
    }

    @Test
    public void fingerprintChangesWithContentOnly() throws Exception {
        T3Model.Detail a = T3Model.Detail.from(new JSONObject(DETAIL));
        T3Model.Detail b = T3Model.Detail.from(new JSONObject(DETAIL));
        T3Model.Detail c = T3Model.Detail.from(new JSONObject(DETAIL.replace("\"text\":\"hi\"", "\"text\":\"hey\"")));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), c.fingerprint());
    }

    @Test
    public void nullApprovalDetailAndQuestionAreEmptyNotTheWordNull() throws Exception {
        // The runtime sends "detail": null when T3 gave no detail (PendingApproval.detail is optional).
        T3Model.Detail detail = T3Model.Detail.from(new JSONObject("{\"thread\":{\"id\":\"t1\"},"
                + "\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"text\":null,\"streaming\":true}],"
                + "\"pending\":{\"approvals\":[{\"requestId\":\"a1\",\"kind\":\"command\",\"detail\":null,\"options\":[]}],"
                + "\"inputs\":[{\"requestId\":\"i1\",\"questions\":[{\"id\":\"q\",\"header\":null,\"question\":null,"
                + "\"options\":[\"A\"]}]}]}}"));
        assertEquals("", detail.approvals.get(0).detail);
        assertEquals("", detail.inputs.get(0).questions.get(0).question);
        assertEquals("", detail.inputs.get(0).questions.get(0).header);
        assertEquals("", detail.messages.get(0).text);
    }

    @Test
    public void connectionDefaultsToUnconfigured() throws Exception {
        T3Model.Connection connection = T3Model.Connection.from(new JSONObject("{\"connected\":false,\"label\":null}"));
        assertFalse(connection.connected);
        assertEquals("unconfigured", connection.healthState);
        assertEquals("", connection.label);
    }
}
