package com.resonolabs.feature.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.resonolabs.feature.voice.VoiceSessionHandoff;
import com.resonolabs.hardware.motor.MotorController;
import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;

/** Full-screen capture/review/send composition. Voice and provider transport remain external owners. */
public final class CameraHandoffPage extends FrameLayout implements AutoCloseable {
    private enum State { POSITIONING, OPENING, LIVE, REVIEW, SENDING, ERROR }
    private static final int CAMERA_PERMISSION_REQUEST = 2402;
    private final Activity activity;
    private final MotorController motor;
    private final VoiceSessionHandoff voice;
    private final Runnable returnToVoice;
    private final TextureView preview;
    private final Controls controls;
    private Camera2Producer producer;
    private CapturedImage captured;
    private Bitmap reviewBitmap;
    private boolean handoffMode;
    private boolean returnHomePending;
    private MotorController.Position activePosition = MotorController.Position.OUTWARD;
    private MotorController.Position positionAfterClose;
    private State state = State.POSITIONING;
    private String message = "Positioning camera";

    public CameraHandoffPage(Activity activity, MotorController motor, VoiceSessionHandoff voice,
                             Runnable returnToVoice) {
        super(activity);
        this.activity = activity; this.motor = motor; this.voice = voice; this.returnToVoice = returnToVoice;
        setBackgroundColor(Color.BLACK);
        preview = new TextureView(activity);
        controls = new Controls(activity);
        addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setContentDescription("Hand a photo to the current Voice session");
    }

    public void startPreview() { start(false); }
    public void startHandoff() { start(true); }

    private void start(boolean directHandoff) {
        handoffMode = directHandoff;
        activePosition = MotorController.Position.OUTWARD;
        clearReview();
        if (handoffMode && !voice.isAvailable()) { fail("Voice session ended"); return; }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            fail("Camera permission required");
            return;
        }
        state = State.POSITIONING; message = "Positioning camera"; controls.invalidate();
        moveThenOpen(activePosition);
    }

    private void moveThenOpen(MotorController.Position requested) {
        motor.moveTo(requested, (motorState, position) -> activity.runOnUiThread(() -> {
            if (!isShown()) return;
            if (motorState == MotorController.State.AT_POSITION && position == requested) openCamera();
            else if (motorState == MotorController.State.UNAVAILABLE) fail("Motor unavailable");
            else if (motorState == MotorController.State.FAILED) fail("Motor failed");
        }));
    }

    private void openCamera() {
        state = State.OPENING; message = "Opening camera"; controls.invalidate();
        producer = new Camera2Producer(activity, preview, new Camera2Producer.Listener() {
            @Override public void onPreviewLive() { state=State.LIVE; message=""; controls.invalidate(); }
            @Override public void onCaptured(CapturedImage image) { showReview(image); }
            @Override public void onFailure(String code) { fail(messageFor(code)); }
            @Override public void onCameraClosed() {
                if (returnHomePending) {
                    returnHomePending = false;
                    motor.returnHome();
                } else if (positionAfterClose != null) {
                    MotorController.Position requested = positionAfterClose;
                    positionAfterClose = null;
                    moveThenOpen(requested);
                }
            }
        });
        producer.open(activePosition);
    }

    private void switchFacing(MotorController.Position requested) {
        if (handoffMode || requested == activePosition || state != State.LIVE || producer == null) return;
        activePosition = requested;
        positionAfterClose = requested;
        state = State.POSITIONING;
        message = requested == MotorController.Position.OUTWARD ? "Turning outward" : "Turning toward you";
        controls.invalidate();
        producer.stopCamera();
    }

    private void capture() { if (state == State.LIVE && producer != null) producer.capture(); }
    private void showReview(CapturedImage image) {
        captured = image;
        reviewBitmap = BitmapFactory.decodeByteArray(image.bytes(), 0, image.bytes().length);
        if (producer != null) producer.stopCamera();
        state = State.REVIEW; message = "Review photo"; controls.invalidate();
    }
    private void retake() { clearReview(); openCamera(); }

    private void send() {
        if (state != State.REVIEW || captured == null || !voice.isAvailable()) { fail("Voice session ended"); return; }
        state=State.SENDING; message="Sending image..."; controls.invalidate();
        try {
            byte[] realtimeImage = RealtimeImageEncoder.encode(captured.bytes());
            if (voice.submitImage(realtimeImage, captured.mimeType(), captured.filename())) cancel();
            else fail("Image could not be sent");
        } catch (IllegalArgumentException error) {
            fail(error.getMessage());
        }
    }

    private void cancel() { stop(); returnToVoice.run(); }
    public void stop() {
        Camera2Producer closingProducer = producer;
        producer=null;
        returnHomePending = true;
        positionAfterClose = null;
        if (closingProducer != null) closingProducer.close();
        else { returnHomePending = false; motor.returnHome(); }
        clearReview();
    }
    private void clearReview() { captured=null; if (reviewBitmap != null) reviewBitmap.recycle(); reviewBitmap=null; }
    private void fail(String value) {
        state=State.ERROR; message=value; controls.invalidate();
        if (producer != null) {
            returnHomePending = true;
            producer.stopCamera();
        } else motor.returnHome();
    }
    private static String messageFor(String code) { return switch(code) { case "preview_unavailable" -> "Preview unavailable"; case "capture_failed" -> "Capture failed"; case "camera_disconnected" -> "Camera disconnected"; default -> "Camera unavailable"; }; }
    @Override public void close() { stop(); }

    private final class Controls extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final FluidOrb orb = new FluidOrb();
        Controls(android.content.Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.scale(getWidth()/480f, getHeight()/640f);
            if (state == State.REVIEW && reviewBitmap != null) canvas.drawBitmap(reviewBitmap, null, new RectF(0,0,480,640), paint);
            boolean busy = state == State.POSITIONING || state == State.OPENING || state == State.SENDING;
            paint.setShader(new LinearGradient(0,0,0,104,SamTheme.withAlpha(SamTheme.BACKGROUND,220),
                    SamTheme.withAlpha(SamTheme.BACKGROUND,0),Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,480,104,paint);
            paint.setShader(new LinearGradient(0,480,0,640,SamTheme.withAlpha(SamTheme.BACKGROUND,0),
                    SamTheme.withAlpha(SamTheme.BACKGROUND,230),Shader.TileMode.CLAMP));
            canvas.drawRect(0,480,480,640,paint); paint.setShader(null);
            SamTheme.text(canvas,paint,handoffMode ? "Send a photo" : "Camera",24,54,24,SamTheme.INK,Paint.Align.LEFT,true);
            pill(canvas,new RectF(352,28,456,72),handoffMode ? "Cancel" : "Back",false);
            if (state == State.LIVE && handoffMode) drawShutter(canvas,240,580);
            else if (state == State.LIVE) {
                RectF track=new RectF(30,546,450,608);
                paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND,150)); canvas.drawRoundRect(track,31,31,paint);
                SamTheme.glass(canvas,paint,track,31,false);
                drawFacingButton(canvas,new RectF(36,552,238,602),"Toward you",activePosition==MotorController.Position.INWARD);
                drawFacingButton(canvas,new RectF(242,552,444,602),"Outward",activePosition==MotorController.Position.OUTWARD);
            }
            else if (state == State.REVIEW) {
                pill(canvas,new RectF(30,546,176,608),"Retake",false);
                RectF send=new RectF(192,546,450,608);
                paint.setShader(new LinearGradient(0,send.top,0,send.bottom,SamTheme.withAlpha(SamTheme.ORB_BLUE,240),
                        SamTheme.withAlpha(SamTheme.ORB_BLUE,195),Shader.TileMode.CLAMP));
                canvas.drawRoundRect(send,31,31,paint); paint.setShader(null);
                SamTheme.text(canvas,paint,"Send to Voice",send.centerX(),send.centerY()+7,20,SamTheme.INK,Paint.Align.CENTER,true);
            }
            else if (!message.isEmpty()) {
                if (busy) { orb.setEnergy(0.5f).setSpeed(1.4f); orb.draw(canvas,240,536,18); }
                SamTheme.text(canvas,paint,message,240,594,19,state==State.ERROR?SamTheme.RED:SamTheme.INK,Paint.Align.CENTER,true);
            }
            canvas.restore();
            if (busy && isShown()) postInvalidateDelayed(33L);
        }
        private void pill(Canvas canvas,RectF rect,String label,boolean selected){
            paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND,150)); canvas.drawRoundRect(rect,rect.height()/2,rect.height()/2,paint);
            SamTheme.glass(canvas,paint,rect,rect.height()/2,selected);
            SamTheme.text(canvas,paint,label,rect.centerX(),rect.centerY()+6,17,SamTheme.INK,Paint.Align.CENTER,true);
        }
        private void drawShutter(Canvas canvas,float x,float y){
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3.5f); paint.setColor(SamTheme.INK);
            canvas.drawCircle(x,y,36,paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(SamTheme.withAlpha(SamTheme.INK,235)); canvas.drawCircle(x,y,29,paint);
        }
        private void drawFacingButton(Canvas canvas,RectF rect,String label,boolean selected){
            if (selected) {
                paint.setColor(SamTheme.withAlpha(SamTheme.ORB_BLUE,200));
                canvas.drawRoundRect(rect,rect.height()/2,rect.height()/2,paint);
            }
            SamTheme.text(canvas,paint,label,rect.centerX(),rect.centerY()+6,17,selected?SamTheme.INK:SamTheme.MUTED,Paint.Align.CENTER,true);
        }
        @Override public boolean onTouchEvent(MotionEvent event){ if(event.getActionMasked()!=MotionEvent.ACTION_UP)return true;float x=event.getX()*480f/Math.max(1,getWidth()),y=event.getY()*640f/Math.max(1,getHeight());if(y<90&&x>350)cancel();else if(!handoffMode&&state==State.LIVE&&y>525){switchFacing(x<240?MotorController.Position.INWARD:MotorController.Position.OUTWARD);}else if(handoffMode&&state==State.LIVE&&y>515)capture();else if(handoffMode&&state==State.REVIEW&&y>515){if(x<180)retake();else send();}return true; }
    }
}
