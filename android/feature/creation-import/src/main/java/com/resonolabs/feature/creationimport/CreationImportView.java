package com.resonolabs.feature.creationimport;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.resonolabs.feature.camera.CapturedImage;
import com.resonolabs.feature.creationimport.qr.CreationQrCaptureView;
import com.resonolabs.feature.creationimport.qr.CreationQrDecoder;
import com.resonolabs.feature.creationimport.qr.ZxingCreationQrDecoder;
import com.resonolabs.hardware.motor.MotorController;
import com.resonolabs.runtime.host.RuntimeCreationImportClient;
import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;
import com.resonolabs.ui.input.UiInputTarget;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Removable Settings-owned Creation QR capture, review, and confirmation flow. */
public final class CreationImportView extends FrameLayout implements UiInputTarget, AutoCloseable {
    private final Activity activity;
    private final RuntimeCreationImportClient client;
    private final Runnable close;
    private final CreationQrCaptureView capture;
    private final Overlay overlay;
    private final CreationQrDecoder decoder = new ZxingCreationQrDecoder();
    private final ExecutorService decodeWorker = Executors.newSingleThreadExecutor();
    private CreationImportState state = CreationImportState.POSITIONING;
    private String message = "Positioning camera";
    private String title = "";
    private String description = "";
    private String token = "";
    private boolean replace;
    private Bitmap capturedBitmap;

    public CreationImportView(Activity activity, MotorController motor,
                              RuntimeCreationImportClient client, Runnable close) {
        super(activity); this.activity = activity; this.client = client; this.close = close;
        capture = new CreationQrCaptureView(activity, motor, new CreationQrCaptureView.Listener() {
            @Override public void onPositioning() { update(CreationImportState.POSITIONING, "Positioning camera"); }
            @Override public void onLive() { update(CreationImportState.LIVE, "Frame the Creation QR code"); }
            @Override public void onCaptured(CapturedImage image) { decode(image); }
            @Override public void onFailure(String value) { update(CreationImportState.ERROR, value); }
        });
        overlay = new Overlay(activity);
        addView(capture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setContentDescription("Import a Creation QR code");
    }

    public void start() { reset(); capture.start(); }
    public void stop() { capture.stopCamera(); clearBitmap(); }

    private void decode(CapturedImage image) {
        capturedBitmap = BitmapFactory.decodeByteArray(image.bytes(), 0, image.bytes().length);
        update(CreationImportState.DECODING, "Reading QR code");
        decodeWorker.execute(() -> {
            try {
                String raw = decoder.decode(image.bytes());
                JSONObject descriptor = new JSONObject(raw);
                activity.runOnUiThread(() -> preflight(descriptor));
            } catch (Exception error) {
                activity.runOnUiThread(() -> update(CreationImportState.ERROR,
                        error.getMessage() == null ? "This is not a supported Creation QR code" : error.getMessage()));
            }
        });
    }

    private void preflight(JSONObject descriptor) {
        update(CreationImportState.PREFLIGHT, "Checking Creation");
        client.preflight(activity, descriptor, value -> {
            JSONObject candidate = value.optJSONObject("candidate");
            title = candidate == null ? "Creation" : candidate.optString("title", "Creation");
            description = candidate == null ? "" : candidate.optString("description", "");
            token = value.optString("preflightToken", "");
            replace = value.optJSONObject("current") != null;
            update(CreationImportState.REVIEW, replace ? "This will replace the existing Creation" : "Ready to import");
        }, value -> update(CreationImportState.ERROR, value));
    }

    private void confirm() {
        if (token.isBlank()) return;
        update(CreationImportState.INSTALLING, replace ? "Replacing Creation" : "Importing Creation");
        client.confirm(activity, token, replace,
                ignored -> update(CreationImportState.SUCCESS, "Creation added to Cards"),
                value -> update(CreationImportState.ERROR, value));
    }

    private void retake() { reset(); capture.start(); }
    private void reset() { title=""; description=""; token=""; replace=false; clearBitmap(); }
    private void update(CreationImportState value, String text) { state=value; message=text; overlay.invalidate(); }
    private void clearBitmap() { if (capturedBitmap != null) capturedBitmap.recycle(); capturedBitmap=null; }
    private void exit() { stop(); close.run(); }

    @Override public boolean onInput(UiInputIntent intent) {
        if (intent == UiInputIntent.BACK) { exit(); return true; }
        if (intent == UiInputIntent.ACTIVATE && state == CreationImportState.LIVE) { capture.capture(); return true; }
        if (intent == UiInputIntent.ACTIVATE && state == CreationImportState.REVIEW) { confirm(); return true; }
        return true;
    }

    @Override public void close() { stop(); decodeWorker.shutdownNow(); }

    private final class Overlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final FluidOrb orb = new FluidOrb();
        Overlay(Activity context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.scale(getWidth()/480f, getHeight()/640f);
            if (capturedBitmap != null && state != CreationImportState.LIVE && state != CreationImportState.POSITIONING)
                canvas.drawBitmap(capturedBitmap, null, new RectF(0,0,480,640), paint);
            boolean busy = state == CreationImportState.POSITIONING || state == CreationImportState.DECODING
                    || state == CreationImportState.PREFLIGHT || state == CreationImportState.INSTALLING;
            // Soft scrims keep the preview visible while giving the chrome a dark base.
            paint.setShader(new LinearGradient(0,0,0,104,SamTheme.withAlpha(SamTheme.BACKGROUND,225),
                    SamTheme.withAlpha(SamTheme.BACKGROUND,0),Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,480,104,paint);
            paint.setShader(new LinearGradient(0,460,0,640,SamTheme.withAlpha(SamTheme.BACKGROUND,0),
                    SamTheme.withAlpha(SamTheme.BACKGROUND,235),Shader.TileMode.CLAMP));
            canvas.drawRect(0,460,480,640,paint); paint.setShader(null);
            SamTheme.text(canvas,paint,"Import Creation",24,54,24,SamTheme.INK,Paint.Align.LEFT,true);
            pill(canvas,new RectF(352,28,456,72),"Cancel",false);
            if (state == CreationImportState.REVIEW) drawReview(canvas);
            else if (state == CreationImportState.SUCCESS) drawSuccess(canvas);
            else {
                boolean error = state == CreationImportState.ERROR;
                if (state == CreationImportState.LIVE) viewfinder(canvas,new RectF(70,130,410,470));
                SamTheme.text(canvas,paint,message,240,524,19,error?SamTheme.RED:SamTheme.INK,Paint.Align.CENTER,true);
                if (state == CreationImportState.LIVE) shutter(canvas,240,584);
                else if (error) pill(canvas,new RectF(150,552,330,608),"Try again",true);
                else if (busy) {
                    orb.setEnergy(0.5f).setSpeed(1.4f);
                    orb.draw(canvas,240,584,20);
                }
            }
            canvas.restore();
            if (busy && isShown()) postInvalidateDelayed(33L);
        }
        private void drawReview(Canvas canvas) {
            RectF panel=new RectF(18,314,462,624);
            paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND,236)); canvas.drawRoundRect(panel,24,24,paint);
            SamTheme.glass(canvas,paint,panel,24,false);
            int tag = replace ? SamTheme.AMBER : SamTheme.ORB_PALE;
            paint.setColor(tag); canvas.drawCircle(46,348,4,paint);
            SamTheme.text(canvas,paint,replace?"Replaces existing Creation":"New Creation",58,353,15,tag,Paint.Align.LEFT,true);
            paint.setTextSize(28); paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            String shown = ellipsize(title,396);
            SamTheme.text(canvas,paint,shown,42,398,28,SamTheme.INK,Paint.Align.LEFT,true);
            String detail = description.isBlank()?message:description;
            paint.setTextSize(17); paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            SamTheme.text(canvas,paint,ellipsize(detail,396),42,436,17,SamTheme.MUTED,Paint.Align.LEFT,false);
            pill(canvas,new RectF(30,540,176,604),"Retake",false);
            primary(canvas,new RectF(192,540,450,604),replace?"Replace":"Import",replace?SamTheme.AMBER:SamTheme.ORB_BLUE);
        }
        private void drawSuccess(Canvas canvas) {
            RectF panel=new RectF(18,404,462,624);
            paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND,236)); canvas.drawRoundRect(panel,24,24,paint);
            SamTheme.glass(canvas,paint,panel,24,false);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_BLUE,80)); canvas.drawCircle(240,452,22,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(SamTheme.INK);
            canvas.drawLine(230,452,237,460,paint); canvas.drawLine(237,460,251,444,paint);
            paint.setStrokeCap(Paint.Cap.BUTT); paint.setStyle(Paint.Style.FILL);
            SamTheme.text(canvas,paint,"Creation added",240,506,24,SamTheme.INK,Paint.Align.CENTER,true);
            SamTheme.text(canvas,paint,"Find it in Cards",240,532,16,SamTheme.MUTED,Paint.Align.CENTER,false);
            primary(canvas,new RectF(130,550,350,606),"Done",SamTheme.ORB_BLUE);
        }
        private String ellipsize(String value,float width){
            if(value==null)return ""; if(paint.measureText(value)<=width)return value;
            int count=paint.breakText(value,true,width-paint.measureText("…"),null);
            return value.substring(0,Math.max(0,count)).trim()+"…";
        }
        private void pill(Canvas canvas,RectF rect,String label,boolean selected){
            paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND,150)); canvas.drawRoundRect(rect,rect.height()/2,rect.height()/2,paint);
            SamTheme.glass(canvas,paint,rect,rect.height()/2,selected);
            SamTheme.text(canvas,paint,label,rect.centerX(),rect.centerY()+6,17,SamTheme.INK,Paint.Align.CENTER,true);
        }
        private void primary(Canvas canvas,RectF rect,String label,int color){
            paint.setShader(new LinearGradient(0,rect.top,0,rect.bottom,SamTheme.withAlpha(color,240),SamTheme.withAlpha(color,195),Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect,rect.height()/2,rect.height()/2,paint); paint.setShader(null);
            SamTheme.text(canvas,paint,label,rect.centerX(),rect.centerY()+7,20,color==SamTheme.AMBER?SamTheme.BACKGROUND:SamTheme.INK,Paint.Align.CENTER,true);
        }
        private void shutter(Canvas canvas,float x,float y){
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3.5f); paint.setColor(SamTheme.INK);
            canvas.drawCircle(x,y,34,paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(SamTheme.withAlpha(SamTheme.INK,235)); canvas.drawCircle(x,y,27,paint);
        }
        private void viewfinder(Canvas canvas,RectF r){
            float arm=38;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(SamTheme.ORB_PALE);
            canvas.drawLine(r.left,r.top+arm,r.left,r.top,paint); canvas.drawLine(r.left,r.top,r.left+arm,r.top,paint);
            canvas.drawLine(r.right-arm,r.top,r.right,r.top,paint); canvas.drawLine(r.right,r.top,r.right,r.top+arm,paint);
            canvas.drawLine(r.left,r.bottom-arm,r.left,r.bottom,paint); canvas.drawLine(r.left,r.bottom,r.left+arm,r.bottom,paint);
            canvas.drawLine(r.right-arm,r.bottom,r.right,r.bottom,paint); canvas.drawLine(r.right,r.bottom,r.right,r.bottom-arm,paint);
            paint.setStrokeCap(Paint.Cap.BUTT); paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(event.getActionMasked()!=MotionEvent.ACTION_UP)return true;
            float x=event.getX()*480f/Math.max(1,getWidth()), y=event.getY()*640f/Math.max(1,getHeight());
            if(y<88&&x>350){exit();return true;}
            if(state==CreationImportState.LIVE&&y>510){capture.capture();return true;}
            if(state==CreationImportState.ERROR&&y>510){retake();return true;}
            if(state==CreationImportState.REVIEW&&y>520){if(x<180)retake();else confirm();return true;}
            if(state==CreationImportState.SUCCESS&&y>500){exit();return true;}
            return true;
        }
    }
}
