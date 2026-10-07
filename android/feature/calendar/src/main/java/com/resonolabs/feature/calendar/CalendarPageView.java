package com.resonolabs.feature.calendar;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import com.resonolabs.runtime.host.CalendarEventClient;
import com.resonolabs.ui.design.ReSonoTheme;
import com.resonolabs.ui.input.UiInputIntent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 480x640 donor-proven upcoming Calendar list/detail projection. */
public final class CalendarPageView extends View implements AutoCloseable {
    private static final float W=480f,H=640f;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CalendarEventClient client=new CalendarEventClient();
    private final Runnable closeCalendar;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable openVoice;
    private JSONArray events=new JSONArray();
    private int selected; private boolean detail; private float downX,downY,detailScroll,maxDetailScroll,manualPan;
    private long focusAt=System.currentTimeMillis();

    public CalendarPageView(Activity activity,Runnable openVoice,Runnable closeCalendar){super(activity);this.openVoice=openVoice;this.closeCalendar=closeCalendar;setFocusable(true);setContentDescription("Upcoming calendar events");}
    public void start(){handler.removeCallbacks(refresh);handler.post(refresh);}
    public void stop(){handler.removeCallbacks(refresh);}
    private final Runnable refresh=new Runnable(){@Override public void run(){client.loadUpcoming(getContext(),new CalendarEventClient.Callback(){public void onEvents(JSONObject value){events=value.optJSONArray("events");if(events==null)events=new JSONArray();selected=Math.min(selected,Math.max(0,events.length()-1));invalidate();}public void onFailure(){}});handler.postDelayed(this,5000);}};

    public boolean onInput(UiInputIntent input){if(input==UiInputIntent.BACK){if(detail){detail=false;detailScroll=0;invalidate();return true;}return false;}if(detail){if(input==UiInputIntent.NEXT)detailScroll=Math.min(maxDetailScroll,detailScroll+45);else if(input==UiInputIntent.PREVIOUS)detailScroll=Math.max(0,detailScroll-45);else if(input==UiInputIntent.ACTIVATE&&current().optBoolean("editable"))openVoice.run();invalidate();return true;}if(input==UiInputIntent.NEXT)move(1);else if(input==UiInputIntent.PREVIOUS)move(-1);else if(input==UiInputIntent.ACTIVATE)openDetail();else return false;return true;}
    private void move(int delta){if(events.length()==0)return;selected=(selected+delta+events.length())%events.length();focusAt=System.currentTimeMillis();manualPan=0;invalidate();}
    private void openDetail(){if(events.length()==0)return;detail=true;detailScroll=0;invalidate();}
    private JSONObject current(){JSONObject value=events.optJSONObject(selected);return value==null?new JSONObject():value;}

    @Override public boolean onTouchEvent(MotionEvent event){float x=event.getX()*W/Math.max(1,getWidth()),y=event.getY()*H/Math.max(1,getHeight());if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=x;downY=y;return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=x-downX,dy=y-downY;if(detail&&Math.abs(dy)>Math.abs(dx)){detailScroll=Math.max(0,Math.min(maxDetailScroll,detailScroll-dy));downY=y;invalidate();}else if(!detail&&downX>=88&&Math.abs(dx)>Math.abs(dy)){manualPan=Math.max(0,manualPan-dx);downX=x;invalidate();}return true;}if(event.getActionMasked()!=MotionEvent.ACTION_UP)return true;if(y<82){if(detail){detail=false;detailScroll=0;invalidate();}else closeCalendar.run();return true;}if(detail){if(y>=528&&current().optBoolean("editable"))openVoice.run();invalidate();return true;}if(y>=92&&y<572&&events.length()>0){int row=(int)((y-92)/96);int next=Math.min(events.length()-1,(selected/5)*5+row);if(next==selected)openDetail();else{selected=next;focusAt=System.currentTimeMillis();manualPan=0;invalidate();}}return true;}

    @Override protected void onDraw(Canvas canvas){canvas.save();canvas.scale(getWidth()/W,getHeight()/H);ReSonoTheme.background(canvas,paint,W,H,430,30,260,ReSonoTheme.ORB_BLUE);header(canvas);if(detail)drawDetail(canvas);else drawList(canvas);canvas.restore();}

    private void header(Canvas c){
        ReSonoTheme.text(c,paint,"Calendar",64,52,30,ReSonoTheme.INK,Paint.Align.LEFT,true);
        ReSonoTheme.text(c,paint,detail?"Event details":(events.length()==0?"Upcoming":events.length()+(events.length()==1?" upcoming event":" upcoming events")),65,76,15,ReSonoTheme.MUTED,Paint.Align.LEFT,false);
        orbDot(c,436,44,13);
    }

    /** Static, cheap orb glyph: white crown fading to orb blue. */
    private void orbDot(Canvas c,float cx,float cy,float r){
        paint.setShader(new android.graphics.RadialGradient(cx,cy+r*0.3f,r*2.4f,ReSonoTheme.withAlpha(ReSonoTheme.ORB_BLUE,70),ReSonoTheme.withAlpha(ReSonoTheme.ORB_BLUE,0),android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy+r*0.3f,r*2.4f,paint);
        paint.setShader(new android.graphics.LinearGradient(cx,cy-r,cx,cy+r,new int[]{0xffffffff,ReSonoTheme.ORB_PALE,ReSonoTheme.ORB_BLUE},new float[]{0.15f,0.5f,0.9f},android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,r,paint);paint.setShader(null);
    }

    private void drawList(Canvas c){
        if(events.length()==0){
            orbDot(c,240,262,34);
            ReSonoTheme.text(c,paint,"No upcoming events",240,352,26,ReSonoTheme.INK,Paint.Align.CENTER,true);
            ReSonoTheme.text(c,paint,"Your calendar is clear.",240,384,18,ReSonoTheme.MUTED,Paint.Align.CENTER,false);
            return;
        }
        int pageStart=(selected/5)*5;
        for(int row=0;row<5&&pageStart+row<events.length();row++){
            int i=pageStart+row;JSONObject item=events.optJSONObject(i);if(item==null)continue;
            float top=92+row*96;boolean focused=i==selected;
            RectF rect=new RectF(18,top,462,top+84);
            ReSonoTheme.glass(c,paint,rect,20,focused);
            dateBadge(c,item.optString("startsAt"),54,top+42,focused);
            drawLine(c,item.optString("title","Untitled event"),96,top+38,24,444,focused,28,true);
            String secondary=timeOnly(item.optString("startsAt"));
            String location=item.optString("location","");
            if(!location.isBlank())secondary=secondary.isEmpty()?location:secondary+" · "+location;
            drawLine(c,secondary,96,top+64,17,444,focused,34,false);
        }
        if(events.length()>1)ReSonoTheme.text(c,paint,(selected+1)+" of "+events.length(),456,610,16,ReSonoTheme.MUTED,Paint.Align.RIGHT,false);
    }

    private void dateBadge(Canvas c,String startsAt,float cx,float cy,boolean focused){
        String month="",day="";
        try{OffsetDateTime t=OffsetDateTime.parse(startsAt);month=t.format(DateTimeFormatter.ofPattern("MMM"));day=t.format(DateTimeFormatter.ofPattern("d"));}catch(Exception ignored){}
        paint.setStyle(Paint.Style.FILL);paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.ORB_BLUE,focused?70:38));
        c.drawRoundRect(new RectF(cx-24,cy-28,cx+24,cy+28),14,14,paint);
        if(day.isEmpty()){paint.setColor(ReSonoTheme.ORB_PALE);c.drawCircle(cx,cy,5,paint);return;}
        ReSonoTheme.text(c,paint,month,cx,cy-8,13,ReSonoTheme.ORB_PALE,Paint.Align.CENTER,true);
        ReSonoTheme.text(c,paint,day,cx,cy+18,24,ReSonoTheme.INK,Paint.Align.CENTER,true);
    }

    private void drawLine(Canvas c,String text,float x,float baseline,float size,float right,boolean focused,float msPerPixel,boolean primary){paint.setTextSize(size);paint.setTypeface(android.graphics.Typeface.create(primary?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));float overflow=Math.max(0,paint.measureText(text)-(right-x));float offset=Math.min(manualPan,overflow);if(focused&&overflow>0&&manualPan==0){long elapsed=System.currentTimeMillis()-focusAt;float distance=overflow+18;long travel=Math.max(700,(long)(distance*msPerPixel));long cycle=900+travel+900;if(elapsed%cycle>900)offset=Math.min(distance,(elapsed%cycle-900)*distance/travel);postInvalidateDelayed(33);}c.save();c.clipRect(x,baseline-size-4,right,baseline+7);ReSonoTheme.text(c,paint,text,x-offset,baseline,size,primary?(focused?ReSonoTheme.INK:ReSonoTheme.withAlpha(ReSonoTheme.INK,215)):ReSonoTheme.MUTED,Paint.Align.LEFT,primary);c.restore();}

    private void drawDetail(Canvas c){
        JSONObject item=current();boolean editable=item.optBoolean("editable");float contentBottom=editable?510:560;
        ReSonoTheme.glass(c,paint,new RectF(18,88,462,622),24,false);
        c.save();c.clipRect(30,102,450,contentBottom);
        float y=140-detailScroll;
        y=wrapped(c,item.optString("title","Untitled event"),38,y,28,36,400,ReSonoTheme.INK,true)+14;
        y=field(c,"Starts",friendly(item.optString("startsAt")),y);
        y=field(c,"Ends",friendly(item.optString("endsAt")),y);
        y=field(c,"Location",item.optString("location"),y);
        y=field(c,"Calendar",item.optString("calendar"),y);
        y=field(c,"Organizer",item.optString("organizer"),y);
        y=field(c,"Notes",item.optString("description"),y);
        maxDetailScroll=Math.max(0,y+detailScroll-contentBottom+24);detailScroll=Math.min(detailScroll,maxDetailScroll);
        c.restore();
        if(maxDetailScroll>0){
            float trackTop=110,trackBottom=contentBottom-8,track=trackBottom-trackTop;
            float visible=contentBottom-102;float thumb=Math.max(28,track*visible/(visible+maxDetailScroll));
            float thumbTop=trackTop+(track-thumb)*(detailScroll/maxDetailScroll);
            paint.setColor(ReSonoTheme.LINE);c.drawRoundRect(new RectF(449,trackTop,453,trackBottom),2,2,paint);
            paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.ORB_PALE,170));c.drawRoundRect(new RectF(449,thumbTop,453,thumbTop+thumb),2,2,paint);
        }
        if(editable){
            RectF button=new RectF(30,528,450,608);
            paint.setShader(new android.graphics.LinearGradient(0,528,0,608,ReSonoTheme.withAlpha(ReSonoTheme.ORB_BLUE,235),ReSonoTheme.withAlpha(ReSonoTheme.ORB_BLUE,190),android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(button,24,24,paint);paint.setShader(null);
            ReSonoTheme.text(c,paint,"Edit with voice",240,575,21,ReSonoTheme.INK,Paint.Align.CENTER,true);
        }else{
            ReSonoTheme.text(c,paint,"Read only",240,596,16,ReSonoTheme.MUTED,Paint.Align.CENTER,false);
        }
    }
    private float field(Canvas c,String label,String value,float y){if(value==null||value.isBlank())return y;paint.setColor(ReSonoTheme.LINE);c.drawRect(38,y-24,442,y-23,paint);ReSonoTheme.text(c,paint,label,38,y,15,ReSonoTheme.ORB_PALE,Paint.Align.LEFT,true);return wrapped(c,value,38,y+30,21,28,400,ReSonoTheme.INK,false)+16;}
    private float wrapped(Canvas c,String value,float x,float y,float size,float line,float width,int color,boolean bold){paint.setTextSize(size);paint.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));String rest=value.trim();while(!rest.isEmpty()){int count=paint.breakText(rest,true,width,null);if(count<rest.length()){int space=rest.lastIndexOf(' ',Math.max(0,count-1));if(space>0)count=space;}String part=rest.substring(0,Math.max(1,count)).trim();ReSonoTheme.text(c,paint,part,x,y,size,color,Paint.Align.LEFT,bold);rest=rest.substring(Math.min(rest.length(),Math.max(1,count))).trim();y+=line;}return y;}
    private static String timeOnly(String value){if(value==null||value.isBlank())return "";try{return OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("EEE · h:mm a"));}catch(Exception ignored){return value;}}
    private static String friendly(String value){if(value==null||value.isBlank())return "";try{return OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"));}catch(Exception ignored){return value;}}
    @Override public void close(){stop();client.close();}
}
