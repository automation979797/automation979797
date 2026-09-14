package com.alcomet.rollingcoilarchive;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import java.util.*;

public class NotificationJobService extends JobService {
    private static final int JOB_ID=15021; private static final String CHANNEL="coil_watch_v150";
    static void configure(Context c,MobileFeatureClient.Policy p){JobScheduler js=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);if(js==null)return;if(p==null||!p.watchlist||!p.notifications){js.cancel(JOB_ID);return;}long interval=Math.max(15,p.pollMinutes)*60_000L;JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(c,NotificationJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(interval).build();js.schedule(job);}
    @Override public boolean onStartJob(JobParameters params){new Thread(()->{try{runCheck();}catch(Exception ignored){}finally{jobFinished(params,false);}},"CoilWatchJob").start();return true;}
    @Override public boolean onStopJob(JobParameters params){return true;}
    private void runCheck()throws Exception{
        MainActivity.Config c=MainActivity.SecureStore.load(this);if(c==null||c.validate()!=null)return;
        MobileFeatureClient.Policy p=MobileFeatureClient.fetchPolicy(c);if(!p.watchlist||!p.notifications){configure(this,p);return;}
        Set<String>w=MobileWatchStore.getWatches(this);if(w.isEmpty())return;
        long now=System.currentTimeMillis()/1000L,last=MobileWatchStore.lastPoll(this);if(last<=0)last=now-120;
        JSONArray a=MobileFeatureClient.fetchEvents(c,last);if(a==null)return;
        for(int i=0;i<a.length();i++){JSONObject e=a.optJSONObject(i);if(e==null)continue;String id=e.optString("id"),coil=e.optString("coil"),fam=e.optString("family"),machine=e.optString("machine");if(id.isEmpty()||MobileWatchStore.wasNotified(this,id)||!MobileWatchStore.matches(w,coil,fam,p.childSuffixes))continue;String prev=e.optString("previous_machine");String type=e.optString("type");String title="Coil "+coil;String text=(p.journeyNotifications&&"handoff".equals(type)&&!prev.isEmpty())?prev+" → "+machine:"Detected at "+machine;notifyEvent(id,title,text,e.optString("report_url","/"));MobileWatchStore.markNotified(this,id);}
        MobileWatchStore.setLastPoll(this,now);
    }
    private void notifyEvent(String id,String title,String text,String path){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm==null)return;if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Coil Watchlist",NotificationManager.IMPORTANCE_DEFAULT));Intent open=new Intent(this,SafeMobileActivity.class).putExtra("open_path",path).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent pi=PendingIntent.getActivity(this,id.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);b.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi);nm.notify(id.hashCode(),b.build());}
}
