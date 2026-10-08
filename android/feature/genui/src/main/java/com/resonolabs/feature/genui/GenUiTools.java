package com.resonolabs.feature.genui;

/** Names of the Realtime card tools the app executes locally (registered by the runtime). */
public final class GenUiTools {
    public static final String SHOW_CARD = "show_card";
    public static final String UPDATE_CARD = "update_card";
    public static final String DISMISS_CARD = "dismiss_card";

    private GenUiTools() {}

    /** True for tools the Voice page must run on-device instead of via the runtime MCP loopback. */
    public static boolean isLocal(String toolName) {
        return SHOW_CARD.equals(toolName) || UPDATE_CARD.equals(toolName) || DISMISS_CARD.equals(toolName);
    }
}
