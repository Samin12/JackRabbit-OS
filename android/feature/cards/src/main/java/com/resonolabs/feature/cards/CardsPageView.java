package com.resonolabs.feature.cards;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;

import com.resonolabs.feature.calendar.CalendarPageView;
import com.resonolabs.feature.cards.board.AgendaWidget;
import com.resonolabs.feature.cards.board.BoardFixtures;
import com.resonolabs.feature.cards.board.BoardHost;
import com.resonolabs.feature.cards.board.BoardWidget;
import com.resonolabs.feature.cards.board.ClockWidget;
import com.resonolabs.feature.cards.board.CreationIdentity;
import com.resonolabs.feature.cards.board.CreationsWidget;
import com.resonolabs.feature.cards.board.TasksWidget;
import com.resonolabs.feature.cards.board.WidgetBoardView;
import com.resonolabs.feature.tasks.TaskPageView;
import com.resonolabs.runtime.host.CreationCatalogClient;
import com.resonolabs.ui.input.UiInputIntent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cards tab: a glanceable widget board (clock, Up next, Tasks, Creations). Full Calendar and
 * Tasks pages and Creations open on top of it with the CardsBackButton and the chrome hidden,
 * exactly as the old deck did.
 */
public final class CardsPageView extends FrameLayout implements AutoCloseable {
    private final Activity activity;
    private final Runnable openVoice;
    private final java.util.function.Consumer<Boolean> creationVisibility;
    private final CreationCatalogClient client = new CreationCatalogClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WidgetBoardView board;
    private final AgendaWidget agenda;
    private final TasksWidget tasksWidget;
    private final CreationsWidget creations;
    private final CardsBackButton back;
    private final Host host = new Host();
    private CreationWebViewHost creation;
    private CreationIdentity openCreation;
    private CalendarPageView calendar;
    private TaskPageView tasks;
    private int generation = -1;
    private JSONObject lastCatalog = new JSONObject();
    private BoardFixtures.Mode fixtureMode = BoardFixtures.Mode.OFF;
    private boolean closed;

    public CardsPageView(Activity activity, Runnable openVoice,
                         java.util.function.Consumer<Boolean> creationVisibility) {
        super(activity);
        this.activity = activity;
        this.openVoice = openVoice;
        this.creationVisibility = creationVisibility;
        board = new WidgetBoardView(activity);
        agenda = new AgendaWidget(host);
        tasksWidget = new TasksWidget(host);
        creations = new CreationsWidget(host);
        List<BoardWidget> widgets = new ArrayList<>();
        widgets.add(new ClockWidget(host));
        // TODO(wave2:LiveWidget): widgets.add(new LiveWidget(host)) — GenUI pinned/live cards from
        //   GenCardStore.get(ctx).liveAndPinned(), each drawn with GenCardRenderer; tap -> host.openLiveCard(id).
        widgets.add(agenda);
        // TODO(wave2:T3Widget): widgets.add(new T3Widget(host)) — "needs you" / "working" T3 threads from
        //   GET /v1/t3/threads (use `revision` for cheap change detection, ~5 s cadence); tap -> host.openT3Thread(id).
        widgets.add(tasksWidget);
        widgets.add(creations);
        board.setWidgets(widgets);
        addView(board, match());
        back = new CardsBackButton(activity, this::navigateBack);
        back.setVisibility(GONE);
        addView(back, new LayoutParams(58, 82));
    }

    public void start() {
        BoardFixtures.Mode mode = BoardFixtures.mode(activity);
        if (mode != fixtureMode) {
            fixtureMode = mode;
            creations.setCatalog(lastCatalog);
        }
        board.start();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        if (calendar != null) calendar.start();
        if (tasks != null) tasks.start();
    }

    public void stop() {
        handler.removeCallbacks(refresh);
        board.stop();
        if (calendar != null) calendar.stop();
        if (tasks != null) tasks.stop();
    }

    public boolean onInput(UiInputIntent input) {
        if (input == UiInputIntent.BACK) { navigateBack(); return true; }
        if (calendar != null) { boolean handled=calendar.onInput(input);if(input==UiInputIntent.BACK&&!handled)closeCalendar();return true; }
        if (tasks != null) { boolean handled=tasks.onInput(input);if(input==UiInputIntent.BACK&&!handled)closeTasks();return true; }
        if (creation != null) return creation.onInput(input);
        board.onInput(input);
        return true;
    }

    private void navigateBack() {
        if (calendar != null) { if (!calendar.onInput(UiInputIntent.BACK)) closeCalendar(); return; }
        if (tasks != null) { if (!tasks.onInput(UiInputIntent.BACK)) closeTasks(); return; }
        if (creation != null) { closeCreation(); return; }
        openVoice.run();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            client.load(activity, new CreationCatalogClient.Callback() {
                @Override public void onCatalog(JSONObject catalog) {
                    int next = catalog.optInt("generation", -1);
                    if (next != generation) {
                        generation = next;
                        lastCatalog = catalog;
                        creations.setCatalog(catalog);
                        // A generation bump is any catalog change; only close the open Creation when it changed.
                        if (creation != null && !CreationIdentity.stillOffered(openCreation, identities(catalog))) {
                            closeCreation();
                        }
                    }
                }
                @Override public void onFailure() {}
            });
            handler.postDelayed(this, 2000);
        }
    };

    private void openCreation(JSONObject item) {
        if (creation != null) closeCreation();
        creation = new CreationWebViewHost(activity, client, item, this::closeCreation);
        openCreation = identity(item);
        board.setVisibility(GONE);
        float density = getResources().getDisplayMetrics().density;
        LayoutParams params = new LayoutParams(Math.round(240f * density), Math.round(282f * density));
        params.gravity = Gravity.CENTER;
        addView(creation, params);
        back.setVisibility(VISIBLE);
        back.bringToFront();
        creationVisibility.accept(true);
    }

    private void openCalendar() {
        if (calendar != null) return;
        calendar = new CalendarPageView(activity, openVoice, this::closeCalendar);
        if (fixtureMode != BoardFixtures.Mode.OFF) calendar.useFixture(agenda.snapshot());
        board.setVisibility(GONE);
        addView(calendar, match());
        back.setVisibility(VISIBLE);
        back.bringToFront();
        creationVisibility.accept(true);
        calendar.start(); calendar.requestFocus();
    }

    private void openCalendarEvent(JSONObject event) {
        openCalendar();
        if (calendar != null) calendar.showEvent(event);
    }

    private void closeCalendar() {
        if (calendar == null) return;
        removeView(calendar); calendar.close(); calendar=null; showBoard();
    }

    private void openTasks() {
        if (tasks != null) return;
        tasks = new TaskPageView(activity, openVoice);
        if (fixtureMode != BoardFixtures.Mode.OFF) tasks.useFixture(tasksWidget.snapshot());
        board.setVisibility(GONE); addView(tasks, match()); back.setVisibility(VISIBLE); back.bringToFront(); creationVisibility.accept(true); tasks.start(); tasks.requestFocus();
    }

    private void openTask(JSONObject task) {
        openTasks();
        if (tasks != null) tasks.showTask(task);
    }

    private void closeTasks() {
        if (tasks == null) return;
        removeView(tasks); tasks.close(); tasks=null; showBoard();
        if (!closed) tasksWidget.refresh();
    }

    private void closeCreation() {
        if (creation == null) return;
        removeView(creation);
        creation.destroy();
        creation = null;
        openCreation = null;
        showBoard();
    }

    private void showBoard() {
        board.setVisibility(VISIBLE);
        back.setVisibility(GONE);
        creationVisibility.accept(false);
        board.requestFocus();
    }

    private static List<CreationIdentity> identities(JSONObject catalog) {
        JSONArray cards = catalog.optJSONArray("cards");
        if (cards == null) cards = catalog.optJSONArray("creations");
        List<CreationIdentity> result = new ArrayList<>();
        if (cards != null) for (int i = 0; i < cards.length(); i++) {
            JSONObject item = cards.optJSONObject(i);
            if (item != null) result.add(identity(item));
        }
        return result;
    }

    private static CreationIdentity identity(JSONObject item) {
        String source = item.optString("sourceType", "local_archive");
        String entry = "rabbit_qr_link".equals(source) ? item.optString("entryUrl", "") : item.optString("entryAsset", "");
        return new CreationIdentity(item.optString("creationId"), item.optString("contentHash"), entry, source);
    }

    private LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override public void close() {
        closed = true;
        stop();
        closeCreation();
        closeCalendar();
        closeTasks();
        board.close();
        client.close();
    }

    /** The board's view of this page. */
    private final class Host implements BoardHost {
        @Override public Context context() { return activity; }
        @Override public BoardFixtures.Mode fixtureMode() { return fixtureMode; }
        @Override public void widgetChanged(BoardWidget widget) { board.widgetChanged(widget); }
        @Override public void openCalendar() { CardsPageView.this.openCalendar(); }
        @Override public void openCalendarEvent(JSONObject event) { CardsPageView.this.openCalendarEvent(event); }
        @Override public void openTasks() { CardsPageView.this.openTasks(); }
        @Override public void openTask(JSONObject task) { CardsPageView.this.openTask(task); }
        @Override public void openCreation(JSONObject item) { CardsPageView.this.openCreation(item); }
    }
}
