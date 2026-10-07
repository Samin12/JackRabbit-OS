package com.resonolabs.feature.cards.board;

import android.content.Context;

import org.json.JSONObject;

/** What a board widget may ask of the Cards page that hosts it. Main thread only. */
public interface BoardHost {
    Context context();

    /** Debug fixture data instead of the runtime (debug builds, {@code debug.sam.widgets.fake}). */
    BoardFixtures.Mode fixtureMode();

    /** The widget's data or layout changed: re-measure and redraw. */
    void widgetChanged(BoardWidget widget);

    /** Full Calendar page. */
    void openCalendar();

    /** Calendar page opened straight into one event's detail; BACK returns to the board. */
    void openCalendarEvent(JSONObject event);

    /** Full Tasks page. */
    void openTasks();

    /** Tasks page opened straight into one task's detail; BACK returns to the board. */
    void openTask(JSONObject task);

    /** A catalog Creation (local_archive / plugin_card / rabbit_qr_link), opened as before. */
    void openCreation(JSONObject item);

    // TODO(wave2:T3Widget): void openT3Thread(String threadId) -> ProductRootView T3 tab / thread detail.
    // TODO(wave2:LiveWidget): void openLiveCard(String cardId) -> GenUI LiveCardsPageView expanded card.
}
