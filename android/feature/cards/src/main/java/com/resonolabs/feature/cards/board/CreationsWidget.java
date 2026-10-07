package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.resonolabs.ui.design.SamTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The runtime Card catalog (standalone Creations, Plugin cards, Rabbit QR links) as compact orb
 * tiles, two per row. Tiles open exactly as the old deck did (same source/entry validation).
 * The catalog is pushed by the page (generation-driven), so this widget has no own cadence.
 */
public final class CreationsWidget implements BoardWidget {
    private static final float LABEL = 46f;
    private static final float TILE_H = 128f;
    private static final float GUTTER = 12f;

    private final BoardHost host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPanel tileGlass = new GlassPanel();
    private final GlassPanel emptyGlass = new GlassPanel();
    private final List<Tile> tiles = new ArrayList<>();
    private List<JSONObject> cards = new ArrayList<>();
    private boolean loaded;
    private float width;
    private float tileWidth;
    private String count = "";
    private boolean changed = true;
    private float height;

    private static final class Tile {
        final JSONObject item;
        final OrbGlyph orb = new OrbGlyph();
        float left;
        float top;
        int accent;
        String kind = "";
        String[] title = new String[0];

        Tile(JSONObject item) { this.item = item; }
    }

    public CreationsWidget(BoardHost host) {
        this.host = host;
    }

    @Override public String id() { return "creations"; }

    /** The page's catalog refresh (only on a generation change). */
    public void setCatalog(JSONObject catalog) {
        JSONArray remote = catalog.optJSONArray("cards");
        if (remote == null) remote = catalog.optJSONArray("creations");
        List<JSONObject> next = new ArrayList<>();
        if (remote != null) for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item != null) next.add(item);
        }
        if (next.isEmpty()) {
            JSONArray fixtures = BoardFixtures.creations(host.fixtureMode());
            for (int i = 0; i < fixtures.length(); i++) next.add(fixtures.optJSONObject(i));
        }
        cards = next;
        loaded = true;
        changed = true;
        host.widgetChanged(this);
    }

    @Override public float measure(float width, long nowMs) {
        if (!changed && width == this.width) return height;
        changed = false;
        this.width = width;
        tiles.clear();
        tileWidth = (width - GUTTER) / 2f;
        float y = LABEL;
        for (int i = 0; i < cards.size(); i++) {
            Tile tile = new Tile(cards.get(i));
            tile.left = (i % 2) * (tileWidth + GUTTER);
            tile.top = LABEL + (i / 2) * (TILE_H + GUTTER);
            tile.accent = accentOf(tile.item);
            tile.kind = kindOf(tile.item);
            String title = tile.item.optString("title", "Creation").trim();
            tile.title = BoardPaint.wrap(paint, title.isEmpty() ? "Creation" : title, tileWidth - 36f, 2, 18f, BoardPaint.MEDIUM);
            tile.orb.set(36f, 40f, 18f, tile.accent, 2.2f);
            tiles.add(tile);
            y = tile.top + TILE_H;
        }
        if (tiles.isEmpty()) y = LABEL + 92f;
        count = tiles.isEmpty() ? "" : String.valueOf(tiles.size());
        tileGlass.size(tileWidth, TILE_H, 24f);
        emptyGlass.size(width, 92f, 24f);
        height = y + 4f;
        return height;
    }

    @Override public void draw(Canvas canvas, long nowMs) {
        BoardPaint.eyebrow(canvas, paint, "CREATIONS", 8f, 30f, 14f, SamTheme.withAlpha(SamTheme.INK, 200), Paint.Align.LEFT);
        if (!count.isEmpty()) {
            BoardPaint.text(canvas, paint, count, width - 8f, 30f, 15f, SamTheme.MUTED, Paint.Align.RIGHT, BoardPaint.REGULAR);
        }
        if (tiles.isEmpty()) {
            canvas.save();
            canvas.translate(0f, LABEL);
            emptyGlass.draw(canvas, paint, false);
            BoardPaint.text(canvas, paint, loaded ? "No creations yet" : "Loading creations…", 22f, 40f, 18f,
                    loaded ? SamTheme.INK : SamTheme.MUTED, Paint.Align.LEFT, BoardPaint.MEDIUM);
            if (loaded) {
                BoardPaint.text(canvas, paint, "Import one from R1 management.", 22f, 66f, 15f, SamTheme.MUTED,
                        Paint.Align.LEFT, BoardPaint.REGULAR);
            }
            canvas.restore();
            return;
        }
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            canvas.save();
            canvas.translate(tile.left, tile.top);
            tileGlass.draw(canvas, paint, false);
            tile.orb.draw(canvas, paint);
            BoardPaint.eyebrow(canvas, paint, tile.kind, 66f, 46f, 11f, OrbGlyph.pale(tile.accent), Paint.Align.LEFT);
            for (int line = 0; line < tile.title.length; line++) {
                BoardPaint.text(canvas, paint, tile.title[line], 18f, 90f + line * 23f, 18f, SamTheme.INK,
                        Paint.Align.LEFT, BoardPaint.MEDIUM);
            }
            canvas.restore();
        }
    }

    @Override public int focusCount() { return tiles.size(); }

    @Override public void focusBounds(int index, RectF out) {
        Tile tile = tiles.get(index);
        out.set(tile.left, tile.top, tile.left + tileWidth, tile.top + TILE_H);
    }

    @Override public boolean onTap(float x, float y) {
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            if (x >= tile.left && x < tile.left + tileWidth && y >= tile.top && y < tile.top + TILE_H) {
                open(tile.item);
                return true;
            }
        }
        return false;
    }

    @Override public boolean activate(int index) {
        if (index < 0 || index >= tiles.size()) return false;
        open(tiles.get(index).item);
        return true;
    }

    /** Same acceptance rules as the original CardsDeckView.activate(). */
    private void open(JSONObject item) {
        if (openable(item)) host.openCreation(item);
    }

    public static boolean openable(JSONObject item) {
        String source = item.optString("sourceType", "local_archive");
        String entry = "rabbit_qr_link".equals(source) ? item.optString("entryUrl", "") : item.optString("entryAsset", "");
        return ("rabbit_qr_link".equals(source) && entry.startsWith("https://"))
                || (("local_archive".equals(source) || "plugin_card".equals(source)) && entry.startsWith("/v1/creations/"));
    }

    @Override public long refreshIntervalMs() { return 0L; }
    @Override public void refresh() { }

    private static String kindOf(JSONObject item) {
        String source = item.optString("sourceType");
        return "plugin_card".equals(source) ? "APP" : "rabbit_qr_link".equals(source) ? "LINK" : "CREATION";
    }

    private static int accentOf(JSONObject item) {
        try { return Color.parseColor(item.optString("accent", "#1A73F2").toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return SamTheme.ORB_BLUE; }
    }
}
