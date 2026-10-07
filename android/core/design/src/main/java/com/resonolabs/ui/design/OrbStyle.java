package com.resonolabs.ui.design;

/** How hero orbs are drawn: the blue Fluid Orb or the voxel Pixel head. */
public enum OrbStyle {
    FLUID("fluid", "Orb"),
    PIXEL_HEAD("pixel_head", "Pixel head");

    private final String key;
    private final String label;

    OrbStyle(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Stable persisted value. */
    public String key() {
        return key;
    }

    /** Short UI label (segmented control). */
    public String label() {
        return label;
    }

    /** Persisted value back to a style; anything unknown or missing is {@link #FLUID}. */
    public static OrbStyle parse(String value) {
        if (value != null) {
            for (OrbStyle style : values()) {
                if (style.key.equals(value.trim())) return style;
            }
        }
        return FLUID;
    }
}
