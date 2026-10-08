package com.resonolabs.feature.t3;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import com.resonolabs.runtime.host.T3Client;
import com.resonolabs.ui.input.UiInputIntent;
import com.resonolabs.ui.input.UiInputTarget;

import org.json.JSONObject;

/**
 * Top-level T3 tab. Owns the three T3 screens (list, thread, new thread), the data source and
 * the poll loop: 2 s while visible and something is working, 5 s otherwise, and only a slow
 * badge poll while the tab is hidden.
 */
public final class T3PageView extends FrameLayout implements UiInputTarget, AutoCloseable {
    /** What the tab needs from the product shell. */
    public interface Host {
        /** Switch to Voice and treat what the user says next as a message for this thread. */
        void talkToThread(String threadId, String title);

        /** Switch to Voice and treat what the user says next as the prompt for a new thread. */
        void talkToNewThread(String projectId, String projectTitle);

        void openSettings();

        /** Hide the product chrome while a thread or the new-thread screen is open. */
        void showChrome(boolean visible);

        /** Count of threads waiting on the user, for a badge outside the tab. */
        void needsYou(int count);

        /** Needs-you and working counts (e.g. the idle Voice page's glance chip); 0/0 when unknown. */
        default void counts(int needsYou, int working) { }
    }

    private enum Screen { LIST, THREAD, NEW }

    private static final long FAST_POLL_MS = 2_000L;
    private static final long SLOW_POLL_MS = 5_000L;
    private static final long HIDDEN_POLL_MS = 20_000L;
    private static final long SOON_MS = 700L;

    private final Activity activity;
    private final Host host;
    private final T3Repository repository;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final T3Toast toast = new T3Toast();
    private final T3ListView list;
    private final T3ThreadView thread;
    private final T3NewThreadView create;
    private final Runnable tick = this::tick;
    private Screen screen = Screen.LIST;
    private boolean visible;
    private boolean closed;
    private boolean listInFlight;
    private boolean threadInFlight;
    private long revision = -1L;
    private T3Model.Snapshot snapshot;
    private String connectionLabel = "";
    private long labelFetchedAt;
    private int lastNeedsYou = -1;
    private int lastWorking = -1;
    private int lastNeedsYouCount = -1;
    /** Last /v1/t3/status said healthState=failed (T3 Code unreachable). */
    private boolean serverDown;
    private String serverDownDetail = "";

    public T3PageView(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        this.repository = new T3Repository(activity);
        list = new T3ListView(activity, toast, listActions());
        thread = new T3ThreadView(activity, toast, threadActions());
        create = new T3NewThreadView(activity, toast, createActions());
        addView(list, match());
        addView(thread, match());
        addView(create, match());
        thread.setVisibility(GONE);
        create.setVisibility(GONE);
        setContentDescription("T3 Code");
    }

    private static LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** The tab became visible. */
    public void start() {
        if (closed) return;
        visible = true;
        repository.reprobe();
        host.showChrome(screen == Screen.LIST);
        current().requestFocus();
        current().invalidate();
        schedule(0L);
    }

    /** The tab was hidden (another tab or an overlay owns the screen). */
    public void stop() {
        visible = false;
        schedule(HIDDEN_POLL_MS);
    }

    /** True while a thread or the new-thread screen is open (the chrome is hidden). */
    public boolean detailOpen() {
        return screen != Screen.LIST;
    }

    /** Opens a thread by id (e.g. from a card or an announcement). */
    public void openThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        T3Model.Summary known = snapshot == null ? null : snapshot.find(threadId);
        openThread(known != null ? known : placeholder(threadId, "T3 thread", "", ""));
    }

    @Override public void close() {
        closed = true;
        visible = false;
        handler.removeCallbacksAndMessages(null);
        repository.close();
    }

    @Override public boolean onInput(UiInputIntent intent) {
        switch (screen) {
            case NEW -> {
                if (!create.onInput(intent) && intent == UiInputIntent.BACK) showList();
                return true;
            }
            case THREAD -> {
                if (!thread.onInput(intent) && intent == UiInputIntent.BACK) closeThread();
                return true;
            }
            default -> {
                return list.onInput(intent);
            }
        }
    }

    private android.view.View current() {
        return switch (screen) {
            case LIST -> list;
            case THREAD -> thread;
            case NEW -> create;
        };
    }

    // ---- screens ---------------------------------------------------------------------------

    private void showScreen(Screen next) {
        screen = next;
        list.setVisibility(next == Screen.LIST ? VISIBLE : GONE);
        thread.setVisibility(next == Screen.THREAD ? VISIBLE : GONE);
        create.setVisibility(next == Screen.NEW ? VISIBLE : GONE);
        if (visible) host.showChrome(next == Screen.LIST);
        current().requestFocus();
        current().invalidate();
    }

    private void showList() {
        showScreen(Screen.LIST);
        schedule(0L);
    }

    private void openThread(T3Model.Summary summary) {
        thread.open(summary);
        showScreen(Screen.THREAD);
        repository.seen(summary.id);
        schedule(0L);
    }

    private void closeThread() {
        String id = thread.threadId();
        T3Model.Summary shown = thread.summary();
        if (shown != null && shown.unread) repository.seen(id);
        list.resetFocus();
        showList();
    }

    private void openNewThread() {
        if (snapshot == null) {
            create.open(null, null);
        } else {
            create.open(snapshot.pickerProjects(), snapshot.defaultProject());
        }
        showScreen(Screen.NEW);
    }

    private static T3Model.Summary placeholder(String id, String title, String projectId, String projectTitle) {
        return new T3Model.Summary(id, projectId, projectTitle, title, T3Status.WORKING, "Working", System.currentTimeMillis(),
                0L, false, "", "Starting", -1d);
    }

    // ---- polling ---------------------------------------------------------------------------

    private void schedule(long delay) {
        if (closed) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Math.max(0L, delay));
    }

    private void tick() {
        if (closed) return;
        if (visible && screen == Screen.THREAD) pollThread();
        else pollList();
    }

    private long listCadence() {
        if (!visible) return HIDDEN_POLL_MS;
        return snapshot != null && snapshot.counts.working > 0 ? FAST_POLL_MS : SLOW_POLL_MS;
    }

    private void pollList() {
        if (listInFlight) return;
        listInFlight = true;
        repository.threads(revision, new T3Repository.SnapshotResult() {
            @Override public void changed(T3Model.Snapshot next) {
                listInFlight = false;
                applySnapshot(next);
                if (visible && screen != Screen.THREAD) schedule(listCadence());
                else if (!visible) schedule(HIDDEN_POLL_MS);
            }

            @Override public void unchanged() {
                listInFlight = false;
                if (list.mode() != T3ListView.Mode.READY && snapshot != null) applySnapshot(snapshot);
                if (visible && screen != Screen.THREAD) schedule(listCadence());
                else if (!visible) schedule(HIDDEN_POLL_MS);
            }

            @Override public void fail(T3Client.Failure failure) {
                listInFlight = false;
                handleListFailure(failure);
                if (screen != Screen.THREAD || !visible) {
                    schedule(visible ? (failure.runtimeUnavailable() ? 3_000L : SLOW_POLL_MS) : HIDDEN_POLL_MS * 3);
                }
            }
        });
    }

    private void applySnapshot(T3Model.Snapshot next) {
        if (!next.connected) {
            // Disconnected or waiting for re-pairing (the runtime answers 200 connected:false):
            // drop the old list and badge so nothing stale is shown or counted.
            revision = -1L;
            snapshot = null;
            if (lastNeedsYou != 0) {
                lastNeedsYou = 0;
                host.needsYou(0);
            }
            reportCounts(0, 0);
            refreshConnection(true);
            return;
        }
        snapshot = next;
        revision = next.revision;
        // Before its first sync the runtime lists zero threads even when T3 Code is just
        // unreachable; that is "can't reach", not "no active threads".
        if (next.threads.isEmpty() && serverDown) list.showMode(T3ListView.Mode.FAILED, serverDownDetail);
        else list.showSnapshot(next, connectionLabel, repository.fakeMode());
        if (next.counts.needsYou != lastNeedsYou) {
            lastNeedsYou = next.counts.needsYou;
            host.needsYou(lastNeedsYou);
        }
        reportCounts(next.counts.needsYou, next.counts.working);
        if (next.threads.isEmpty() || System.currentTimeMillis() - labelFetchedAt > 60_000L) {
            refreshConnection(false);
        }
    }

    private void reportCounts(int needsYou, int working) {
        if (needsYou == lastNeedsYouCount && working == lastWorking) return;
        lastNeedsYouCount = needsYou;
        lastWorking = working;
        host.counts(needsYou, working);
    }

    private void handleListFailure(T3Client.Failure failure) {
        if (failure.notConnected() || failure.routeMissing()) {
            revision = -1L;
            snapshot = null;
            if (failure.routeMissing()) list.showMode(T3ListView.Mode.UNCONFIGURED, "");
            else refreshConnection(true);
            if (lastNeedsYou != 0) {
                lastNeedsYou = 0;
                host.needsYou(0);
            }
            reportCounts(0, 0);
            return;
        }
        if (failure.runtimeUnavailable()) {
            if (list.hasThreads()) list.showStale("SamRabbit runtime unavailable · retrying");
            else list.showMode(T3ListView.Mode.RUNTIME_DOWN, "");
            return;
        }
        // T3 server unreachable / re-auth: ask the runtime why.
        refreshConnection(true);
    }

    /** Reads /v1/t3/status for the header label and, when {@code applyMode}, the empty state. */
    private void refreshConnection(boolean applyMode) {
        labelFetchedAt = System.currentTimeMillis();
        repository.status(new T3Repository.Result<T3Model.Connection>() {
            @Override public void ok(T3Model.Connection connection) {
                connectionLabel = connection.label;
                serverDown = "failed".equals(connection.healthState);
                serverDownDetail = connection.detail.isEmpty() ? connection.serverUrl : connection.detail;
                if (!applyMode) {
                    if (snapshot == null) return;
                    if (snapshot.threads.isEmpty() && serverDown) {
                        list.showMode(T3ListView.Mode.FAILED, serverDownDetail);
                    } else if (list.mode() == T3ListView.Mode.READY || list.mode() == T3ListView.Mode.FAILED) {
                        list.showSnapshot(snapshot, connectionLabel, repository.fakeMode());
                    }
                    return;
                }
                switch (connection.healthState) {
                    case "reauth" -> list.showMode(T3ListView.Mode.REAUTH, connection.detail);
                    case "failed" -> {
                        if (list.hasThreads()) list.showStale("Can't reach T3 Code · retrying");
                        else list.showMode(T3ListView.Mode.FAILED, serverDownDetail);
                    }
                    case "ready" -> {
                        if (!connection.connected) list.showMode(T3ListView.Mode.UNCONFIGURED, "");
                    }
                    default -> list.showMode(T3ListView.Mode.UNCONFIGURED, "");
                }
            }

            @Override public void fail(T3Client.Failure failure) {
                if (!applyMode) return;
                if (failure.runtimeUnavailable()) list.showMode(T3ListView.Mode.RUNTIME_DOWN, "");
                else if (failure.routeMissing() || failure.notConnected()) list.showMode(T3ListView.Mode.UNCONFIGURED, "");
                else list.showMode(T3ListView.Mode.FAILED, failure.message);
            }
        });
    }

    private void pollThread() {
        if (threadInFlight) return;
        String id = thread.threadId();
        if (id.isEmpty()) {
            showList();
            return;
        }
        threadInFlight = true;
        repository.thread(id, new T3Repository.Result<T3Model.Detail>() {
            @Override public void ok(T3Model.Detail detail) {
                threadInFlight = false;
                if (screen != Screen.THREAD || !id.equals(thread.threadId())) {
                    schedule(0L);
                    return;
                }
                thread.showDetail(detail);
                if (detail.thread.unread) repository.seen(id);
                schedule(visible ? (thread.working() ? FAST_POLL_MS : SLOW_POLL_MS) : HIDDEN_POLL_MS);
            }

            @Override public void fail(T3Client.Failure failure) {
                threadInFlight = false;
                if (screen != Screen.THREAD || !id.equals(thread.threadId())) {
                    schedule(0L);
                    return;
                }
                if (failure.routeMissing() || failure.notConnected()) {
                    showList(); // The list explains how to connect.
                    return;
                }
                if (failure.httpStatus == 404) {
                    toast.show("That thread is no longer available", T3Status.AMBER);
                    showList();
                    return;
                }
                thread.showLoadFailure();
                schedule(visible ? SLOW_POLL_MS : HIDDEN_POLL_MS);
            }
        });
    }

    // ---- actions ---------------------------------------------------------------------------

    private T3ListView.Actions listActions() {
        return new T3ListView.Actions() {
            @Override public void openThread(T3Model.Summary summary) {
                T3PageView.this.openThread(summary);
            }

            @Override public void newThread() {
                openNewThread();
            }

            @Override public void openSettings() {
                host.openSettings();
            }

            @Override public void retry() {
                revision = -1L;
                schedule(0L);
            }
        };
    }

    private T3ThreadView.Actions threadActions() {
        return new T3ThreadView.Actions() {
            @Override public void back() {
                closeThread();
            }

            @Override public void send(T3Model.Summary target, String text) {
                if (target == null) return;
                thread.addLocalMessage(text);
                repository.send(target.id, text, new T3Repository.Result<Boolean>() {
                    @Override public void ok(Boolean value) {
                        schedule(SOON_MS);
                    }

                    @Override public void fail(T3Client.Failure failure) {
                        thread.dropLocalMessages();
                        toast.show(problem("Couldn't send", failure), T3Status.RED);
                    }
                });
            }

            @Override public void talk(T3Model.Summary target) {
                if (target != null) host.talkToThread(target.id, target.title);
            }

            @Override public void stop(T3Model.Summary target) {
                if (target == null) return;
                thread.markStopped();
                toast.show("Stopping…", T3Status.RED);
                repository.interrupt(target.id, new T3Repository.Result<Boolean>() {
                    @Override public void ok(Boolean value) {
                        schedule(SOON_MS);
                    }

                    @Override public void fail(T3Client.Failure failure) {
                        toast.show(problem("Couldn't stop", failure), T3Status.RED);
                        schedule(0L);
                    }
                });
            }

            @Override public void approve(T3Model.Summary target, T3Model.Approval approval, String decision) {
                if (target == null) return;
                thread.markHandled(approval.requestId);
                repository.approve(target.id, approval.requestId, decision, new T3Repository.Result<Boolean>() {
                    @Override public void ok(Boolean value) {
                        boolean accepted = decision.startsWith("accept");
                        toast.show(accepted ? ("accept".equals(decision) ? "Approved" : "Approved for this session")
                                : "Denied", accepted ? T3Status.GREEN : T3Status.AMBER);
                        thread.markWorking(accepted ? "Continuing" : "Replanning");
                        schedule(SOON_MS);
                    }

                    @Override public void fail(T3Client.Failure failure) {
                        thread.unmarkHandled(approval.requestId);
                        toast.show(problem("Couldn't send decision", failure), T3Status.RED);
                    }
                });
            }

            @Override public void answer(T3Model.Summary target, T3Model.Input input, JSONObject answers) {
                if (target == null) return;
                thread.markHandled(input.requestId);
                repository.answer(target.id, input.requestId, answers, new T3Repository.Result<Boolean>() {
                    @Override public void ok(Boolean value) {
                        toast.show("Answer sent", T3Status.GREEN);
                        thread.markWorking("Continuing");
                        schedule(SOON_MS);
                    }

                    @Override public void fail(T3Client.Failure failure) {
                        thread.unmarkHandled(input.requestId);
                        toast.show(problem("Couldn't send answer", failure), T3Status.RED);
                    }
                });
            }
        };
    }

    private T3NewThreadView.Actions createActions() {
        return new T3NewThreadView.Actions() {
            @Override public void back() {
                showList();
            }

            @Override public void create(T3Model.Project project, String text) {
                create.creating(true, text);
                String projectId = project == null || project.id.isEmpty() ? null : project.id;
                repository.create(text, projectId, new T3Repository.Result<String>() {
                    @Override public void ok(String threadId) {
                        create.creating(false, "");
                        String title = text.replaceAll("\\s+", " ").trim();
                        if (title.length() > 60) title = title.substring(0, 59).trim() + "…";
                        T3Model.Summary started = placeholder(threadId, title,
                                projectId == null ? "" : projectId, project == null ? "" : project.title);
                        revision = -1L;
                        openThread(started);
                        thread.addLocalMessage(text);
                        toast.show("Thread started", T3Status.GREEN);
                    }

                    @Override public void fail(T3Client.Failure failure) {
                        create.creating(false, "");
                        toast.show(problem("Couldn't start thread", failure), T3Status.RED);
                    }
                });
            }

            @Override public void talk(T3Model.Project project) {
                showList();
                host.talkToNewThread(project == null ? "" : project.id, project == null ? "" : project.title);
            }
        };
    }

    private static String problem(String lead, T3Client.Failure failure) {
        if (failure.notConnected()) return lead + ": T3 isn't connected";
        if (failure.runtimeUnavailable()) return lead + ": runtime offline";
        if (!failure.message.isEmpty() && failure.message.length() <= 48) return lead + ": " + failure.message;
        return lead;
    }
}
