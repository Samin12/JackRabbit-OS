package com.resonolabs.feature.t3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug-only stand-in for the CONTRACTS §2 routes, used when the runtime does not have
 * {@code /v1/t3/*} yet and {@code debug.sam.t3.fake=1}. Stateful so every UI path (approve,
 * answer, send, stop, create) visibly does something. Produces contract-shaped JSON so the
 * real parsing code is exercised.
 */
final class T3FakeBackend {
    private static final long MINUTE = 60_000L;
    private static final String SAM = "p-samrabbit";
    private static final String WEB = "p-bookedin";
    private static final String HERMES = "p-agents";

    private final Map<String, FakeThread> threads = new LinkedHashMap<>();
    private final Map<String, String> projects = new LinkedHashMap<>();
    private long revision = 1;
    private int sequence;

    private static final class FakeThread {
        final String id;
        final String projectId;
        String title;
        String status;
        String statusLabel;
        String phase;
        double progress = -1d;
        long updatedAt;
        long completedAt;
        boolean unread;
        long finishAt;
        String reply;
        final List<JSONObject> messages = new ArrayList<>();
        final List<JSONObject> approvals = new ArrayList<>();
        final List<JSONObject> inputs = new ArrayList<>();

        FakeThread(String id, String projectId) {
            this.id = id;
            this.projectId = projectId;
        }
    }

    T3FakeBackend(long now) {
        this(now, true);
    }

    /** {@code withThreads=false} starts connected but empty (debug.sam.t3.fake=empty). */
    T3FakeBackend(long now, boolean withThreads) {
        projects.put(SAM, "SamRabbit");
        projects.put(WEB, "bookedin-web");
        projects.put(HERMES, "Agent Club");
        if (withThreads) seed(now);
    }

    synchronized JSONObject status(long now) {
        return object("connected", true, "serverUrl", "http://192.168.1.183:3773",
                "label", "Samin's Mac Studio (demo data)", "healthState", "ready", "detail", null,
                "expiresAt", iso(now + 29L * 24 * 60 * MINUTE), "lastSyncAt", iso(now - 2_000L));
    }

    synchronized JSONObject threads(long now, int limit) {
        advance(now);
        JSONArray list = new JSONArray();
        int needs = 0, working = 0, done = 0, error = 0;
        List<FakeThread> ordered = new ArrayList<>(threads.values());
        ordered.sort((a, b) -> {
            int rank = Integer.compare(T3Status.rank(a.status), T3Status.rank(b.status));
            return rank != 0 ? rank : Long.compare(b.updatedAt, a.updatedAt);
        });
        for (FakeThread thread : ordered) {
            if (T3Status.needsYou(thread.status)) needs++;
            else if (T3Status.working(thread.status)) working++;
            else if (T3Status.ERROR.equals(thread.status)) error++;
            else done++;
            if (list.length() < limit) list.put(summary(thread));
        }
        JSONArray projectList = new JSONArray();
        for (Map.Entry<String, String> project : projects.entrySet()) {
            projectList.put(object("id", project.getKey(), "title", project.getValue()));
        }
        return object("connected", true, "revision", revision, "updatedAt", iso(now),
                "counts", object("needsYou", needs, "working", working, "done", done, "error", error),
                "projects", projectList, "threads", list);
    }

    synchronized JSONObject thread(long now, String threadId) {
        advance(now);
        FakeThread thread = threads.get(threadId);
        if (thread == null) return null;
        JSONArray messages = new JSONArray();
        for (JSONObject message : thread.messages) messages.put(message);
        JSONArray approvals = new JSONArray();
        for (JSONObject approval : thread.approvals) approvals.put(approval);
        JSONArray inputs = new JSONArray();
        for (JSONObject input : thread.inputs) inputs.put(input);
        return object("thread", summary(thread), "messages", messages,
                "pending", object("approvals", approvals, "inputs", inputs),
                "activeTurnId", T3Status.working(thread.status) ? "turn-" + thread.id : null);
    }

    synchronized String create(long now, String text, String projectId) {
        String project = projectId != null && projects.containsKey(projectId) ? projectId : SAM;
        FakeThread thread = new FakeThread("fake-new-" + (++sequence), project);
        String title = text.trim().replaceAll("\\s+", " ");
        thread.title = title.length() > 42 ? title.substring(0, 41).trim() + "…" : title;
        addMessage(thread, true, text, now);
        startWork(thread, now, "Starting", 5_000L,
                "On it. I read the request and sketched a plan:\n\n1. Find the relevant files\n"
                        + "2. Make the change\n3. Run the tests\n\nI'll report back when the tests pass.");
        threads.put(thread.id, thread);
        bump();
        return thread.id;
    }

    synchronized boolean send(long now, String threadId, String text) {
        FakeThread thread = threads.get(threadId);
        if (thread == null) return false;
        addMessage(thread, true, text, now);
        String lower = text.trim().toLowerCase(java.util.Locale.ROOT);
        String reply = lower.startsWith("stop") ? "Okay — I stopped there and left everything as is."
                : lower.startsWith("looks good") ? "Great, thanks. I've committed the change on the branch."
                : "Got it: “" + text.trim() + "”. Working on that now — I'll update this thread when done.";
        startWork(thread, now, "Thinking", 4_500L, reply);
        bump();
        return true;
    }

    synchronized boolean approve(long now, String threadId, String requestId, String decision) {
        FakeThread thread = threads.get(threadId);
        if (thread == null || !thread.approvals.removeIf(item -> requestId.equals(item.optString("requestId")))) {
            return false;
        }
        boolean accepted = decision.startsWith("accept");
        startWork(thread, now, accepted ? "Running command" : "Replanning", 5_000L, accepted
                ? "Remounted /system read-write and installed the APK. Rebooting the R1 now — screenshots "
                        + "next once the runtime is back on :8765."
                : "Understood, I won't touch the device. The APK is built at "
                        + "android/app/build/outputs/apk/debug/app-debug.apk if you want to install it yourself.");
        bump();
        return true;
    }

    synchronized boolean answer(long now, String threadId, String requestId, JSONObject answers) {
        FakeThread thread = threads.get(threadId);
        if (thread == null || !thread.inputs.removeIf(item -> requestId.equals(item.optString("requestId")))) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        for (java.util.Iterator<String> keys = answers.keys(); keys.hasNext(); ) {
            Object value = answers.opt(keys.next());
            if (text.length() > 0) text.append("; ");
            if (value instanceof JSONArray array) {
                for (int i = 0; i < array.length(); i++) {
                    if (i > 0) text.append(", ");
                    text.append(array.optString(i));
                }
            } else {
                text.append(String.valueOf(value));
            }
        }
        addMessage(thread, true, text.toString(), now);
        startWork(thread, now, "Updating layout", 5_000L,
                "Thanks — going with “" + text + "”. I'll wire it into the list sectioning and post screenshots.");
        bump();
        return true;
    }

    synchronized boolean interrupt(long now, String threadId) {
        FakeThread thread = threads.get(threadId);
        if (thread == null) return false;
        thread.finishAt = 0L;
        thread.reply = null;
        thread.status = T3Status.DONE;
        thread.statusLabel = "Stopped";
        thread.phase = null;
        thread.progress = -1d;
        thread.updatedAt = now;
        thread.completedAt = now;
        bump();
        return true;
    }

    synchronized boolean seen(String threadId) {
        FakeThread thread = threads.get(threadId);
        if (thread == null) return false;
        if (thread.unread) {
            thread.unread = false;
            bump();
        }
        return true;
    }

    private void advance(long now) {
        for (FakeThread thread : threads.values()) {
            if (thread.finishAt <= 0L || thread.finishAt == Long.MAX_VALUE) continue;
            if (now < thread.finishAt) {
                double next = Math.min(0.95d, 1d - (thread.finishAt - now) / 5_000d);
                if (Math.abs(next - thread.progress) > 0.05d) {
                    thread.progress = next;
                    bump();
                }
                continue;
            }
            thread.finishAt = 0L;
            if (thread.reply != null) addMessage(thread, false, thread.reply, now);
            thread.reply = null;
            thread.status = T3Status.DONE;
            thread.statusLabel = "Done";
            thread.phase = null;
            thread.progress = -1d;
            thread.updatedAt = now;
            thread.completedAt = now;
            thread.unread = true;
            bump();
        }
    }

    private void startWork(FakeThread thread, long now, String phase, long duration, String reply) {
        thread.status = T3Status.WORKING;
        thread.statusLabel = "Working";
        thread.phase = phase;
        thread.progress = 0.05d;
        thread.updatedAt = now;
        thread.finishAt = now + duration;
        thread.reply = reply;
    }

    private void bump() {
        revision++;
    }

    private void addMessage(FakeThread thread, boolean user, String text, long at) {
        thread.messages.add(object("id", "m-" + thread.id + "-" + thread.messages.size(),
                "role", user ? "user" : "assistant", "text", text, "createdAt", iso(at), "streaming", false));
        thread.updatedAt = Math.max(thread.updatedAt, at);
    }

    private JSONObject summary(FakeThread thread) {
        return object("id", thread.id, "projectId", thread.projectId,
                "projectTitle", projects.get(thread.projectId), "title", thread.title,
                "status", thread.status, "statusLabel", thread.statusLabel,
                "updatedAt", iso(thread.updatedAt),
                "completedAt", thread.completedAt > 0 ? iso(thread.completedAt) : null,
                "unread", thread.unread, "model", "claude-opus-5-5", "phase", thread.phase,
                "progress", thread.progress >= 0 ? thread.progress : null);
    }

    private FakeThread put(String id, String projectId, String title, String status, String label,
                           String phase, long updatedAt, boolean unread) {
        FakeThread thread = new FakeThread(id, projectId);
        thread.title = title;
        thread.status = status;
        thread.statusLabel = label;
        thread.phase = phase;
        thread.updatedAt = updatedAt;
        thread.unread = unread;
        if (T3Status.DONE.equals(status) || T3Status.ERROR.equals(status)) thread.completedAt = updatedAt;
        threads.put(id, thread);
        return thread;
    }

    private void seed(long now) {
        FakeThread deploy = put("fake-deploy", SAM, "Deploy the T3 tab build to the R1",
                T3Status.NEEDS_APPROVAL, "Needs approval", "Waiting for approval", now - 2 * MINUTE, true);
        addMessage(deploy, true, "Build the feat/t3-tab branch, deploy it to the R1 and take screenshots of the "
                + "new tab.", now - 14 * MINUTE);
        addMessage(deploy, false, "Building with the toolchain script first.\n\n```sh\nJR_REPO=~/jackrabbit-src/wt/t3-ui "
                + "GRADLE_TASKS=\":app:assembleDebug\" ~/jr-toolchain/build.sh -q\n```\n\nBuild passed in 1m 52s "
                + "with no warnings in **:feature:t3**.", now - 9 * MINUTE);
        addMessage(deploy, false, "To install it I need to remount `/system` read-write on the device and reboot. "
                + "This interrupts any voice session that is running.", now - 2 * MINUTE);
        deploy.approvals.add(object("requestId", "req-remount-1", "kind", "command",
                "detail", "adb shell 'blockdev --setrw /dev/block/dm-1; mount -o remount,rw / && "
                        + "cp /data/local/tmp/SamVoice.apk /system/priv-app/SamVoice/ && reboot'",
                "options", new JSONArray()
                        .put(object("decision", "accept", "label", "Approve"))
                        .put(object("decision", "acceptForSession", "label", "Always allow this session"))
                        .put(object("decision", "decline", "label", "Decline"))
                        .put(object("decision", "cancel", "label", "Cancel"))));

        FakeThread layout = put("fake-layout", SAM, "Pick the layout for finished threads",
                T3Status.NEEDS_INPUT, "Has a question", null, now - 6 * MINUTE, true);
        addMessage(layout, true, "Make the T3 list feel like the phone app but tuned for the wheel.", now - 31 * MINUTE);
        addMessage(layout, false, "I grouped threads into Needs you, Working and Recent, with a status orb on each "
                + "row. One decision before I finish the Recent section.", now - 6 * MINUTE);
        layout.inputs.add(object("requestId", "codex-async:4f1c:call_layout", "questions", new JSONArray()
                .put(object("id", "0", "header", "Recent section",
                        "question", "How long should finished threads stay in Recent?",
                        "options", new JSONArray().put("Until I open them").put("For 24 hours").put("Last 5 only"),
                        "allowCustom", true, "multiSelect", false))));

        FakeThread login = put("fake-login", WEB, "Fix the login redirect loop on Safari",
                T3Status.WORKING, "Working", "Running tests", now - MINUTE / 2, false);
        login.progress = 0.62d;
        addMessage(login, true, "Users on Safari get bounced between /login and /app forever after the cookie "
                + "change. Find out why and fix it.", now - 22 * MINUTE);
        addMessage(login, false, "Found it: the new `SameSite=Strict` session cookie is dropped on the cross-site "
                + "redirect back from the OAuth provider, so `/app` sees no session and sends you to `/login` again.\n\n"
                + "Switching the session cookie to `SameSite=Lax` and adding a regression test for the callback.",
                now - 4 * MINUTE);
        login.finishAt = Long.MAX_VALUE; // Keeps "working" until the user interacts.

        FakeThread journal = put("fake-journal", HERMES, "Summarize this week's Heptabase journal",
                T3Status.WORKING, "Working", "Reading notes", now - 3 * MINUTE, false);
        addMessage(journal, true, "Give me a short summary of what I worked on this week.", now - 5 * MINUTE);
        journal.finishAt = Long.MAX_VALUE;

        FakeThread migrate = put("fake-migrate", SAM, "Migrate the settings table to v43",
                T3Status.ERROR, "Failed", null, now - 48 * MINUTE, true);
        addMessage(migrate, true, "Add migration 43 for the T3 tables.", now - 70 * MINUTE);
        addMessage(migrate, false, "The migration runner rejected v043: `sqlite3.OperationalError: table connections "
                + "already exists`. I left the database untouched. Want me to rebuild the table with the v030 "
                + "pattern instead?", now - 48 * MINUTE);

        FakeThread stripe = put("fake-stripe", WEB, "Add retries to the Stripe webhook",
                T3Status.DONE, "Done", null, now - 12 * MINUTE, true);
        addMessage(stripe, true, "Stripe webhooks sometimes time out and the order never gets fulfilled. Add retries.",
                now - 95 * MINUTE);
        addMessage(stripe, false, "Here's what I found before changing anything.\n\n"
                + "## Why orders were lost\nThe webhook handler did the fulfilment inline. When the database was slow "
                + "the request took longer than Stripe's 10 second timeout, Stripe marked the delivery failed, and "
                + "our handler had already thrown away the event when the retry came in because the idempotency "
                + "key was written *before* fulfilment succeeded.\n\n"
                + "## Options\n- Move fulfilment to a queue and acknowledge immediately\n"
                + "- Keep it inline but retry with backoff and write the idempotency key last\n"
                + "- Both, with a dead-letter table for events that still fail\n\n"
                + "I'd go with the second option now (small change) and the queue later. Proceeding with that.\n\n"
                + "The handler lives in `apps/web/src/webhooks/stripe.ts` and the fulfilment code in "
                + "`apps/web/src/orders/fulfil.ts`. Both have decent test coverage, so the risk is low.\n\n"
                + "I'll also add structured logs around each attempt so we can see retries in the dashboard.\n\n"
                + "Starting now.", now - 80 * MINUTE);
        addMessage(stripe, false, "Done. Webhook deliveries now retry with exponential backoff instead of failing the "
                + "checkout.\n\n## What changed\n"
                + "- **`webhooks/stripe.ts`** wraps the handler in `withRetry()` (5 attempts, 0.5s × 2ⁿ, capped at 30s)\n"
                + "- Idempotency: events are keyed by `event.id` so a retry never double-fulfils an order\n"
                + "- Added a dead-letter table for events that still fail\n\n"
                + "```ts\nexport async function handleStripe(req: Request) {\n  const event = verify(req);\n"
                + "  return withRetry(() => fulfil(event), {\n    attempts: 5,\n    baseMs: 500,\n"
                + "    maxMs: 30_000,\n  });\n}\n```\n\n"
                + "## Tests\n| Suite | Result |\n|---|---|\n| webhooks | 14 passed |\n| checkout | 32 passed |\n\n"
                + "Next I'd suggest alerting on the dead-letter table — want me to add that?", now - 12 * MINUTE);

        FakeThread rename = put("fake-rename", SAM, "Rename the project to SamRabbit",
                T3Status.DONE, "Done", null, now - 5 * 60 * MINUTE, false);
        addMessage(rename, true, "Rename everything from JackRabbit to SamRabbit.", now - 6 * 60 * MINUTE);
        addMessage(rename, false, "Renamed the package, theme class and headers. Boundary checks pass.",
                now - 5 * 60 * MINUTE);

        FakeThread wifi = put("fake-wifi", SAM, "Investigate the Wi-Fi drop after the R1 sleeps",
                T3Status.DONE, "Done", null, now - 26 * 60 * MINUTE, false);
        addMessage(wifi, true, "Why does Wi-Fi drop when the R1 wakes up?", now - 27 * 60 * MINUTE);
        addMessage(wifi, false, "The driver powers down in deep sleep and the supplicant takes ~4s to reassociate. "
                + "Nothing to fix in SamRabbit; the runtime already retries.", now - 26 * 60 * MINUTE);

        FakeThread readme = put("fake-readme", HERMES, "Clean up the README badges",
                T3Status.DONE, "Done", null, now - 3 * 24 * 60 * MINUTE, false);
        addMessage(readme, true, "Remove the broken badges from the README.", now - 3 * 24 * 60 * MINUTE - MINUTE);
        addMessage(readme, false, "Removed 3 dead badges and fixed the CI badge URL.", now - 3 * 24 * 60 * MINUTE);
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                value.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            }
        } catch (Exception ignored) {
            // Keys are literals; values are JSON-compatible.
        }
        return value;
    }
}
