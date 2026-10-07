package com.resonolabs.feature.genui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The 18 card glyphs plus weather conditions, built once as Paths on a 24-unit grid and
 * drawn with save/translate/scale (no allocation in draw). Line icons use round caps/joins.
 */
public final class GenIcons {
    private static final float GRID = 24f;

    private final Path[] stroke = new Path[GenSchema.ICONS.length];
    private final Path[] fill = new Path[GenSchema.ICONS.length];
    private final Path check = new Path();
    private final Path close = new Path();
    private final Path triangleUp = new Path();
    private final Path triangleDown = new Path();
    private final Path sunRays = new Path();
    private final Path moon = new Path();
    private final Path cloud = new Path();
    private final Path rain = new Path();
    private final Path snow = new Path();
    private final Path fog = new Path();
    private final Path wind = new Path();
    private final Path smallBolt = new Path();
    private final Path pause = new Path();

    public GenIcons() {
        for (int index = 0; index < stroke.length; index++) {
            stroke[index] = new Path();
            fill[index] = new Path();
        }
        // calendar
        Path p = stroke[0];
        p.addRoundRect(3f, 5f, 21f, 21f, 2.5f, 2.5f, Path.Direction.CW);
        line(p, 3f, 10f, 21f, 10f);
        line(p, 8f, 3f, 8f, 7f);
        line(p, 16f, 3f, 16f, 7f);
        // clock
        p = stroke[1];
        p.addCircle(12f, 12f, 9f, Path.Direction.CW);
        p.moveTo(12f, 7f);
        p.lineTo(12f, 12f);
        p.lineTo(15.5f, 14f);
        // timer
        p = stroke[2];
        p.addCircle(12f, 13.5f, 8f, Path.Direction.CW);
        line(p, 10f, 2.5f, 14f, 2.5f);
        line(p, 12f, 13.5f, 14.8f, 10.7f);
        line(p, 18.6f, 6.4f, 20f, 5f);
        // check
        p = stroke[3];
        p.moveTo(4.5f, 12.5f);
        p.lineTo(9.5f, 17.5f);
        p.lineTo(19.5f, 7f);
        // task
        p = stroke[4];
        p.addRoundRect(3.5f, 3.5f, 20.5f, 20.5f, 4f, 4f, Path.Direction.CW);
        p.moveTo(8f, 12f);
        p.lineTo(11f, 15f);
        p.lineTo(16.5f, 9f);
        // code
        p = stroke[5];
        p.moveTo(8.5f, 6.5f);
        p.lineTo(3f, 12f);
        p.lineTo(8.5f, 17.5f);
        p.moveTo(15.5f, 6.5f);
        p.lineTo(21f, 12f);
        p.lineTo(15.5f, 17.5f);
        // mail
        p = stroke[6];
        p.addRoundRect(2.5f, 5f, 21.5f, 19f, 2.5f, 2.5f, Path.Direction.CW);
        p.moveTo(3f, 7f);
        p.lineTo(12f, 13f);
        p.lineTo(21f, 7f);
        // bolt
        p = fill[7];
        p.moveTo(13.5f, 2f);
        p.lineTo(4.5f, 13.5f);
        p.lineTo(11f, 13.5f);
        p.lineTo(10f, 22f);
        p.lineTo(19.5f, 10f);
        p.lineTo(13f, 10f);
        p.close();
        // pin
        p = stroke[8];
        p.moveTo(12f, 21.5f);
        p.lineTo(7.4f, 14.1f);
        p.arcTo(5.5f, 3f, 18.5f, 16f, 135f, 270f, false);
        p.close();
        p.addCircle(12f, 9.5f, 2.3f, Path.Direction.CW);
        // cart
        p = stroke[9];
        p.moveTo(2.5f, 3.5f);
        p.lineTo(5f, 3.5f);
        p.lineTo(7.5f, 15f);
        p.lineTo(18.5f, 15f);
        p.lineTo(21f, 7f);
        p.lineTo(6f, 7f);
        p.addCircle(9f, 19.5f, 1.6f, Path.Direction.CW);
        p.addCircle(17.5f, 19.5f, 1.6f, Path.Direction.CW);
        // plane
        p = fill[10];
        p.moveTo(21f, 16f);
        p.lineTo(13.2f, 11f);
        p.lineTo(13.2f, 4.6f);
        p.quadTo(12f, 1.6f, 10.8f, 4.6f);
        p.lineTo(10.8f, 11f);
        p.lineTo(3f, 16f);
        p.lineTo(3f, 18f);
        p.lineTo(10.8f, 15.6f);
        p.lineTo(10.8f, 19.4f);
        p.lineTo(8.6f, 21f);
        p.lineTo(8.6f, 22.2f);
        p.lineTo(12f, 21.2f);
        p.lineTo(15.4f, 22.2f);
        p.lineTo(15.4f, 21f);
        p.lineTo(13.2f, 19.4f);
        p.lineTo(13.2f, 15.6f);
        p.lineTo(21f, 18f);
        p.close();
        // car
        p = stroke[11];
        p.moveTo(5f, 11f);
        p.lineTo(6.8f, 6.8f);
        p.quadTo(7.3f, 6f, 8.2f, 6f);
        p.lineTo(15.8f, 6f);
        p.quadTo(16.7f, 6f, 17.2f, 6.8f);
        p.lineTo(19f, 11f);
        p.addRoundRect(3f, 11f, 21f, 16.5f, 2.5f, 2.5f, Path.Direction.CW);
        line(p, 6.5f, 16.5f, 6.5f, 19.5f);
        line(p, 17.5f, 16.5f, 17.5f, 19.5f);
        // music
        p = stroke[12];
        p.addCircle(6.5f, 17.5f, 2.5f, Path.Direction.CW);
        p.addCircle(17.5f, 15.5f, 2.5f, Path.Direction.CW);
        p.moveTo(9f, 17.5f);
        p.lineTo(9f, 5.5f);
        p.lineTo(20f, 3.5f);
        p.lineTo(20f, 15.5f);
        line(p, 9f, 9.5f, 20f, 7.5f);
        // cloud (shared): union of three circles and a base
        Path union = new Path();
        union.addCircle(8f, 15.5f, 4f, Path.Direction.CW);
        Path part = new Path();
        part.addCircle(13f, 11.5f, 5.5f, Path.Direction.CW);
        union.op(part, Path.Op.UNION);
        part.reset();
        part.addCircle(17.5f, 15.5f, 4f, Path.Direction.CW);
        union.op(part, Path.Op.UNION);
        part.reset();
        part.addRect(8f, 15.5f, 17.5f, 19.5f, Path.Direction.CW);
        union.op(part, Path.Op.UNION);
        cloud.set(union);
        // weather icon: sun peeking over a cloud outline
        p = stroke[13];
        p.addPath(cloud);
        p.addArc(4f, 3.5f, 13f, 12.5f, 150f, 150f);
        line(p, 8.5f, 0.8f, 8.5f, 1.8f);
        line(p, 2.2f, 8f, 1.2f, 8f);
        line(p, 3.9f, 3.4f, 3.2f, 2.7f);
        line(p, 13.1f, 3.4f, 13.8f, 2.7f);
        // chart
        p = stroke[14];
        p.moveTo(3.5f, 3.5f);
        p.lineTo(3.5f, 20.5f);
        p.lineTo(20.5f, 20.5f);
        line(p, 8f, 16.5f, 8f, 12f);
        line(p, 12.5f, 16.5f, 12.5f, 7.5f);
        line(p, 17f, 16.5f, 17f, 10.5f);
        // warning
        p = stroke[15];
        p.moveTo(12f, 3.5f);
        p.lineTo(21.5f, 20f);
        p.lineTo(2.5f, 20f);
        p.close();
        line(p, 12f, 9.5f, 12f, 14f);
        fill[15].addCircle(12f, 17f, 1.1f, Path.Direction.CW);
        // star
        p = stroke[16];
        for (int point = 0; point < 10; point++) {
            double angle = Math.PI / 5.0 * point - Math.PI / 2.0;
            float radius = point % 2 == 0 ? 9.5f : 4.1f;
            float x = 12f + (float) Math.cos(angle) * radius;
            float y = 12.8f + (float) Math.sin(angle) * radius;
            if (point == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        // home
        p = stroke[17];
        p.moveTo(3f, 11f);
        p.lineTo(12f, 3.5f);
        p.lineTo(21f, 11f);
        p.moveTo(5.5f, 9.5f);
        p.lineTo(5.5f, 20.5f);
        p.lineTo(18.5f, 20.5f);
        p.lineTo(18.5f, 9.5f);
        p.moveTo(10f, 20.5f);
        p.lineTo(10f, 14.5f);
        p.lineTo(14f, 14.5f);
        p.lineTo(14f, 20.5f);

        // ---- UI glyphs ----
        check.moveTo(6f, 12.5f);
        check.lineTo(10f, 16.5f);
        check.lineTo(18f, 8f);
        line(close, 6f, 6f, 18f, 18f);
        line(close, 18f, 6f, 6f, 18f);
        triangleUp.moveTo(12f, 5f);
        triangleUp.lineTo(21f, 19f);
        triangleUp.lineTo(3f, 19f);
        triangleUp.close();
        triangleDown.moveTo(12f, 19f);
        triangleDown.lineTo(21f, 5f);
        triangleDown.lineTo(3f, 5f);
        triangleDown.close();
        pause.addRoundRect(6.5f, 5f, 10f, 19f, 1.2f, 1.2f, Path.Direction.CW);
        pause.addRoundRect(14f, 5f, 17.5f, 19f, 1.2f, 1.2f, Path.Direction.CW);

        // ---- weather parts ----
        for (int ray = 0; ray < 8; ray++) {
            double angle = Math.PI / 4.0 * ray;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            line(sunRays, 12f + cos * 7.4f, 12f + sin * 7.4f, 12f + cos * 9.8f, 12f + sin * 9.8f);
        }
        Path disc = new Path();
        disc.addCircle(11f, 12f, 8f, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(16f, 7.5f, 6.5f, Path.Direction.CW);
        disc.op(bite, Path.Op.DIFFERENCE);
        moon.set(disc);
        line(rain, 8f, 19.5f, 6.8f, 22.5f);
        line(rain, 12.5f, 19.5f, 11.3f, 22.5f);
        line(rain, 17f, 19.5f, 15.8f, 22.5f);
        snow.addCircle(7.5f, 21f, 1.2f, Path.Direction.CW);
        snow.addCircle(12.5f, 22f, 1.2f, Path.Direction.CW);
        snow.addCircle(17.5f, 21f, 1.2f, Path.Direction.CW);
        line(fog, 3f, 8f, 21f, 8f);
        line(fog, 5f, 12.5f, 19f, 12.5f);
        line(fog, 3f, 17f, 16f, 17f);
        wind.moveTo(3f, 8.5f);
        wind.lineTo(14f, 8.5f);
        wind.cubicTo(18f, 8.5f, 18f, 3.5f, 14.5f, 4.5f);
        wind.moveTo(3f, 12.5f);
        wind.lineTo(18.5f, 12.5f);
        wind.cubicTo(22.5f, 12.5f, 22.5f, 18.5f, 18.5f, 17.5f);
        wind.moveTo(3f, 16.5f);
        wind.lineTo(10f, 16.5f);
        smallBolt.moveTo(13f, 16f);
        smallBolt.lineTo(9f, 21f);
        smallBolt.lineTo(12f, 21f);
        smallBolt.lineTo(11f, 24f);
        smallBolt.lineTo(15.5f, 19f);
        smallBolt.lineTo(12.5f, 19f);
        smallBolt.close();
    }

    private static void line(Path path, float x0, float y0, float x1, float y1) {
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
    }

    /** One of {@link GenSchema#ICONS}; {@code strokePx} is the on-screen line width. */
    public void draw(Canvas canvas, Paint paint, int icon, float cx, float cy, float size, int color,
                     float strokePx) {
        if (icon < 0 || icon >= stroke.length) return;
        float scale = size / GRID;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(scale, scale);
        paint.setShader(null);
        paint.setColor(color);
        if (!stroke[icon].isEmpty()) {
            strokeStyle(paint, strokePx / scale);
            canvas.drawPath(stroke[icon], paint);
        }
        if (!fill[icon].isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(fill[icon], paint);
        }
        restoreStyle(paint);
        canvas.restore();
    }

    public void drawCheck(Canvas canvas, Paint paint, float cx, float cy, float size, int color, float strokePx) {
        drawStroke(canvas, paint, check, cx, cy, size, color, strokePx);
    }

    public void drawClose(Canvas canvas, Paint paint, float cx, float cy, float size, int color, float strokePx) {
        drawStroke(canvas, paint, close, cx, cy, size, color, strokePx);
    }

    public void drawTriangle(Canvas canvas, Paint paint, boolean up, float cx, float cy, float size, int color) {
        drawFill(canvas, paint, up ? triangleUp : triangleDown, cx, cy, size, color);
    }

    public void drawPause(Canvas canvas, Paint paint, float cx, float cy, float size, int color) {
        drawFill(canvas, paint, pause, cx, cy, size, color);
    }

    /** Multi-color condition glyph (sun amber, clouds ink/gray, rain cyan, moon pale). */
    public void drawWeather(Canvas canvas, Paint paint, int condition, float cx, float cy, float size) {
        float scale = size / GRID;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(scale, scale);
        paint.setShader(null);
        float line = Math.max(1.4f, size / 14f) / scale;
        switch (condition) {
            case 0 -> sun(canvas, paint, 0f, 0f, 1f, line);
            case 1 -> {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(GenColors.ORB_PALE);
                canvas.drawPath(moon, paint);
            }
            case 2 -> {
                sun(canvas, paint, -3.5f, -4f, 0.78f, line);
                cloudAt(canvas, paint, 2f, 1.5f, 0.82f, GenColors.INK);
            }
            case 3 -> {
                cloudAt(canvas, paint, -2.5f, -3.5f, 0.7f, GenColors.withAlpha(GenColors.MUTED, 200));
                cloudAt(canvas, paint, 1.5f, 0f, 0.9f, GenColors.rgb(214, 222, 236));
            }
            case 4 -> {
                cloudAt(canvas, paint, 0f, -3.5f, 1f, GenColors.rgb(178, 190, 210));
                strokeStyle(paint, line);
                paint.setColor(GenColors.CYAN);
                canvas.drawPath(rain, paint);
            }
            case 5 -> {
                cloudAt(canvas, paint, 0f, -3.5f, 1f, GenColors.rgb(150, 162, 184));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(GenColors.AMBER);
                canvas.drawPath(smallBolt, paint);
            }
            case 6 -> {
                cloudAt(canvas, paint, 0f, -3.5f, 1f, GenColors.rgb(200, 210, 228));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(GenColors.INK);
                canvas.drawPath(snow, paint);
            }
            case 7 -> {
                strokeStyle(paint, line * 1.1f);
                paint.setColor(GenColors.rgb(170, 182, 202));
                canvas.drawPath(fog, paint);
            }
            case 8 -> {
                strokeStyle(paint, line * 1.1f);
                paint.setColor(GenColors.rgb(190, 202, 222));
                canvas.drawPath(wind, paint);
            }
            default -> cloudAt(canvas, paint, 0f, 0f, 1f, GenColors.INK);
        }
        restoreStyle(paint);
        canvas.restore();
    }

    private void sun(Canvas canvas, Paint paint, float dx, float dy, float scale, float line) {
        canvas.save();
        canvas.translate(12f + dx, 12f + dy);
        canvas.scale(scale, scale);
        canvas.translate(-12f, -12f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GenColors.AMBER);
        canvas.drawCircle(12f, 12f, 4.9f, paint);
        strokeStyle(paint, line / scale);
        canvas.drawPath(sunRays, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void cloudAt(Canvas canvas, Paint paint, float dx, float dy, float scale, int color) {
        canvas.save();
        canvas.translate(12f + dx, 15f + dy);
        canvas.scale(scale, scale);
        canvas.translate(-12f, -15f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(cloud, paint);
        canvas.restore();
    }

    private static void drawStroke(Canvas canvas, Paint paint, Path path, float cx, float cy, float size,
                                   int color, float strokePx) {
        float scale = size / GRID;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(scale, scale);
        paint.setShader(null);
        paint.setColor(color);
        strokeStyle(paint, strokePx / scale);
        canvas.drawPath(path, paint);
        restoreStyle(paint);
        canvas.restore();
    }

    private static void drawFill(Canvas canvas, Paint paint, Path path, float cx, float cy, float size, int color) {
        float scale = size / GRID;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(scale, scale);
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private static void strokeStyle(Paint paint, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private static void restoreStyle(Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
    }
}
