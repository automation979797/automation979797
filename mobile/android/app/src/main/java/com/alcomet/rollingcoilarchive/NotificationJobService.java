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
    private static final int JOB_ID=15021;
    private static final int TEST_ID=15022;
    private static final String CHANNEL="coil_watch_v150";

    static void configure(Context c,MobileFeatureClient.Policy p){
        JobScheduler js=(JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE);
        if(js==null)return;
        if(p==null||!p.watchlist||!p.notifications){js.cancel(JOB_ID);return;}
        long interval=Math.max(15,p.pollMinutes)*60_000L;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(c,NotificationJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(interval)
            .build();
        js.schedule(job);
    }

    static boolean showTestNotification(Context c){
        if(c==null)return false;
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager nm=(NotificationManager)c.getSystemService(NOTIFICATION_SERVICE);
        if(nm==null)return false;
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Coil Favorites",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(c,FavoritesActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,TEST_ID,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CoilReport notification test")
            .setContentText("Favorite coil notifications are working.")
            .setAutoCancel(true)
            .setContentIntent(pi);
        nm.notify(TEST_ID,b.build());
        return true;
    }

    @Override public boolean onStartJob(JobParameters params){
        new Thread(()->{try{runCheck();}catch(Exception ignored){}finally{jobFinished(params,false);}},"CoilWatchJob").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}

    private void runCheck()throws Exception{
        MainActivity.Config c=MainActivity.SecureStore.load(this);
        if(c==null||c.validate()!=null)return;
        MobileFeatureClient.Policy p=MobileFeatureClient.fetchPolicy(c);
        if(!p.watchlist||!p.notifications){configure(this,p);return;}

        long now=System.currentTimeMillis()/1000L;
        Set<String>w=MobileWatchStore.getNotificationWatches(this);
        // Do not keep an old cursor while there are no active alert subscriptions.
        // Otherwise unmuting/adding a favorite later could replay old historical events.
        if(w.isEmpty()){
            MobileWatchStore.setLastPoll(this,now);
            return;
        }

        long last=MobileWatchStore.lastPoll(this);
        if(last<=0)last=now-120;
        JSONArray a=MobileFeatureClient.fetchEvents(c,last);
        if(a==null)return;
        boolean permissionReady=Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        for(int i=0;i<a.length();i++){
            JSONObject e=a.optJSONObject(i);if(e==null)continue;
            String id=e.optString("id"),coil=e.optString("coil"),fam=e.optString("family"),machine=e.optString("machine");
            if(id.isEmpty()||MobileWatchStore.wasNotified(this,id)||!MobileWatchStore.matches(w,coil,fam,p.childSuffixes))continue;
            if(!permissionReady)continue;
            String prev=e.optString("previous_machine");
            String type=e.optString("type");
            String title="Coil "+coil;
            String machineLabel=MachineNames.label(machine);
            String prevLabel=MachineNames.label(prev);
            String text=(p.journeyNotifications&&"handoff".equals(type)&&!prev.isEmpty())?prevLabel+" → "+machineLabel:"Detected at "+machineLabel;
            if(notifyEvent(id,title,text,e.optString("report_url","/")))MobileWatchStore.markNotified(this,id);
        }
        if(permissionReady)MobileWatchStore.setLastPoll(this,now);
    }

    private boolean notifyEvent(String id,String title,String text,String path){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm==null)return false;
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Coil Favorites",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(this,SafeMobileActivity.class).putExtra("open_path",path).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,id.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi);
        nm.notify(id.hashCode(),b.build());
        return true;
    }
}
