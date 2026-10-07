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
import com.resonolabs.ui.design.SamTheme;
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
    /** Debug fixture (Cards board fake mode) instead of the runtime. */
    private JSONObject fixture;
    /** Opened from a board row: keep this event's detail; BACK returns to the caller. */
    private String pinnedEventId; private boolean detailOnly;

    public CalendarPageView(Activity activity,Runnable openVoice,Runnable closeCalendar){super(activity);this.openVoice=openVoice;this.closeCalendar=closeCalendar;setFocusable(true);setContentDescription("Upcoming calendar events");}
    public void start(){handler.removeCallbacks(refresh);handler.post(refresh);}
    public void stop(){handler.removeCallbacks(refresh);}
    private final Runnable refresh=new Runnable(){@Override public void run(){if(fixture!=null)apply(fixture);else client.loadUpcoming(getContext(),new CalendarEventClient.Callback(){public void onEvents(JSONObject value){apply(value);}public void onFailure(){}});handler.postDelayed(this,5000);}};
    private void apply(JSONObject value){JSONArray next=notOver(value.optJSONArray("events"));if(pinnedEventId!=null){int found=indexOf(next,pinnedEventId);if(found<0)return;selected=found;}events=next;selected=Math.min(selected,Math.max(0,events.length()-1));invalidate();}
    /** The runtime keeps all-day events up to 14 h past their stored UTC end (for zones behind UTC); drop the ones whose date is over here. */
    private static JSONArray notOver(JSONArray list){JSONArray out=new JSONArray();if(list==null)return out;java.time.LocalDate today=java.time.LocalDate.now();for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);if(item==null)continue;if(item.optBoolean("allDay")&&CalendarDates.allDayOver(item.optString("startsAt"),item.optString("endsAt"),today))continue;out.put(item);}return out;}
    private static int indexOf(JSONArray list,String eventId){for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);if(item!=null&&eventId.equals(item.optString("eventId")))return i;}return -1;}
    /** Use a fixed upcoming projection ({@code {"events":[...]}}) instead of polling the runtime. */
    public void useFixture(JSONObject upcoming){fixture=upcoming;if(upcoming!=null)apply(upcoming);}
    /** Opens straight into one event's detail (from a Cards board row); BACK closes the page. */
    public void showEvent(JSONObject event){if(event==null)return;pinnedEventId=event.optString("eventId",null);detailOnly=true;events=new JSONArray().put(event);selected=0;detail=true;detailScroll=0;invalidate();}

    public boolean onInput(UiInputIntent input){if(input==UiInputIntent.BACK){if(detail&&!detailOnly){detail=false;detailScroll=0;invalidate();return true;}return false;}if(detail){if(input==UiInputIntent.NEXT)detailScroll=Math.min(maxDetailScroll,detailScroll+45);else if(input==UiInputIntent.PREVIOUS)detailScroll=Math.max(0,detailScroll-45);else if(input==UiInputIntent.ACTIVATE&&current().optBoolean("editable"))openVoice.run();invalidate();return true;}if(input==UiInputIntent.NEXT)move(1);else if(input==UiInputIntent.PREVIOUS)move(-1);else if(input==UiInputIntent.ACTIVATE)openDetail();else return false;return true;}
    private void move(int delta){if(events.length()==0)return;selected=(selected+delta+events.length())%events.length();focusAt=System.currentTimeMillis();manualPan=0;invalidate();}
    private void openDetail(){if(events.length()==0)return;detail=true;detailScroll=0;invalidate();}
    private JSONObject current(){JSONObject value=events.optJSONObject(selected);return value==null?new JSONObject():value;}

    @Override public boolean onTouchEvent(MotionEvent event){float x=event.getX()*W/Math.max(1,getWidth()),y=event.getY()*H/Math.max(1,getHeight());if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=x;downY=y;return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=x-downX,dy=y-downY;if(detail&&Math.abs(dy)>Math.abs(dx)){detailScroll=Math.max(0,Math.min(maxDetailScroll,detailScroll-dy));downY=y;invalidate();}else if(!detail&&downX>=88&&Math.abs(dx)>Math.abs(dy)){manualPan=Math.max(0,manualPan-dx);downX=x;invalidate();}return true;}if(event.getActionMasked()!=MotionEvent.ACTION_UP)return true;if(y<82){if(detail&&!detailOnly){detail=false;detailScroll=0;invalidate();}else closeCalendar.run();return true;}if(detail){if(y>=528&&current().optBoolean("editable"))openVoice.run();invalidate();return true;}if(y>=92&&y<572&&events.length()>0){int row=(int)((y-92)/96);int next=Math.min(events.length()-1,(selected/5)*5+row);if(next==selected)openDetail();else{selected=next;focusAt=System.currentTimeMillis();manualPan=0;invalidate();}}return true;}

    @Override protected void onDraw(Canvas canvas){canvas.save();canvas.scale(getWidth()/W,getHeight()/H);SamTheme.background(canvas,paint,W,H,430,30,260,SamTheme.ORB_BLUE);header(canvas);if(detail)drawDetail(canvas);else drawList(canvas);canvas.restore();}

    private void header(Canvas c){
        SamTheme.text(c,paint,"Calendar",64,52,30,SamTheme.INK,Paint.Align.LEFT,true);
        SamTheme.text(c,paint,detail?"Event details":(events.length()==0?"Upcoming":events.length()+(events.length()==1?" upcoming event":" upcoming events")),65,76,15,SamTheme.MUTED,Paint.Align.LEFT,false);
        orbDot(c,436,44,13);
    }

    /** Static, cheap orb glyph: white crown fading to orb blue. */
    private void orbDot(Canvas c,float cx,float cy,float r){
        paint.setShader(new android.graphics.RadialGradient(cx,cy+r*0.3f,r*2.4f,SamTheme.withAlpha(SamTheme.ORB_BLUE,70),SamTheme.withAlpha(SamTheme.ORB_BLUE,0),android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy+r*0.3f,r*2.4f,paint);
        paint.setShader(new android.graphics.LinearGradient(cx,cy-r,cx,cy+r,new int[]{0xffffffff,SamTheme.ORB_PALE,SamTheme.ORB_BLUE},new float[]{0.15f,0.5f,0.9f},android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,r,paint);paint.setShader(null);
    }

    private void drawList(Canvas c){
        if(events.length()==0){
            orbDot(c,240,262,34);
            SamTheme.text(c,paint,"No upcoming events",240,352,26,SamTheme.INK,Paint.Align.CENTER,true);
            SamTheme.text(c,paint,"Your calendar is clear.",240,384,18,SamTheme.MUTED,Paint.Align.CENTER,false);
            return;
        }
        int pageStart=(selected/5)*5;
        for(int row=0;row<5&&pageStart+row<events.length();row++){
            int i=pageStart+row;JSONObject item=events.optJSONObject(i);if(item==null)continue;
            float top=92+row*96;boolean focused=i==selected;
            RectF rect=new RectF(18,top,462,top+84);
            SamTheme.glass(c,paint,rect,20,focused);
            dateBadge(c,item,54,top+42,focused);
            drawLine(c,item.optString("title","Untitled event"),96,top+38,24,444,focused,28,true);
            String secondary=timeOnly(item);
            String location=item.optString("location","");
            if(!location.isBlank())secondary=secondary.isEmpty()?location:secondary+" · "+location;
            drawLine(c,secondary,96,top+64,17,444,focused,34,false);
        }
        if(events.length()>1)SamTheme.text(c,paint,(selected+1)+" of "+events.length(),456,610,16,SamTheme.MUTED,Paint.Align.RIGHT,false);
    }

    private void dateBadge(Canvas c,JSONObject item,float cx,float cy,boolean focused){
        String month="",day="";
        java.time.LocalDate date=localDate(item,"startsAt");
        if(date!=null){month=date.format(DateTimeFormatter.ofPattern("MMM"));day=date.format(DateTimeFormatter.ofPattern("d"));}
        paint.setStyle(Paint.Style.FILL);paint.setColor(SamTheme.withAlpha(SamTheme.ORB_BLUE,focused?70:38));
        c.drawRoundRect(new RectF(cx-24,cy-28,cx+24,cy+28),14,14,paint);
        if(day.isEmpty()){paint.setColor(SamTheme.ORB_PALE);c.drawCircle(cx,cy,5,paint);return;}
        SamTheme.text(c,paint,month,cx,cy-8,13,SamTheme.ORB_PALE,Paint.Align.CENTER,true);
        SamTheme.text(c,paint,day,cx,cy+18,24,SamTheme.INK,Paint.Align.CENTER,true);
    }

    private void drawLine(Canvas c,String text,float x,float baseline,float size,float right,boolean focused,float msPerPixel,boolean primary){paint.setTextSize(size);paint.setTypeface(android.graphics.Typeface.create(primary?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));float overflow=Math.max(0,paint.measureText(text)-(right-x));float offset=Math.min(manualPan,overflow);if(focused&&overflow>0&&manualPan==0){long elapsed=System.currentTimeMillis()-focusAt;float distance=overflow+18;long travel=Math.max(700,(long)(distance*msPerPixel));long cycle=900+travel+900;if(elapsed%cycle>900)offset=Math.min(distance,(elapsed%cycle-900)*distance/travel);postInvalidateDelayed(33);}c.save();c.clipRect(x,baseline-size-4,right,baseline+7);SamTheme.text(c,paint,text,x-offset,baseline,size,primary?(focused?SamTheme.INK:SamTheme.withAlpha(SamTheme.INK,215)):SamTheme.MUTED,Paint.Align.LEFT,primary);c.restore();}

    private void drawDetail(Canvas c){
        JSONObject item=current();boolean editable=item.optBoolean("editable");float contentBottom=editable?510:560;
        SamTheme.glass(c,paint,new RectF(18,88,462,622),24,false);
        c.save();c.clipRect(30,102,450,contentBottom);
        float y=140-detailScroll;
        y=wrapped(c,item.optString("title","Untitled event"),38,y,28,36,400,SamTheme.INK,true)+14;
        y=field(c,"Starts",friendly(item,"startsAt"),y);
        y=field(c,"Ends",friendly(item,"endsAt"),y);
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
            paint.setColor(SamTheme.LINE);c.drawRoundRect(new RectF(449,trackTop,453,trackBottom),2,2,paint);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE,170));c.drawRoundRect(new RectF(449,thumbTop,453,thumbTop+thumb),2,2,paint);
        }
        if(editable){
            RectF button=new RectF(30,528,450,608);
            paint.setShader(new android.graphics.LinearGradient(0,528,0,608,SamTheme.withAlpha(SamTheme.ORB_BLUE,235),SamTheme.withAlpha(SamTheme.ORB_BLUE,190),android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(button,24,24,paint);paint.setShader(null);
            SamTheme.text(c,paint,"Edit with voice",240,575,21,SamTheme.INK,Paint.Align.CENTER,true);
        }else{
            SamTheme.text(c,paint,"Read only",240,596,16,SamTheme.MUTED,Paint.Align.CENTER,false);
        }
    }
    private float field(Canvas c,String label,String value,float y){if(value==null||value.isBlank())return y;paint.setColor(SamTheme.LINE);c.drawRect(38,y-24,442,y-23,paint);SamTheme.text(c,paint,label,38,y,15,SamTheme.ORB_PALE,Paint.Align.LEFT,true);return wrapped(c,value,38,y+30,21,28,400,SamTheme.INK,false)+16;}
    private float wrapped(Canvas c,String value,float x,float y,float size,float line,float width,int color,boolean bold){paint.setTextSize(size);paint.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));String rest=value.trim();while(!rest.isEmpty()){int count=paint.breakText(rest,true,width,null);if(count<rest.length()){int space=rest.lastIndexOf(' ',Math.max(0,count-1));if(space>0)count=space;}String part=rest.substring(0,Math.max(1,count)).trim();SamTheme.text(c,paint,part,x,y,size,color,Paint.Align.LEFT,bold);rest=rest.substring(Math.min(rest.length(),Math.max(1,count))).trim();y+=line;}return y;}
    /*
     * Times are stored as UTC instants: show them in the device time zone. All-day events are stored as UTC
     * midnight of a floating date (end exclusive): show that date, never a converted time.
     */
    private String timeOnly(JSONObject item){String value=item.optString("startsAt");if(value.isBlank())return "";if(item.optBoolean("allDay")){java.time.LocalDate date=localDate(item,"startsAt");return date==null?"All day":date.format(DateTimeFormatter.ofPattern("EEE"))+" · All day";}java.time.ZonedDateTime time=zoned(value);return time==null?value:time.format(DateTimeFormatter.ofPattern(timePattern("EEE · ")));}
    private String friendly(JSONObject item,String key){String value=item.optString(key);if(value.isBlank())return "";if(item.optBoolean("allDay")){java.time.LocalDate date=localDate(item,key);if(date==null)return value;if("endsAt".equals(key)){java.time.LocalDate start=localDate(item,"startsAt");date=date.minusDays(1);if(start!=null&&!date.isAfter(start))return "";}return date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))+("startsAt".equals(key)?" · All day":"");}java.time.ZonedDateTime time=zoned(value);return time==null?value:time.format(DateTimeFormatter.ofPattern(timePattern("EEE, MMM d · ")));}
    private String timePattern(String prefix){return prefix+(android.text.format.DateFormat.is24HourFormat(getContext())?"H:mm":"h:mm a");}
    private static java.time.ZonedDateTime zoned(String value){try{return OffsetDateTime.parse(value.endsWith("Z")?value.substring(0,value.length()-1)+"+00:00":value).atZoneSameInstant(java.time.ZoneId.systemDefault());}catch(Exception ignored){return null;}}
    private static java.time.LocalDate localDate(JSONObject item,String key){String value=item.optString(key);if(value.isBlank())return null;try{OffsetDateTime parsed=OffsetDateTime.parse(value.endsWith("Z")?value.substring(0,value.length()-1)+"+00:00":value);return item.optBoolean("allDay")?parsed.toLocalDate():parsed.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();}catch(Exception ignored){return null;}}
    @Override public void close(){stop();client.close();}
}
