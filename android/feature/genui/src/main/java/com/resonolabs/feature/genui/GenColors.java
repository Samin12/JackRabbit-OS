package com.resonolabs.feature.genui;

/**
 * Plain ARGB ints mirroring {@code SamTheme} (so the model stays JVM-testable) plus the
 * GenUI-only status green. Values are identical to the SamTheme tokens.
 */
public final class GenColors {
    private GenColors() {}

    public static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    /** Multiplies the color's existing alpha by {@code factor} (0..1). */
    public static int fade(int color, float factor) {
        int alpha = (color >>> 24) & 0xFF;
        return withAlpha(color, Math.round(alpha * Math.max(0f, Math.min(1f, factor))));
    }

    /** Mixes toward white; used to lift dark accents for small text on dark glass. */
    public static int lift(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return rgb(Math.round(r + (255 - r) * amount), Math.round(g + (255 - g) * amount),
                Math.round(b + (255 - b) * amount));
    }

    /** Pulls a color toward gray; used for stale live cards. */
    public static int desaturate(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int gray = Math.round(r * 0.3f + g * 0.59f + b * 0.11f);
        return (color & 0xFF000000) | (Math.round(r + (gray - r) * amount) << 16)
                | (Math.round(g + (gray - g) * amount) << 8) | Math.round(b + (gray - b) * amount);
    }

    public static final int BACKGROUND = rgb(9, 11, 16);
    public static final int INK = rgb(245, 248, 255);
    public static final int MUTED = rgb(140, 152, 172);
    public static final int ORB_BLUE = rgb(26, 115, 242);
    public static final int ORB_PALE = rgb(160, 199, 255);
    public static final int PANEL = rgb(18, 22, 32);
    public static final int PANEL_RAISED = rgb(24, 29, 42);
    public static final int LINE = argb(34, 190, 210, 255);
    public static final int HAIRLINE = argb(26, 190, 210, 255);
    public static final int AMBER = rgb(255, 196, 92);
    public static final int RED = rgb(255, 99, 99);
    public static final int DANGER_FILL = rgb(196, 54, 48);
    /** Proposed SUCCESS token (genui.md section 2.1); kept local to avoid touching SamTheme. */
    public static final int SUCCESS = rgb(96, 214, 160);
    public static final int CYAN = rgb(92, 162, 255);

    public static int status(int status) {
        return switch (status) {
            case GenSchema.STATUS_OK -> SUCCESS;
            case GenSchema.STATUS_WARN -> AMBER;
            case GenSchema.STATUS_ERROR -> RED;
            case GenSchema.STATUS_ACTIVE -> ORB_PALE;
            default -> MUTED;
        };
    }
}
