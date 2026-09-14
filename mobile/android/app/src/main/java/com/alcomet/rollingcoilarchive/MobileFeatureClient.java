package com.alcomet.rollingcoilarchive;

import org.json.*;
import java.io.*;
import java.net.*;

final class MobileFeatureClient {
    static final class Policy {
        final boolean qrBarcodeSearch,watchlist,notifications,journeyNotifications,nativePdfShare;
        final int pollMinutes; final String childSuffixes,version;
        Policy(boolean q,boolean w,boolean n,boolean j,boolean p,int min,String suffix,String version){this.qrBarcodeSearch=q;this.watchlist=w;this.notifications=w&&n;this.journeyNotifications=w&&n&&j;this.nativePdfShare=p;this.pollMinutes=Math.max(15,Math.min(240,min));this.childSuffixes=suffix==null?"A,B":suffix;this.version=version==null?"":version;}
        static Policy disabled(){return new Policy(false,false,false,false,false,15,"A,B","");}
    }
    static Policy fetchPolicy(MainActivity.Config c)throws Exception{
        JSONObject o=getJSON(c,c.baseUrl()+"/mobile-capabilities.json");
        return new Policy(o.optBoolean("qr_barcode_search"),o.optBoolean("watchlist"),o.optBoolean("notifications"),o.optBoolean("journey_notifications"),o.optBoolean("native_pdf_share"),o.optInt("poll_minutes",15),o.optString("child_suffixes","A,B"),o.optString("version",""));
    }
    static JSONArray fetchEvents(MainActivity.Config c,long since)throws Exception{return getJSON(c,c.baseUrl()+"/mobile-events.json?since="+since).optJSONArray("events");}
    private static JSONObject getJSON(MainActivity.Config c,String url)throws Exception{
        HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();
        try{con.setConnectTimeout(5000);con.setReadTimeout(10000);con.setUseCaches(false);con.setInstanceFollowRedirects(false);con.setRequestProperty("Accept","application/json");int code=con.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=con.getInputStream()){byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))>0&&total<1024*1024){out.write(b,0,n);total+=n;}}return new JSONObject(out.toString("UTF-8"));}finally{con.disconnect();}
    }
}
