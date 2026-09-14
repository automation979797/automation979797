package com.alcomet.rollingcoilarchive;

import android.content.*;
import android.net.Uri;
import java.util.*;
import java.util.regex.Pattern;

final class MobileWatchStore {
    private static final String PREF="mobile_watch_v150",KEY="coils",LAST="last_poll",NOTIFIED="notified",MUTED="muted";
    private static final Pattern SAFE=Pattern.compile("^[A-Z0-9._-]{2,64}$");

    static Set<String> getWatches(Context c){return new TreeSet<>(c.getSharedPreferences(PREF,0).getStringSet(KEY,Collections.emptySet()));}

    static Set<String> getNotificationWatches(Context c){
        Set<String> s=getWatches(c);
        Set<String> muted=new HashSet<>(c.getSharedPreferences(PREF,0).getStringSet(MUTED,Collections.emptySet()));
        s.removeAll(muted);
        return s;
    }

    static void addWatch(Context c,String coil){
        coil=normalizeCoil(coil);if(coil.isEmpty())return;
        Set<String>s=getWatches(c);
        boolean wasEmpty=s.isEmpty();
        s.add(coil);
        SharedPreferences.Editor e=c.getSharedPreferences(PREF,0).edit().putStringSet(KEY,s);
        if(wasEmpty)e.putLong(LAST,System.currentTimeMillis()/1000L);
        e.apply();
    }

    static void removeWatch(Context c,String coil){
        coil=normalizeCoil(coil);
        Set<String>s=getWatches(c);s.remove(coil);
        Set<String>m=new HashSet<>(c.getSharedPreferences(PREF,0).getStringSet(MUTED,Collections.emptySet()));m.remove(coil);
        c.getSharedPreferences(PREF,0).edit().putStringSet(KEY,s).putStringSet(MUTED,m).apply();
    }

    static void clearWatches(Context c){
        c.getSharedPreferences(PREF,0).edit().remove(KEY).remove(MUTED).putLong(LAST,System.currentTimeMillis()/1000L).apply();
    }

    static boolean isMuted(Context c,String coil){
        coil=normalizeCoil(coil);
        return c.getSharedPreferences(PREF,0).getStringSet(MUTED,Collections.emptySet()).contains(coil);
    }

    static void setMuted(Context c,String coil,boolean muted){
        coil=normalizeCoil(coil);if(coil.isEmpty())return;
        Set<String>m=new HashSet<>(c.getSharedPreferences(PREF,0).getStringSet(MUTED,Collections.emptySet()));
        if(muted)m.add(coil);else m.remove(coil);
        c.getSharedPreferences(PREF,0).edit().putStringSet(MUTED,m).apply();
    }

    static String normalizeCoil(String v){if(v==null)return"";v=v.trim().toUpperCase(Locale.ROOT);return SAFE.matcher(v).matches()?v:"";}
    static long lastPoll(Context c){return c.getSharedPreferences(PREF,0).getLong(LAST,0);}
    static void setLastPoll(Context c,long v){c.getSharedPreferences(PREF,0).edit().putLong(LAST,v).apply();}
    static boolean wasNotified(Context c,String id){return c.getSharedPreferences(PREF,0).getStringSet(NOTIFIED,Collections.emptySet()).contains(id);}
    static void markNotified(Context c,String id){Set<String>s=new LinkedHashSet<>(c.getSharedPreferences(PREF,0).getStringSet(NOTIFIED,Collections.emptySet()));s.add(id);while(s.size()>250){Iterator<String>i=s.iterator();i.next();i.remove();}c.getSharedPreferences(PREF,0).edit().putStringSet(NOTIFIED,s).apply();}
    static boolean matches(Set<String>watches,String eventCoil,String family,String suffixCsv){String ec=normalizeCoil(eventCoil),fam=normalizeCoil(family);for(String w:watches){w=normalizeCoil(w);if(w.equals(ec))return true;if(!isChild(w,suffixCsv)&&w.equals(fam))return true;}return false;}
    static boolean isChild(String coil,String csv){coil=normalizeCoil(coil);if(coil.isEmpty())return false;for(String s:csv.split(",")){s=s==null?"":s.trim().toUpperCase(Locale.ROOT);if(!s.matches("[A-Z0-9]{1,4}"))continue;if(coil.length()>s.length()&&coil.endsWith(s))return true;}return false;}
    static final class ScanTarget{final String machine,coil;ScanTarget(String m,String c){machine=m;coil=c;}}
    static ScanTarget parseScan(String raw){if(raw==null)return null;String v=raw.trim();if(v.toUpperCase(Locale.ROOT).startsWith("CR-")){String[]p=v.split("-",3);if(p.length==3){String m=normalizeCoil(p[1]),c=normalizeCoil(p[2]);if(!m.isEmpty()&&!c.isEmpty())return new ScanTarget(m,c);}}try{Uri u=Uri.parse(v);String q=normalizeCoil(u.getQueryParameter("q"));if(!q.isEmpty())return new ScanTarget(normalizeCoil(u.getQueryParameter("machine")),q);}catch(Exception ignored){}String c=normalizeCoil(v);return c.isEmpty()?null:new ScanTarget("",c);}
}
