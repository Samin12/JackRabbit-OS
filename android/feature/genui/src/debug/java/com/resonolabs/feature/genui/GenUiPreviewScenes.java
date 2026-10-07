package com.resonolabs.feature.genui;

/**
 * Debug-only fixture scenes for the GenUI preview. Every card goes through the real
 * {@link GenUiController#execute} path, so the preview also exercises the parser and store.
 */
final class GenUiPreviewScenes {
    enum Session { IDLE, LIVE, RESPONDING }

    static final class Scene {
        final String name;
        final Session session;
        final boolean realSources;
        final String[] cards;

        Scene(String name, Session session, boolean realSources, String... cards) {
            this.name = name;
            this.session = session;
            this.realSources = realSources;
            this.cards = cards;
        }
    }

    static final String GROCERIES = "{\"id\":\"groceries\",\"eyebrow\":\"Shopping\",\"icon\":\"cart\",\"accent\":\"violet\","
            + "\"title\":\"Grocery list\",\"subtitle\":\"6 items • Trader Joe's\",\"body\":[{\"type\":\"checklist\","
            + "\"id\":\"items\",\"items\":[{\"text\":\"Oat milk\",\"checked\":true},{\"text\":\"Eggs (dozen)\",\"checked\":true},"
            + "{\"text\":\"Spinach\"},{\"text\":\"Sourdough\"},{\"text\":\"Greek yogurt\"},{\"text\":\"Coffee beans\"}]}],"
            + "\"actions\":[{\"label\":\"Add item\",\"say\":\"Add something to the grocery list\"},"
            + "{\"label\":\"Done\",\"style\":\"primary\",\"dismiss\":true}]}";

    static final String GROCERIES_LONG = "{\"id\":\"groceries\",\"eyebrow\":\"Shopping\",\"icon\":\"cart\",\"accent\":\"violet\","
            + "\"title\":\"Grocery list\",\"subtitle\":\"8 items • Trader Joe's\",\"body\":[{\"type\":\"checklist\","
            + "\"id\":\"items\",\"items\":[{\"text\":\"Oat milk\",\"checked\":true},{\"text\":\"Eggs (dozen)\",\"checked\":true},"
            + "{\"text\":\"Spinach\"},{\"text\":\"Sourdough\"},{\"text\":\"Greek yogurt\"},{\"text\":\"Coffee beans\"},"
            + "{\"text\":\"Bananas\"},{\"text\":\"Olive oil\"}]},{\"type\":\"text\",\"style\":\"muted\","
            + "\"text\":\"Trader Joe's on Masonic closes at 9 PM. Bring the reusable bags from the car.\"}],"
            + "\"actions\":[{\"label\":\"Add item\",\"say\":\"Add something to the grocery list\"},"
            + "{\"label\":\"Done\",\"style\":\"primary\",\"dismiss\":true}]}";

    static final String TIMER_PILL = "{\"id\":\"timer-pasta\",\"eyebrow\":\"Timer\",\"icon\":\"timer\",\"accent\":\"amber\","
            + "\"title\":\"Pasta\",\"size\":\"compact\",\"live\":{\"type\":\"timer\",\"durationSec\":540},"
            + "\"actions\":[{\"label\":\"+1 min\",\"timer\":\"add1m\"},{\"label\":\"Cancel\",\"style\":\"danger\",\"dismiss\":true}]}";

    static final String TIMER_CARD = "{\"id\":\"timer-pasta\",\"eyebrow\":\"Timer\",\"icon\":\"timer\",\"accent\":\"amber\","
            + "\"title\":\"Pasta\",\"body\":[{\"type\":\"timer\",\"id\":\"timer\",\"durationSec\":540,"
            + "\"label\":\"Boil until al dente\"}],\"actions\":[{\"label\":\"+1 min\",\"timer\":\"add1m\"},"
            + "{\"label\":\"Cancel\",\"style\":\"danger\",\"dismiss\":true}]}";

    static final String UBER = "{\"id\":\"uber\",\"size\":\"compact\",\"icon\":\"car\",\"accent\":\"blue\","
            + "\"title\":\"your Uber to SFO\",\"subtitle\":\"Toyota Camry • 7KXP221 • 4 min\","
            + "\"actions\":[{\"label\":\"Track\",\"say\":\"Where is my Uber?\"}]}";

    static final String T3 = "{\"id\":\"t3-login-fix\",\"eyebrow\":\"T3 Code • web-app\",\"icon\":\"code\",\"accent\":\"cyan\","
            + "\"title\":\"Fix login redirect bug\",\"live\":{\"type\":\"t3-thread\",\"threadId\":\"thr_8f2a\"},"
            + "\"actions\":[{\"label\":\"Status\",\"say\":\"How is the login redirect thread going?\"},"
            + "{\"label\":\"Stop\",\"style\":\"danger\",\"say\":\"Stop the login redirect thread.\"}]}";

    static final String WEATHER = "{\"id\":\"weather-sf\",\"eyebrow\":\"Weather\",\"icon\":\"weather\",\"accent\":\"blue\","
            + "\"title\":\"San Francisco\",\"body\":[{\"type\":\"weather\",\"temp\":\"64°\",\"condition\":\"rain\","
            + "\"hi\":\"66°\",\"lo\":\"55°\",\"place\":\"Now\",\"hours\":[{\"t\":\"3p\",\"temp\":\"64°\",\"condition\":\"rain\"},"
            + "{\"t\":\"4p\",\"temp\":\"63°\",\"condition\":\"rain\"},{\"t\":\"5p\",\"temp\":\"62°\",\"condition\":\"cloudy\"},"
            + "{\"t\":\"6p\",\"temp\":\"60°\",\"condition\":\"partly-cloudy\"},{\"t\":\"7p\",\"temp\":\"58°\",\"condition\":\"clear-night\"}]}]}";

    static final String SPEND = "{\"id\":\"spend\",\"eyebrow\":\"Spending • this week\",\"icon\":\"chart\",\"accent\":\"pink\","
            + "\"title\":\"You spent $412\",\"body\":[{\"type\":\"stat\",\"value\":\"$412.80\",\"label\":\"Week to date\","
            + "\"delta\":\"18% vs last\",\"trend\":\"up\"},{\"type\":\"bars\",\"values\":[32,58,12,140,44,96,30],"
            + "\"labels\":[\"M\",\"T\",\"W\",\"T\",\"F\",\"S\",\"S\"],\"unit\":\"$\",\"highlight\":3},"
            + "{\"type\":\"kv\",\"columns\":2,\"pairs\":[{\"k\":\"Biggest\",\"v\":\"Costco $140\"},{\"k\":\"Budget left\",\"v\":\"$187\"}]}]}";

    static final String PACKAGE = "{\"id\":\"package\",\"eyebrow\":\"Amazon • this week\",\"icon\":\"home\",\"accent\":\"amber\","
            + "\"title\":\"Running shoes arrive today\",\"body\":[{\"type\":\"progress\",\"label\":\"Puma Velocity Nitro 3\","
            + "\"steps\":[\"Shipped\",\"In transit\",\"Delivered\"],\"step\":1},{\"type\":\"kv\",\"columns\":2,"
            + "\"pairs\":[{\"k\":\"Carrier\",\"v\":\"UPS\"},{\"k\":\"Window\",\"v\":\"2 – 6 PM\"}]},"
            + "{\"type\":\"text\",\"style\":\"muted\",\"text\":\"Another order lands by Friday.\"}],"
            + "\"actions\":[{\"label\":\"Track\",\"say\":\"Track my Amazon package\"}]}";

    static final String CALENDAR = "{\"id\":\"cal-next\",\"size\":\"compact\",\"icon\":\"calendar\",\"accent\":\"pink\","
            + "\"title\":\"Next meeting\",\"live\":{\"type\":\"calendar-next\"}}";

    static final String RUN = "{\"id\":\"goal-recycling\",\"eyebrow\":\"Background goal\",\"icon\":\"bolt\",\"accent\":\"violet\","
            + "\"title\":\"Battery recycling options\",\"live\":{\"type\":\"background-run\",\"runId\":\"run-preview\"},"
            + "\"actions\":[{\"label\":\"Status\",\"say\":\"How is the battery recycling research going?\"}]}";

    static final String T3_DONE = "{\"id\":\"t3-done\",\"eyebrow\":\"T3 Code • web-app\",\"icon\":\"code\",\"accent\":\"cyan\","
            + "\"title\":\"Fix login redirect bug\",\"live\":{\"type\":\"t3-thread\",\"threadId\":\"thr_done\"},"
            + "\"actions\":[{\"label\":\"Read reply\",\"say\":\"Read me the last reply in the login thread.\"}]}";

    static final String STRESS = "{\"id\":\"stress\",\"eyebrow\":\"An extraordinarily long eyebrow label here\","
            + "\"icon\":\"warning\",\"accent\":\"red\",\"title\":\"This title is far longer than five words and must ellipsize cleanly\","
            + "\"subtitle\":\"A subtitle that also keeps going well past the width of the R1 screen\",\"body\":["
            + "{\"type\":\"stat\",\"value\":\"$1,234,567.89\",\"label\":\"Total including every fee and tax\","
            + "\"delta\":\"-12.5% vs last month\",\"trend\":\"down\"},"
            + "{\"type\":\"kv\",\"columns\":2,\"pairs\":[{\"k\":\"Extremely long key name\",\"v\":\"Extremely long value text\"},"
            + "{\"k\":\"Short\",\"v\":\"WWWWWWWWWWWWWWWWWWWWWWWWWWWW\"}]},"
            + "{\"type\":\"list\",\"items\":[{\"title\":\"WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW\",\"trailing\":\"999,999.99\","
            + "\"detail\":\"Detail that is long enough to need an ellipsis at the end of the row\",\"status\":\"warn\"},"
            + "{\"title\":\"Second\",\"status\":\"error\"}]}],"
            + "\"actions\":[{\"label\":\"Acknowledge everything\",\"style\":\"primary\",\"dismiss\":true},"
            + "{\"label\":\"Tell me more about it\",\"say\":\"Tell me more\"}]}";

    static final String REAL_TASKS = "{\"id\":\"tasks\",\"eyebrow\":\"Tasks\",\"icon\":\"task\",\"accent\":\"amber\","
            + "\"title\":\"Open tasks\",\"live\":{\"type\":\"tasks\"}}";
    static final String REAL_CALENDAR = "{\"id\":\"cal-real\",\"eyebrow\":\"Calendar\",\"icon\":\"calendar\",\"accent\":\"pink\","
            + "\"title\":\"Next event\",\"live\":{\"type\":\"calendar-next\"}}";
    static final String REAL_T3 = "{\"id\":\"t3-real\",\"eyebrow\":\"T3 Code\",\"icon\":\"code\",\"accent\":\"cyan\","
            + "\"title\":\"Login redirect thread\",\"live\":{\"type\":\"t3-thread\",\"threadId\":\"thr_missing\"}}";

    static final Scene[] ALL = {
            new Scene("compact-timer", Session.LIVE, false, UBER, TIMER_PILL),
            new Scene("checklist", Session.RESPONDING, false, GROCERIES),
            new Scene("stack", Session.RESPONDING, false, GROCERIES, WEATHER, SPEND),
            new Scene("t3-live", Session.LIVE, false, GROCERIES, T3),
            new Scene("timer-card", Session.LIVE, false, TIMER_CARD),
            new Scene("weather", Session.RESPONDING, false, WEATHER),
            new Scene("stat-bars", Session.RESPONDING, false, SPEND),
            new Scene("expanded", Session.LIVE, false, GROCERIES_LONG),
            new Scene("idle-action-pill", Session.IDLE, false, UBER),
            new Scene("idle-calendar", Session.IDLE, false, CALENDAR),
            new Scene("run-live", Session.LIVE, false, RUN),
            new Scene("t3-done", Session.LIVE, false, T3_DONE),
            new Scene("timer-done", Session.LIVE, false, TIMER_PILL),
            new Scene("stress", Session.RESPONDING, false, STRESS),
            new Scene("package", Session.RESPONDING, false, PACKAGE),
            new Scene("real-sources", Session.LIVE, true, REAL_T3, REAL_CALENDAR, REAL_TASKS),
            new Scene("adhoc", Session.LIVE, false),
    };

    private GenUiPreviewScenes() {}
}
