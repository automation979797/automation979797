package com.alcomet.rollingcoilarchive;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.*;
import android.net.http.SslError;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

public class MainActivity extends Activity {
    private WebView web;
    private LinearLayout offline;
    private TextView status;
    private Config config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(8,19,29));
        getWindow().setNavigationBarColor(Color.rgb(8,19,29));
        buildUi();
        configureWeb();
    }

    @Override protected void onResume() {
        super.onResume();
        Config c = SecureStore.load(this);
        if (c == null) {
            showOffline("Set the Operator server to begin.");
            showSettings();
            return;
        }
        String error = c.validate();
        if (error != null) {
            showOffline(error);
            showSettings();
            return;
        }
        config = c;
        loadHome();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8,19,29));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12),0,dp(6),0);
        bar.setBackgroundColor(Color.rgb(8,19,29));

        TextView logo = new TextView(this);
        logo.setText("A"); logo.setGravity(Gravity.CENTER); logo.setTextColor(Color.WHITE);
        logo.setTextSize(18); logo.setTypeface(null,1); logo.setBackgroundColor(Color.rgb(18,102,143));
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(dp(34),dp(34)); lpp.setMargins(0,0,dp(10),0);
        bar.addView(logo,lpp);

        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this); title.setText("ALCOMET • Coil Archive"); title.setTextColor(Color.WHITE); title.setTextSize(14); title.setTypeface(null,1); labels.addView(title);
        status = new TextView(this); status.setText("offline"); status.setTextColor(Color.rgb(159,178,194)); status.setTextSize(10); labels.addView(status);
        bar.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView reload = action("↻"); reload.setContentDescription("Reload"); reload.setOnClickListener(v -> { if(config!=null) web.reload(); });
        TextView settings = action("⚙"); settings.setContentDescription("Connection settings"); settings.setOnClickListener(v -> showSettings());
        bar.addView(reload,new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(50)));

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this); web.setBackgroundColor(Color.rgb(8,19,29)); content.addView(web,new FrameLayout.LayoutParams(-1,-1));

        offline = new LinearLayout(this); offline.setOrientation(LinearLayout.VERTICAL); offline.setGravity(Gravity.CENTER); offline.setPadding(dp(26),dp(26),dp(26),dp(26)); offline.setBackgroundColor(Color.rgb(8,19,29));
        TextView ot = new TextView(this); ot.setText("Operator server offline"); ot.setTextColor(Color.WHITE); ot.setTextSize(24); ot.setTypeface(null,1); ot.setGravity(Gravity.CENTER); offline.addView(ot);
        TextView om = new TextView(this); om.setTag("msg"); om.setTextColor(Color.rgb(159,178,194)); om.setTextSize(14); om.setGravity(Gravity.CENTER); om.setPadding(0,dp(12),0,dp(22)); offline.addView(om);
        Button retry = button("Retry", true); retry.setOnClickListener(v -> loadHome()); offline.addView(retry,new LinearLayout.LayoutParams(-1,dp(54)));
        Button cfg = button("Connection settings", false); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,dp(54)); cp.setMargins(0,dp(10),0,0); offline.addView(cfg,cp); cfg.setOnClickListener(v -> showSettings());
        content.addView(offline,new FrameLayout.LayoutParams(-1,-1));
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private TextView action(String s) { TextView v=new TextView(this); v.setText(s); v.setTextColor(Color.rgb(222,234,242)); v.setTextSize(23); v.setGravity(Gravity.CENTER); return v; }
    private Button button(String text, boolean primary) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setBackgroundColor(primary?Color.rgb(70,167,223):Color.rgb(23,42,58)); return b; }

    private void configureWeb() {
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false);
        if(Build.VERSION.SDK_INT>=21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ return block(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){ return block(Uri.parse(u)); }
            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap f){ setStatus("connecting…",Color.rgb(255,183,74)); }
            @Override public void onPageFinished(WebView v,String u){ offline.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); if(config!=null)setStatus(config.host+":"+config.port+" • online",Color.rgb(53,201,131)); }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){ if(r.isForMainFrame())showOffline("Cannot reach the Operator server. Check Wi-Fi/VPN and CoilReport service."); }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse res){ if(r.isForMainFrame()&&res.getStatusCode()>=500)showOffline("CoilReport returned HTTP "+res.getStatusCode()+"."); }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e){ h.cancel(); showOffline("HTTPS certificate validation failed. Invalid certificates are never bypassed."); }
        });
        web.setDownloadListener((url,ua,disp,mime,len)->download(url,ua,disp,mime));
    }

    private boolean block(Uri u){ if(config!=null&&config.sameOrigin(u))return false; Toast.makeText(this,"Blocked navigation outside the configured CoilReport server.",Toast.LENGTH_LONG).show(); return true; }

    private void download(String url,String ua,String disp,String mime){
        try{
            Uri u=Uri.parse(url); if(config==null||!config.sameOrigin(u)){Toast.makeText(this,"Blocked external download.",Toast.LENGTH_SHORT).show();return;}
            DownloadManager.Request r=new DownloadManager.Request(u); r.addRequestHeader("User-Agent",ua); r.setMimeType(mime);
            String name=URLUtil.guessFileName(url,disp,mime); r.setTitle(name); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name); ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
        }catch(Exception e){Toast.makeText(this,"Download failed.",Toast.LENGTH_SHORT).show();}
    }

    private void loadHome(){
        if(config==null) config=SecureStore.load(this);
        if(config==null){showOffline("Set the Operator server to begin.");return;}
        String err=config.validate(); if(err!=null){showOffline(err);return;}
        offline.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); web.loadUrl(config.baseUrl()+"/");
    }

    private void showOffline(String m){ web.setVisibility(View.GONE); offline.setVisibility(View.VISIBLE); for(int i=0;i<offline.getChildCount();i++){View v=offline.getChildAt(i);if("msg".equals(v.getTag()))((TextView)v).setText(m);} setStatus("offline",Color.rgb(255,102,122)); }
    private void setStatus(String s,int c){status.setText(s);status.setTextColor(c);}

    private void showSettings(){
        Config current=SecureStore.load(this); if(current==null) current=Config.defaults();
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),dp(10),dp(20),0);
        TextView note=new TextView(this); note.setText("Operator server only. Port 5050 is blocked. HTTPS is recommended. HTTP is allowed only for private LAN addresses."); note.setTextColor(Color.DKGRAY); note.setPadding(0,0,0,dp(12)); form.addView(note);
        Spinner scheme=new Spinner(this); scheme.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"https","http"})); scheme.setSelection(current.scheme.equals("http")?1:0); form.addView(scheme);
        EditText host=new EditText(this); host.setHint("192.168.1.100"); host.setSingleLine(true); host.setText(current.host); form.addView(host);
        EditText port=new EditText(this); port.setHint("5051"); port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(current.port)); form.addView(port);
        Switch allow=new Switch(this); allow.setText("Allow unencrypted HTTP on private LAN"); allow.setChecked(current.allowLanHttp); form.addView(allow);
        TextView state=new TextView(this); state.setPadding(0,dp(10),0,0); form.addView(state);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Connection settings").setView(form).setNegativeButton("Cancel",null).setNeutralButton("Test",null).setPositiveButton("Save & connect",null).create();
        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{Config c=readConfig(scheme,host,port,allow);String e=c.validate();if(e!=null){state.setText(e);state.setTextColor(Color.RED);return;}state.setText("Testing…");test(c,state,false,dlg);});
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Config c=readConfig(scheme,host,port,allow);String e=c.validate();if(e!=null){state.setText(e);state.setTextColor(Color.RED);return;}test(c,state,true,dlg);});
        });
        dlg.show();
    }

    private Config readConfig(Spinner s,EditText h,EditText p,Switch a){int port=5051;try{port=Integer.parseInt(p.getText().toString().trim());}catch(Exception ignored){}return new Config(String.valueOf(s.getSelectedItem()),h.getText().toString().trim(),port,a.isChecked());}

    private void test(Config c,TextView state,boolean save,AlertDialog dlg){
        executor.execute(()->{String result;boolean ok=false;HttpURLConnection con=null;try{URL u=new URL(c.baseUrl()+"/health");con=(HttpURLConnection)u.openConnection();con.setConnectTimeout(4000);con.setReadTimeout(4000);con.setRequestProperty("Accept","application/json,text/plain");int code=con.getResponseCode();if(code==200){ok=true;result="Connected";}else result="Server returned HTTP "+code;}catch(Exception e){result="Connection failed: "+safe(e);}finally{if(con!=null)con.disconnect();}boolean success=ok;String msg=result;runOnUiThread(()->{state.setText(msg);state.setTextColor(success?Color.rgb(0,140,80):Color.RED);if(success&&save){try{SecureStore.save(this,c);config=c;dlg.dismiss();loadHome();}catch(Exception e){state.setText("Secure settings save failed.");}}});});
    }

    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():(m.length()>100?m.substring(0,100):m);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override public void onBackPressed(){if(web.getVisibility()==View.VISIBLE&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){executor.shutdownNow();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}

    static final class Config {
        final String scheme,host; final int port; final boolean allowLanHttp;
        Config(String s,String h,int p,boolean a){scheme=s==null?"https":s.toLowerCase(Locale.ROOT).trim();host=h==null?"":h.trim();port=p;allowLanHttp=a;}
        static Config defaults(){return new Config("https","",5051,false);}
        String baseUrl(){String h=host.contains(":")&&!host.startsWith("[")?"["+host+"]":host;return scheme+"://"+h+":"+port;}
        String validate(){if(!scheme.equals("https")&&!scheme.equals("http"))return "Protocol must be HTTPS or HTTP.";if(host.isEmpty()||host.contains("/")||host.contains("@")||host.contains("?")||host.contains("#"))return "Enter only the server IP or hostname.";if(port<1||port>65535)return "Port must be between 1 and 65535.";if(port==5050)return "Port 5050 is the Engineering/Admin service and is blocked.";if(scheme.equals("http")&&(!allowLanHttp||!privateHost(host)))return "HTTP is allowed only when LAN HTTP is enabled for a private/local host.";return null;}
        boolean sameOrigin(Uri u){if(u==null||u.getScheme()==null||u.getHost()==null)return false;int p=u.getPort();if(p==-1)p=u.getScheme().equalsIgnoreCase("https")?443:80;return scheme.equalsIgnoreCase(u.getScheme())&&host.equalsIgnoreCase(u.getHost())&&p==port;}
        static boolean privateHost(String raw){String h=raw.toLowerCase(Locale.ROOT).trim();if(h.equals("localhost")||h.endsWith(".local"))return true;String[] x=h.split("\\.");if(x.length!=4)return false;try{int a=Integer.parseInt(x[0]),b=Integer.parseInt(x[1]);for(String q:x){int n=Integer.parseInt(q);if(n<0||n>255)return false;}return a==10||a==127||(a==172&&b>=16&&b<=31)||(a==192&&b==168)||(a==169&&b==254);}catch(Exception e){return false;}}
    }

    static final class SecureStore {
        static final String PREF="secure_connection", DATA="config", ALIAS="alcomet_coil_archive_key";
        static void save(Context c,Config cfg)throws Exception{JSONObject j=new JSONObject();j.put("scheme",cfg.scheme);j.put("host",cfg.host);j.put("port",cfg.port);j.put("allow",cfg.allowLanHttp);Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.ENCRYPT_MODE,key());byte[] enc=ci.doFinal(j.toString().getBytes(StandardCharsets.UTF_8));JSONObject env=new JSONObject();env.put("iv",Base64.encodeToString(ci.getIV(),Base64.NO_WRAP));env.put("data",Base64.encodeToString(enc,Base64.NO_WRAP));if(!c.getSharedPreferences(PREF,MODE_PRIVATE).edit().putString(DATA,env.toString()).commit())throw new IOException("save failed");}
        static Config load(Context c){String raw=c.getSharedPreferences(PREF,MODE_PRIVATE).getString(DATA,null);if(raw==null)return null;try{JSONObject e=new JSONObject(raw);Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(e.getString("iv"),Base64.NO_WRAP)));JSONObject j=new JSONObject(new String(ci.doFinal(Base64.decode(e.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));return new Config(j.optString("scheme","https"),j.optString("host",""),j.optInt("port",5051),j.optBoolean("allow",false));}catch(Exception ex){c.getSharedPreferences(PREF,MODE_PRIVATE).edit().remove(DATA).apply();return null;}}
        static SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);KeyStore.Entry e=ks.getEntry(ALIAS,null);if(e instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)e).getSecretKey();KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return g.generateKey();}
    }
}
