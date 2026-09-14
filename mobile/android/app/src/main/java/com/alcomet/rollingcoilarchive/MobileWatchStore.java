package com.alcomet.rollingcoilarchive;

import android.content.*;
import android.net.Uri;
import java.util.*;
import java.util.regex.Pattern;

final class MobileWatchStore {
    private static final String PREF="mobile_watch_v150",KEY="coils",LAST="last_poll",NOTIFIED="notified";
    private static final Pattern SAFE=Pattern.compile("^[A-Z0-9._-]{2,64}$");
    static Set<String> getWatches(Context c){return new TreeSet<>(c.getSharedPreferences(PREF,0).getStringSet(KEY,Collections.emptySet()));}
    static void addWatch(Context c,String coil){coil=normalizeCoil(coil);if(coil.isEmpty())return;Set<String>s=getWatches(c);s.add(coil);c.getSharedPreferences(PREF,0).edit().putStringSet(KEY,s).apply();}
    static void removeWatch(Context c,String coil){Set<String>s=getWatches(c);s.remove(normalizeCoil(coil));c.getSharedPreferences(PREF,0).edit().putStringSet(KEY,s).apply();}
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
