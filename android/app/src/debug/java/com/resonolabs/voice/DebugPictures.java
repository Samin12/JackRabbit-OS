package com.resonolabs.voice;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;

/**
 * Debug builds only: synthetic JPEGs for the transcript/viewer/card screenshots without the
 * runtime, the Mac or the camera (a dark-theme bar chart like the Mac's generated UIs, a Mac
 * window, a photo-like gradient). Never in release builds.
 */
final class DebugPictures {
    private DebugPictures() {}

    static byte[] forSource(String source, String title) {
        if ("camera".equals(source)) return photo();
        if ("mac_screenshot".equals(source)) return screenshot();
        return chart(title);
    }

    /** 960x640 dark card with a bar chart, like a generated "graph of …" widget. */
    static byte[] chart(String title) {
        int w = 960;
        int h = 640;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(17, 21, 32), Color.rgb(10, 12, 18), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(Color.rgb(245, 248, 255));
        paint.setTextSize(44);
        canvas.drawText(title == null || title.isBlank() ? "Weekly focus hours" : title, 56, 92, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(26);
        paint.setColor(Color.rgb(140, 152, 172));
        canvas.drawText("Last 7 days · hours per day", 56, 136, paint);
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        float[] values = {4.5f, 6.2f, 3.1f, 7.4f, 5.8f, 2.2f, 1.6f};
        float left = 80;
        float base = 560;
        float top = 190;
        paint.setStrokeWidth(2);
        for (int line = 0; line <= 4; line++) {
            float y = base - (base - top) * line / 4f;
            paint.setColor(Color.argb(40, 190, 210, 255));
            canvas.drawLine(left, y, w - 56, y, paint);
            paint.setColor(Color.rgb(110, 122, 142));
            paint.setTextSize(20);
            canvas.drawText(Integer.toString(line * 2), 46, y + 7, paint);
        }
        float slot = (w - 56 - left) / days.length;
        for (int index = 0; index < days.length; index++) {
            float barLeft = left + index * slot + slot * 0.18f;
            float barRight = left + (index + 1) * slot - slot * 0.18f;
            float barTop = base - (base - top) * values[index] / 8f;
            boolean peak = index == 3;
            paint.setShader(new LinearGradient(0, barTop, 0, base,
                    peak ? Color.rgb(160, 199, 255) : Color.rgb(124, 108, 255),
                    peak ? Color.rgb(26, 115, 242) : Color.rgb(70, 60, 170), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(barLeft, barTop, barRight, base), 12, 12, paint);
            paint.setShader(null);
            paint.setColor(Color.rgb(200, 210, 228));
            paint.setTextSize(22);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(days[index], (barLeft + barRight) / 2f, base + 36, paint);
            if (peak) {
                paint.setColor(Color.rgb(245, 248, 255));
                paint.setTextSize(24);
                canvas.drawText("7.4 h", (barLeft + barRight) / 2f, barTop - 14, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }
        return jpeg(bitmap, 82);
    }

    /** 1024x640 fake Mac desktop with a browser-like window. */
    static byte[] screenshot() {
        int w = 1024;
        int h = 640;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, w, h, Color.rgb(40, 60, 120), Color.rgb(150, 70, 140), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(200, 20, 22, 30));
        canvas.drawRect(0, 0, w, 28, paint);
        paint.setColor(Color.rgb(250, 250, 252));
        canvas.drawRoundRect(new RectF(90, 70, 934, 590), 14, 14, paint);
        paint.setColor(Color.rgb(232, 234, 240));
        canvas.drawRoundRect(new RectF(90, 70, 934, 122), 14, 14, paint);
        int[] dots = {Color.rgb(255, 95, 87), Color.rgb(254, 188, 46), Color.rgb(40, 200, 64)};
        for (int index = 0; index < 3; index++) {
            paint.setColor(dots[index]);
            canvas.drawCircle(118 + index * 24, 96, 7, paint);
        }
        paint.setColor(Color.rgb(255, 255, 255));
        canvas.drawRoundRect(new RectF(220, 84, 800, 108), 12, 12, paint);
        paint.setColor(Color.rgb(120, 124, 134));
        paint.setTextSize(15);
        canvas.drawText("github.com/Samin12/SamRabbit", 236, 101, paint);
        paint.setColor(Color.rgb(30, 34, 44));
        paint.setTextSize(30);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        canvas.drawText("SamRabbit", 130, 180, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        for (int line = 0; line < 9; line++) {
            paint.setColor(Color.rgb(214, 218, 226));
            canvas.drawRoundRect(new RectF(130, 214 + line * 38, 130 + 640 - (line % 3) * 140, 232 + line * 38), 6, 6, paint);
        }
        return jpeg(bitmap, 80);
    }

    /** 960x720 photo-like soft scene. */
    static byte[] photo() {
        int w = 960;
        int h = 720;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(255, 196, 140), Color.rgb(90, 120, 170), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(680, 250, 120, Color.argb(255, 255, 240, 200), Color.argb(0, 255, 240, 200), Shader.TileMode.CLAMP));
        canvas.drawCircle(680, 250, 120, paint);
        paint.setShader(new LinearGradient(0, 420, 0, h, Color.rgb(60, 80, 70), Color.rgb(25, 35, 30), Shader.TileMode.CLAMP));
        android.graphics.Path hills = new android.graphics.Path();
        hills.moveTo(0, 470);
        hills.quadTo(240, 380, 480, 460);
        hills.quadTo(720, 540, 960, 430);
        hills.lineTo(960, 720);
        hills.lineTo(0, 720);
        hills.close();
        canvas.drawPath(hills, paint);
        return jpeg(bitmap, 78);
    }

    private static byte[] jpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(160_000);
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
