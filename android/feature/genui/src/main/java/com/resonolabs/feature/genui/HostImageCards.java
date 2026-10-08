package com.resonolabs.feature.genui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * App-built (trusted) cards that show a picture from the conversation: a camera photo, a Mac
 * screenshot from {@code mac_look}, or a UI generated on the Mac (CONTRACTS-WAVE3 §6). Pure
 * JSON, shown with {@link GenUiController#showHostCard}; the model's {@code show_card} schema
 * has no image block and cannot change these cards.
 */
public final class HostImageCards {
    public static final String CAMERA = "camera";
    public static final String MAC_SCREENSHOT = "mac_screenshot";
    public static final String GENERATED_UI = "generated_ui";
    static final int PHOTO_TTL_SEC = 300;
    static final int GENERATED_TTL_SEC = 1_800;

    private HostImageCards() {}

    /** Stable card id: one card per generated artifact, per picture otherwise. */
    public static String cardId(String source, String ref, String artifactId) {
        if (GENERATED_UI.equals(source) && artifactId != null && !artifactId.isBlank()) {
            return GenCardParser.normalizeId("ui-" + artifactId.toLowerCase(Locale.ROOT));
        }
        String hex = ref != null && ref.startsWith(GenImages.REF_PREFIX)
                ? ref.substring(GenImages.REF_PREFIX.length()) : String.valueOf(ref == null ? 0 : ref.hashCode());
        String prefix = CAMERA.equals(source) ? "photo-" : MAC_SCREENSHOT.equals(source) ? "screen-" : "image-";
        return GenCardParser.normalizeId(prefix + hex.substring(0, Math.min(12, hex.length())));
    }

    /**
     * The card JSON, or null without a picture or a generated title. {@code ref} may be null for
     * a generated UI whose image could not be fetched (the card then says so in words).
     * {@code announce} (no live session) titles a generated UI "New: …" for the idle pill.
     */
    public static JSONObject cardJson(String source, String ref, float aspect, String title, String summary,
                                      String artifactId, boolean announce) {
        boolean generated = GENERATED_UI.equals(source);
        boolean picture = GenImages.validRef(ref);
        if (!picture && !generated) return null;
        try {
            JSONObject card = new JSONObject();
            card.put("id", cardId(source, picture ? ref : null, artifactId));
            String heading;
            String eyebrow;
            String subtitle;
            String accent;
            if (generated) {
                String name = flat(title, GenSchema.TITLE);
                if (name.isEmpty()) name = "Generated UI";
                heading = announce ? flat("New: " + name, GenSchema.TITLE) : name;
                eyebrow = "Generated on your Mac";
                subtitle = flat(summary, GenSchema.SUBTITLE);
                accent = "violet";
                card.put("icon", "chart");
            } else if (CAMERA.equals(source)) {
                heading = flat(title, GenSchema.TITLE);
                if (heading.isEmpty()) heading = "Photo";
                eyebrow = "Camera";
                subtitle = flat(summary, GenSchema.SUBTITLE);
                accent = "blue";
            } else {
                heading = flat(title, GenSchema.TITLE);
                if (heading.isEmpty()) heading = "Your Mac";
                eyebrow = "Screenshot";
                subtitle = flat(summary, GenSchema.SUBTITLE);
                accent = "cyan";
            }
            card.put("title", heading);
            card.put("eyebrow", eyebrow);
            if (!subtitle.isEmpty()) card.put("subtitle", subtitle);
            card.put("accent", accent);
            card.put("ttlSec", generated ? GENERATED_TTL_SEC : PHOTO_TTL_SEC);
            JSONArray body = new JSONArray();
            if (picture) {
                JSONObject image = new JSONObject().put("type", "image").put("ref", ref)
                        .put("aspect", (double) clampAspect(aspect));
                String alt = generated ? flat(title, GenSchema.IMAGE_ALT) : flat(summary, GenSchema.IMAGE_ALT);
                if (!alt.isEmpty()) image.put("alt", alt);
                body.put(image);
            } else {
                body.put(new JSONObject().put("type", "text").put("style", "muted")
                        .put("text", "The picture is not available on the R1. It is on your Mac."));
            }
            card.put("body", body);
            return card;
        } catch (Exception invalid) {
            return null;
        }
    }

    static float clampAspect(float aspect) {
        if (Float.isNaN(aspect) || aspect <= 0f) return 0.75f;
        return Math.max(GenSchema.IMAGE_ASPECT_MIN, Math.min(GenSchema.IMAGE_ASPECT_MAX, aspect));
    }

    static String flat(String value, int max) {
        if (value == null) return "";
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        while (text.contains("  ")) text = text.replace("  ", " ");
        return text.length() <= max ? text : text.substring(0, max - 1).trim() + "…";
    }
}
