package com.resonolabs.feature.cards;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.SamTheme;

/** One visible return control for every Cards-owned content surface. */
final class CardsBackButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable navigateBack;

    CardsBackButton(Context context, Runnable navigateBack) {
        super(context);
        this.navigateBack = navigateBack;
        setContentDescription("Back to Cards");
        setFocusable(true);
    }

    @Override protected void onDraw(Canvas canvas) {
        SamTheme.glass(canvas, paint, new android.graphics.RectF(10f, 22f, 54f, 66f), 22f, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.INK);
        canvas.drawLine(36f, 34f, 27f, 44f, paint);
        canvas.drawLine(27f, 44f, 36f, 54f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            navigateBack.run();
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
