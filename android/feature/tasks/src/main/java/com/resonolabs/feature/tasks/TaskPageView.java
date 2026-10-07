package com.resonolabs.feature.tasks;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import com.resonolabs.runtime.host.TaskClient;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native 480x640 active Tasks list/detail projection. */
public final class TaskPageView extends View implements AutoCloseable {
    private static final float W = 480f, H = 640f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TaskClient client = new TaskClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openVoice;
    private JSONArray tasks = new JSONArray();
    private int selected;
    private boolean detail;
    private float downX, downY, manualPan;
    private long focusAt = System.currentTimeMillis();

    public TaskPageView(Activity activity, Runnable openVoice) {
        super(activity); this.openVoice = openVoice; setFocusable(true);
        setContentDescription("Active tasks");
    }
    public void start() { handler.removeCallbacks(refresh); handler.post(refresh); }
    public void stop() { handler.removeCallbacks(refresh); }
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            client.loadActive(getContext(), new TaskClient.Callback() {
                @Override public void onTasks(JSONObject value) {
                    tasks = value.optJSONArray("tasks");
                    if (tasks == null) tasks = new JSONArray();
                    selected = Math.min(selected, Math.max(0, tasks.length() - 1));
                    if (tasks.length() == 0) detail = false;
                    invalidate();
                }
                @Override public void onFailure() {}
            });
            handler.postDelayed(this, 2000);
        }
    };

    public boolean onInput(UiInputIntent input) {
        if (input == UiInputIntent.BACK) {
            if (detail) { detail = false; invalidate(); return true; }
            return false;
        }
        if (detail) { if (input == UiInputIntent.ACTIVATE) openVoice.run(); return true; }
        if (input == UiInputIntent.NEXT) move(1);
        else if (input == UiInputIntent.PREVIOUS) move(-1);
        else if (input == UiInputIntent.ACTIVATE && tasks.length() > 0) { detail = true; invalidate(); }
        else return false;
        return true;
    }

    private void move(int delta) {
        if (tasks.length() == 0) return;
        selected = (selected + delta + tasks.length()) % tasks.length();
        focusAt = System.currentTimeMillis(); manualPan = 0; invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * W / Math.max(1, getWidth());
        float y = event.getY() * H / Math.max(1, getHeight());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = x; downY = y; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !detail && Math.abs(x-downX) > Math.abs(y-downY)) {
            manualPan = Math.max(0, manualPan-(x-downX)); downX=x; invalidate(); return true;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (detail) { if (y < 82) detail=false; else if (y >= 520) openVoice.run(); invalidate(); return true; }
        if (y >= 92 && y < 572 && tasks.length() > 0) {
            int next = Math.min(tasks.length()-1, (selected/5)*5+(int)((y-92)/96));
            if (next == selected) detail=true;
            else { selected=next; focusAt=System.currentTimeMillis(); manualPan=0; }
            invalidate();
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save(); canvas.scale(getWidth()/W, getHeight()/H);
        SamTheme.background(canvas, paint, W, H, 430, 30, 260, SamTheme.ORB_BLUE);
        header(canvas);
        if (detail) drawDetail(canvas); else drawList(canvas); canvas.restore();
    }

    private void header(Canvas c) {
        SamTheme.text(c,paint,"Tasks",64,52,30,SamTheme.INK,Paint.Align.LEFT,true);
        String sub = detail ? "Task details" : tasks.length()==0 ? "All clear"
                : tasks.length() + (tasks.length()==1 ? " open task" : " open tasks");
        SamTheme.text(c,paint,sub,65,76,15,SamTheme.MUTED,Paint.Align.LEFT,false);
        orbDot(c,436,44,13);
    }

    /** Static, cheap orb glyph: white crown fading to orb blue. */
    private void orbDot(Canvas c, float cx, float cy, float r) {
        paint.setShader(new RadialGradient(cx, cy + r*0.3f, r*2.4f,
                SamTheme.withAlpha(SamTheme.ORB_BLUE,70), SamTheme.withAlpha(SamTheme.ORB_BLUE,0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + r*0.3f, r*2.4f, paint);
        paint.setShader(new LinearGradient(cx, cy-r, cx, cy+r,
                new int[]{0xffffffff, SamTheme.ORB_PALE, SamTheme.ORB_BLUE}, new float[]{0.15f,0.5f,0.9f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, paint); paint.setShader(null);
    }

    private void drawList(Canvas c) {
        if (tasks.length()==0) {
            orbDot(c,240,262,34);
            SamTheme.text(c,paint,"No open tasks",240,352,26,SamTheme.INK,Paint.Align.CENTER,true);
            SamTheme.text(c,paint,"Ask Voice to add one.",240,384,18,SamTheme.MUTED,Paint.Align.CENTER,false); return;
        }
        int start=(selected/5)*5;
        for (int row=0; row<5 && start+row<tasks.length(); row++) {
            int i=start+row; JSONObject item=tasks.optJSONObject(i); if (item==null) continue;
            float top=92+row*96; boolean focused=i==selected;
            SamTheme.glass(c,paint,new RectF(18,top,462,top+84),20,focused);
            // Open checkbox ring.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f);
            paint.setColor(focused ? SamTheme.ORB_PALE : SamTheme.withAlpha(SamTheme.MUTED,170));
            c.drawCircle(54, top+42, 13, paint); paint.setStyle(Paint.Style.FILL);
            line(c,item.optString("text","Task"),86,top+51,24,444,focused);
        }
        if (tasks.length()>1)
            SamTheme.text(c,paint,(selected+1)+" of "+tasks.length(),456,610,16,SamTheme.MUTED,Paint.Align.RIGHT,false);
    }

    private void line(Canvas c,String text,float x,float baseline,float size,float right,boolean focused) {
        paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float overflow=Math.max(0,paint.measureText(text)-(right-x));
        float offset=Math.min(manualPan,overflow);
        if (focused && overflow>0 && manualPan==0) {
            long elapsed=System.currentTimeMillis()-focusAt; float distance=overflow+18;
            long travel=Math.max(700,(long)(distance*28)); long position=elapsed%(1800+travel);
            if (position>900) offset=Math.min(distance,(position-900)*distance/travel); postInvalidateDelayed(33);
        }
        c.save(); c.clipRect(x,baseline-size-4,right,baseline+7);
        SamTheme.text(c,paint,text,x-offset,baseline,size,focused?SamTheme.INK:SamTheme.withAlpha(SamTheme.INK,200),Paint.Align.LEFT,true); c.restore();
    }

    private void drawDetail(Canvas c) {
        JSONObject item=tasks.optJSONObject(selected); if(item==null)return;
        SamTheme.glass(c,paint,new RectF(18,95,462,500),24,false);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f); paint.setColor(SamTheme.ORB_PALE);
        c.drawCircle(52,132,12,paint); paint.setStyle(Paint.Style.FILL);
        SamTheme.text(c,paint,"Open",74,138,16,SamTheme.ORB_PALE,Paint.Align.LEFT,true);
        wrapped(c,item.optString("text","Task"),38,196,28,38,404);
        RectF button=new RectF(18,528,462,592);
        paint.setShader(new LinearGradient(0,528,0,592,SamTheme.withAlpha(SamTheme.ORB_BLUE,235),
                SamTheme.withAlpha(SamTheme.ORB_BLUE,190),Shader.TileMode.CLAMP));
        c.drawRoundRect(button,24,24,paint); paint.setShader(null);
        SamTheme.text(c,paint,"Edit with voice",240,568,21,SamTheme.INK,Paint.Align.CENTER,true);
    }

    private void wrapped(Canvas c,String value,float x,float y,float size,float line,float width) {
        paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); String rest=value.trim();
        while(!rest.isEmpty()&&y<475){int count=paint.breakText(rest,true,width,null);
            if(count<rest.length()){int space=rest.lastIndexOf(' ',Math.max(0,count-1));if(space>0)count=space;}
            SamTheme.text(c,paint,rest.substring(0,Math.max(1,count)).trim(),x,y,size,SamTheme.INK,Paint.Align.LEFT,true);
            rest=rest.substring(Math.min(rest.length(),Math.max(1,count))).trim();y+=line;}
    }
    @Override public void close(){stop();client.close();}
}
