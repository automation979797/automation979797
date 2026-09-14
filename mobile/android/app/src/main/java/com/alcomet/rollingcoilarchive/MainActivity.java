package com.alcomet.rollingcoilarchive;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.http.SslError;
import android.os.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLHandshakeException;

public class MainActivity extends Activity {
    private static final String APP_VERSION = "1.1.0";
    private static final int C_BG = Color.rgb(8,19,29);
    private static final int C_PANEL_2 = Color.rgb(22,45,61);
    private static final int C_PRIMARY = Color.rgb(70,167,223);
    private static final int C_GREEN = Color.rgb(53,201,131);
    private static final int C_AMBER = Color.rgb(255,183,74);
    private static final int C_RED = Color.rgb(255,102,122);
    private static final int C_TEXT = Color.rgb(228,238,246);
    private static final int C_MUTED = Color.rgb(151,174,191);

    private WebView web;
    private LinearLayout offline, loading;
    private TextView offlineTitle, offlineMsg, loadingMsg, status;
    private ProgressBar pageProgress;
    private Config config;
    private String lastRequestedUrl;
    private boolean restoredState, settingsShowing, destroyed;
    private long lastBackAt;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connectInFlight = new AtomicBoolean(false);
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        config = SecureStore.load(this);
        buildUi();
        configureWeb();

        if (state != null && valid(config)) {
            try {
                WebBackForwardList list = web.restoreState(state);
                restoredState = list != null && list.getSize() > 0;
                if (restoredState) {
                    showWeb();
                    lastRequestedUrl = web.getUrl();
                }
            } catch (Exception ignored) {}
        }

        if (!valid(config)) {
            showOffline("Connection required", config == null ? "Set the CoilReport Operator server to begin." : config.validate());
            new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!isFinishing() && !destroyed) showSettings(); }, 250);
        } else if (!restoredState) {
            connectAndOpen(config.baseUrl() + "/", true);
        }
    }

    @Override protected void onStart() { super.onStart(); registerNetworkCallback(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onStop() { unregisterNetworkCallback(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle outState) { try { if (web != null) web.saveState(outState); } catch (Exception ignored) {} super.onSaveInstanceState(outState); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10),0,dp(4),0);
        bar.setBackgroundColor(C_BG);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.alcomet_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setContentDescription("Alcomet");
        logo.setOnClickListener(v -> goHome());
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(112), dp(44));
        logoLp.setMargins(0,0,dp(8),0);
        bar.addView(logo, logoLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Rolling Shop Coil Archive");
        title.setTextColor(C_TEXT);
        title.setTextSize(12.5f);
        title.setTypeface(null,1);
        title.setSingleLine(true);
        labels.addView(title);
        status = new TextView(this);
        status.setText("offline");
        status.setTextColor(C_MUTED);
        status.setTextSize(9.5f);
        status.setSingleLine(true);
        labels.addView(status);
        bar.addView(labels,new LinearLayout.LayoutParams(0,-1,1));

        TextView home = action("⌂","Home"); home.setOnClickListener(v -> goHome());
        TextView reload = action("↻","Reload"); reload.setOnClickListener(v -> reloadCurrent());
        TextView settings = action("⚙","Connection settings"); settings.setOnClickListener(v -> showSettings());
        bar.addView(home,new LinearLayout.LayoutParams(dp(40),dp(50)));
        bar.addView(reload,new LinearLayout.LayoutParams(dp(40),dp(50)));
        bar.addView(settings,new LinearLayout.LayoutParams(dp(40),dp(50)));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(54)));

        pageProgress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100); pageProgress.setVisibility(View.GONE);
        root.addView(pageProgress,new LinearLayout.LayoutParams(-1,dp(2)));

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this); web.setBackgroundColor(C_BG); content.addView(web,new FrameLayout.LayoutParams(-1,-1));

        loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL); loading.setGravity(Gravity.CENTER); loading.setPadding(dp(28),dp(28),dp(28),dp(28)); loading.setBackgroundColor(C_BG);
        ProgressBar spinner = new ProgressBar(this); loading.addView(spinner,new LinearLayout.LayoutParams(dp(44),dp(44)));
        loadingMsg = new TextView(this); loadingMsg.setTextColor(C_MUTED); loadingMsg.setTextSize(14); loadingMsg.setGravity(Gravity.CENTER); loadingMsg.setPadding(0,dp(18),0,0); loading.addView(loadingMsg);
        content.addView(loading,new FrameLayout.LayoutParams(-1,-1));

        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL); offline.setGravity(Gravity.CENTER); offline.setPadding(dp(26),dp(26),dp(26),dp(26)); offline.setBackgroundColor(C_BG);
        ImageView offlineLogo = new ImageView(this); offlineLogo.setImageResource(R.drawable.alcomet_logo); offlineLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE); offline.addView(offlineLogo,new LinearLayout.LayoutParams(dp(220),dp(66)));
        offlineTitle = new TextView(this); offlineTitle.setTextColor(C_TEXT); offlineTitle.setTextSize(22); offlineTitle.setTypeface(null,1); offlineTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams otp = new LinearLayout.LayoutParams(-1,-2); otp.setMargins(0,dp(18),0,0); offline.addView(offlineTitle,otp);
        offlineMsg = new TextView(this); offlineMsg.setTextColor(C_MUTED); offlineMsg.setTextSize(14); offlineMsg.setGravity(Gravity.CENTER); offlineMsg.setPadding(0,dp(10),0,dp(22)); offline.addView(offlineMsg);
        Button retry = button("Retry connection",true); retry.setOnClickListener(v -> connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true)); offline.addView(retry,new LinearLayout.LayoutParams(-1,dp(54)));
        Button cfg = button("Connection settings",false); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,dp(54)); cp.setMargins(0,dp(10),0,0); offline.addView(cfg,cp); cfg.setOnClickListener(v -> showSettings());
        TextView hint = new TextView(this); hint.setText("Operator UI only • Admin port 5050 blocked • v" + APP_VERSION); hint.setTextColor(Color.rgb(105,131,150)); hint.setTextSize(10); hint.setGravity(Gravity.CENTER); hint.setPadding(0,dp(18),0,0); offline.addView(hint);
        content.addView(offline,new FrameLayout.LayoutParams(-1,-1));

        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private TextView action(String glyph,String description) {
        TextView v = new TextView(this); v.setText(glyph); v.setTextColor(C_TEXT); v.setTextSize(21); v.setGravity(Gravity.CENTER); v.setContentDescription(description); return v;
    }

    private Button button(String text,boolean primary) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setTypeface(null,1);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? C_PRIMARY : C_PANEL_2); bg.setCornerRadius(dp(12)); if (!primary) bg.setStroke(dp(1),Color.rgb(55,81,99)); b.setBackground(bg); return b;
    }

    private void configureWeb() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false); s.setTextZoom(100); s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true); if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web,false);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int progress) { pageProgress.setProgress(progress); pageProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE); }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r) { return shouldBlock(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url) { return shouldBlock(Uri.parse(url)); }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap f) { setStatus("loading…",C_AMBER); }
            @Override public void onPageFinished(WebView v,String url) {
                if (!destroyed && config != null && config.sameOrigin(Uri.parse(url))) {
                    lastRequestedUrl = url; showWeb(); setStatus(config.host + ":" + config.port + " • online",C_GREEN);
                }
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e) { if (r != null && r.isForMainFrame()) showOffline("Connection lost",friendlyWebError(e)); }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse response) {
                if (r != null && r.isForMainFrame() && response != null) {
                    int code = response.getStatusCode();
                    if (code >= 500) showOffline("Server error","CoilReport returned HTTP " + code + ".");
                    else if (code == 401 || code == 403) showOffline("Access denied","Operator service returned HTTP " + code + ".");
                }
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e) { h.cancel(); showOffline("Secure connection blocked","HTTPS certificate validation failed. Invalid certificates are never bypassed."); }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) { showOffline("WebView restarted","Android WebView stopped unexpectedly. Tap Retry to reconnect safely."); return true; }
        });
        web.setDownloadListener((url,ua,disp,mime,len) -> download(url,ua,disp,mime));
    }

    private boolean shouldBlock(Uri uri) {
        if (uri == null) return true;
        if (config != null && config.sameOrigin(uri)) return false;
        if ("about:blank".equalsIgnoreCase(uri.toString())) return false;
        toast("Blocked navigation outside the configured CoilReport Operator server."); return true;
    }

    private void download(String url,String ua,String disp,String mime) {
        try {
            Uri u = Uri.parse(url); if (config == null || !config.sameOrigin(u)) { toast("Blocked external download."); return; }
            DownloadManager.Request r = new DownloadManager.Request(u);
            if (ua != null && !ua.isEmpty()) r.addRequestHeader("User-Agent",ua);
            String cookie = CookieManager.getInstance().getCookie(url); if (cookie != null && !cookie.isEmpty()) r.addRequestHeader("Cookie",cookie);
            String referer = web.getUrl(); if (referer != null && config.sameOrigin(Uri.parse(referer))) r.addRequestHeader("Referer",referer);
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            String name = URLUtil.guessFileName(url,disp,mime); r.setTitle(name); r.setDescription("Alcomet Coil Archive");
            r.setAllowedOverMetered(true); r.setAllowedOverRoaming(false); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE); if (dm == null) throw new IOException("Download service unavailable"); dm.enqueue(r); toast("Downloading " + name);
        } catch (Exception e) { toast("Download failed: " + safe(e)); }
    }

    private void connectAndOpen(String url,boolean healthFirst) {
        if (!valid(config)) { showOffline("Connection required",config == null ? "Set the CoilReport Operator server to begin." : config.validate()); return; }
        if (url == null || !config.sameOrigin(Uri.parse(url))) url = homeUrl();
        final String target = url; lastRequestedUrl = target; hideKeyboard();
        if (!healthFirst) { showLoading("Opening Operator UI…"); web.loadUrl(target); return; }
        if (!connectInFlight.compareAndSet(false,true)) return;
        showLoading("Connecting to " + config.host + ":" + config.port + "…");
        executor.execute(() -> {
            HealthResult result = checkHealth(config);
            runOnUiThread(() -> {
                connectInFlight.set(false); if (destroyed) return;
                if (result.ok) { setStatus(config.host + ":" + config.port + (result.version == null ? " • online" : " • CoilReport " + result.version),C_GREEN); web.loadUrl(target); }
                else showOffline("Operator server unavailable",result.message);
            });
        });
    }

    private HealthResult checkHealth(Config c) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection)new URL(c.baseUrl() + "/health").openConnection(); con.setConnectTimeout(3500); con.setReadTimeout(3500); con.setUseCaches(false); con.setInstanceFollowRedirects(false); con.setRequestProperty("Accept","application/json,text/plain");
            int code = con.getResponseCode(); if (code != 200) return HealthResult.fail("Server returned HTTP " + code + " from /health.");
            String body = readSmall(con.getInputStream()); String version = null;
            try { JSONObject obj = new JSONObject(body); version = obj.optString("version",null); if (version != null && version.trim().isEmpty()) version = null; } catch (Exception ignored) {}
            return HealthResult.ok(version);
        } catch (SSLHandshakeException e) { return HealthResult.fail("HTTPS certificate validation failed."); }
        catch (Exception e) { return HealthResult.fail(networkMessage(e)); }
        finally { if (con != null) con.disconnect(); }
    }

    private static String readSmall(InputStream in) throws IOException {
        if (in == null) return ""; ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1024]; int total = 0,n;
        while (total < 4096 && (n = in.read(b,0,Math.min(b.length,4096-total))) > 0) { out.write(b,0,n); total += n; }
        in.close(); return out.toString("UTF-8");
    }

    private String networkMessage(Exception e) {
        if (!hasNetwork()) return "Phone is not connected to factory Wi-Fi/VPN. Connect and Retry.";
        if (e instanceof SocketTimeoutException) return "Connection timed out. Check Operator service and port 5051.";
        if (e instanceof UnknownHostException) return "Server address could not be resolved. Check the IP/hostname.";
        if (e instanceof ConnectException) return "Connection refused. Check CoilReport service and firewall port 5051.";
        return "Cannot reach Operator server. " + safe(e);
    }

    private String friendlyWebError(WebResourceError e) {
        if (!hasNetwork()) return "Phone is not connected to the factory Wi-Fi/VPN.";
        if (e == null || e.getDescription() == null) return "Cannot reach Operator server.";
        String m = e.getDescription().toString(); return m.length() > 120 ? m.substring(0,120) : m;
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); if (cm == null) return false;
        Network n = cm.getActiveNetwork(); if (n == null) return false; NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    private void reloadCurrent() {
        if (!valid(config)) { showSettings(); return; }
        if (web.getVisibility() == View.VISIBLE && web.getUrl() != null && config.sameOrigin(Uri.parse(web.getUrl()))) { setStatus("reloading…",C_AMBER); web.reload(); }
        else connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true);
    }

    private void goHome() { if (!valid(config)) { showSettings(); return; } web.clearHistory(); connectAndOpen(homeUrl(),offline.getVisibility() == View.VISIBLE); }
    private String homeUrl() { return valid(config) ? config.baseUrl() + "/" : "about:blank"; }
    private void showWeb() { loading.setVisibility(View.GONE); offline.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); }
    private void showLoading(String m) { web.setVisibility(View.GONE); offline.setVisibility(View.GONE); loading.setVisibility(View.VISIBLE); loadingMsg.setText(m); setStatus("connecting…",C_AMBER); }
    private void showOffline(String title,String msg) { web.setVisibility(View.GONE); loading.setVisibility(View.GONE); offline.setVisibility(View.VISIBLE); offlineTitle.setText(title); offlineMsg.setText(msg); setStatus("offline",C_RED); }
    private void setStatus(String s,int c) { if (status != null) { status.setText(s); status.setTextColor(c); } }

    private void showSettings() {
        if (settingsShowing || destroyed) return; settingsShowing = true;
        Config current = config != null ? config : SecureStore.load(this); if (current == null) current = Config.defaults();
        ScrollView scroll = new ScrollView(this); LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),dp(8),dp(20),dp(4)); scroll.addView(form);
        ImageView brand = new ImageView(this); brand.setImageResource(R.drawable.alcomet_logo); brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE); form.addView(brand,new LinearLayout.LayoutParams(-1,dp(62)));
        TextView note = new TextView(this); note.setText("Operator server only. Admin port 5050 is blocked. HTTPS is recommended. Private-LAN HTTP must be explicitly enabled."); note.setTextColor(Color.DKGRAY); note.setTextSize(13); note.setPadding(0,0,0,dp(12)); form.addView(note);
        Spinner scheme = new Spinner(this); scheme.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"https","http"})); scheme.setSelection(current.scheme.equals("http")?1:0); form.addView(scheme);
        EditText host = new EditText(this); host.setHint("192.168.40.206"); host.setSingleLine(true); host.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI); host.setText(current.host); form.addView(host);
        EditText port = new EditText(this); port.setHint("5051"); port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(current.port)); form.addView(port);
        Switch allow = new Switch(this); allow.setText("Allow unencrypted HTTP on private LAN"); allow.setChecked(current.allowLanHttp); form.addView(allow);
        TextView state = new TextView(this); state.setText("Not tested"); state.setTextColor(Color.DKGRAY); state.setTextSize(12); state.setPadding(0,dp(10),0,0); form.addView(state);
        TextView ver = new TextView(this); ver.setText("Android v" + APP_VERSION); ver.setTextColor(Color.GRAY); ver.setTextSize(10); ver.setPadding(0,dp(10),0,0); form.addView(ver);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Connection settings").setView(scroll).setNegativeButton("Close",null).setNeutralButton("Test",null).setPositiveButton("Save & connect",null).create();
        dlg.setOnDismissListener(d -> settingsShowing = false);
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                Config c = readConfig(scheme,host,port,allow); String err = c.validate(); if (err != null) { state.setText(err); state.setTextColor(Color.RED); return; }
                state.setText("Testing…"); state.setTextColor(Color.DKGRAY); dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                executor.execute(() -> { HealthResult r = checkHealth(c); runOnUiThread(() -> { if (!dlg.isShowing()) return; dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true); state.setText(r.ok ? "Connected" + (r.version == null ? "" : " • CoilReport " + r.version) : r.message); state.setTextColor(r.ok ? Color.rgb(0,140,80) : Color.RED); }); });
            });
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Config c = readConfig(scheme,host,port,allow); String err = c.validate(); if (err != null) { state.setText(err); state.setTextColor(Color.RED); return; }
                try { String old = config == null ? null : config.originKey(); SecureStore.save(this,c); config = c; if (old == null || !old.equals(c.originKey())) { web.stopLoading(); web.clearHistory(); lastRequestedUrl = null; } dlg.dismiss(); connectAndOpen(homeUrl(),true); }
                catch (Exception e) { state.setText("Secure settings save failed: " + safe(e)); state.setTextColor(Color.RED); }
            });
        });
        dlg.show();
    }

    private Config readConfig(Spinner s,EditText h,EditText p,Switch a) { int port = -1; try { port = Integer.parseInt(p.getText().toString().trim()); } catch (Exception ignored) {} return new Config(String.valueOf(s.getSelectedItem()),h.getText().toString().trim(),port,a.isChecked()); }
    private void hideKeyboard() { try { View f = getCurrentFocus(); if (f != null) { InputMethodManager i = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if (i != null) i.hideSoftInputFromWindow(f.getWindowToken(),0); } } catch (Exception ignored) {} }

    private void registerNetworkCallback() {
        if (networkCallback != null || Build.VERSION.SDK_INT < 24) return;
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { runOnUiThread(() -> { if (!destroyed && valid(config) && offline.getVisibility() == View.VISIBLE && !connectInFlight.get()) connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true); }); }
            @Override public void onLost(Network n) { runOnUiThread(() -> { if (!hasNetwork()) setStatus("network offline",C_RED); }); }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); } catch (Exception e) { networkCallback = null; }
    }
    private void unregisterNetworkCallback() { if (connectivity != null && networkCallback != null) try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {} networkCallback = null; }

    private static boolean valid(Config c) { return c != null && c.validate() == null; }
    private void toast(String s) { if (!destroyed) Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    private static String safe(Exception e) { String m = e == null ? null : e.getMessage(); if (m == null || m.trim().isEmpty()) m = e == null ? "Unknown error" : e.getClass().getSimpleName(); m = m.replace('\n',' ').replace('\r',' ').trim(); return m.length() > 120 ? m.substring(0,120) : m; }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis(); if (now-lastBackAt < 1800) super.onBackPressed(); else { lastBackAt=now; toast("Press Back again to close"); }
    }

    @Override protected void onDestroy() {
        destroyed = true; unregisterNetworkCallback(); connectInFlight.set(false); executor.shutdownNow();
        if (web != null) try { web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.removeAllViews(); web.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    static final class HealthResult {
        final boolean ok; final String message,version;
        private HealthResult(boolean ok,String message,String version){this.ok=ok;this.message=message;this.version=version;}
        static HealthResult ok(String v){return new HealthResult(true,"Connected",v);} static HealthResult fail(String m){return new HealthResult(false,m,null);}
    }

    static final class Config {
        private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:%-]+$");
        final String scheme,host; final int port; final boolean allowLanHttp;
        Config(String s,String h,int p,boolean a){scheme=s==null?"https":s.toLowerCase(Locale.ROOT).trim();host=h==null?"":h.trim().replace("[","").replace("]","");port=p;allowLanHttp=a;}
        static Config defaults(){return new Config("https","",5051,false);}
        String baseUrl(){String h=host.contains(":")?"["+host+"]":host;return scheme+"://"+h+":"+port;}
        String originKey(){return scheme+"|"+host.toLowerCase(Locale.ROOT)+"|"+port;}
        String validate(){
            if(!scheme.equals("https")&&!scheme.equals("http"))return "Protocol must be HTTPS or HTTP.";
            if(host.isEmpty())return "Enter the CoilReport server IP or hostname.";
            if(host.length()>253||!HOST.matcher(host).matches())return "Server address contains unsupported characters.";
            if(port<1||port>65535)return "Port must be between 1 and 65535.";
            if(port==5050)return "Port 5050 is the Engineering/Admin service and is blocked.";
            if(scheme.equals("http")&&(!allowLanHttp||!privateHost(host)))return "HTTP is allowed only for private/local addresses when LAN HTTP is enabled.";
            try{if(new URI(baseUrl()).getHost()==null)return "Invalid server IP or hostname.";}catch(Exception e){return "Invalid server IP or hostname.";}return null;
        }
        boolean sameOrigin(Uri u){if(u==null||u.getScheme()==null||u.getHost()==null)return false;String s=u.getScheme().toLowerCase(Locale.ROOT);int p=u.getPort();if(p==-1)p=s.equals("https")?443:80;return scheme.equalsIgnoreCase(s)&&host.equalsIgnoreCase(u.getHost())&&p==port;}
        static boolean privateHost(String raw){String h=raw==null?"":raw.toLowerCase(Locale.ROOT).trim();if(h.equals("localhost")||h.endsWith(".local")||(!h.contains(".")&&!h.contains(":")))return true;if(h.equals("::1")||h.startsWith("fe80:")||h.startsWith("fc")||h.startsWith("fd"))return true;String[] x=h.split("\\.");if(x.length!=4)return false;try{int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(x[i]);if(n[i]<0||n[i]>255)return false;}return n[0]==10||n[0]==127||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==192&&n[1]==168)||(n[0]==169&&n[1]==254);}catch(Exception e){return false;}}
    }

    static final class SecureStore {
        static final String PREF="secure_connection",DATA="config",ALIAS="alcomet_coil_archive_key";
        static void save(Context c,Config cfg)throws Exception{JSONObject j=new JSONObject();j.put("scheme",cfg.scheme);j.put("host",cfg.host);j.put("port",cfg.port);j.put("allow",cfg.allowLanHttp);Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.ENCRYPT_MODE,key());byte[] enc=ci.doFinal(j.toString().getBytes(StandardCharsets.UTF_8));JSONObject env=new JSONObject();env.put("iv",Base64.encodeToString(ci.getIV(),Base64.NO_WRAP));env.put("data",Base64.encodeToString(enc,Base64.NO_WRAP));if(!c.getSharedPreferences(PREF,MODE_PRIVATE).edit().putString(DATA,env.toString()).commit())throw new IOException("settings write failed");}
        static Config load(Context c){String raw=c.getSharedPreferences(PREF,MODE_PRIVATE).getString(DATA,null);if(raw==null)return null;try{JSONObject e=new JSONObject(raw);Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(e.getString("iv"),Base64.NO_WRAP)));JSONObject j=new JSONObject(new String(ci.doFinal(Base64.decode(e.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));return new Config(j.optString("scheme","https"),j.optString("host",""),j.optInt("port",5051),j.optBoolean("allow",false));}catch(Exception ex){c.getSharedPreferences(PREF,MODE_PRIVATE).edit().remove(DATA).apply();return null;}}
        static SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);KeyStore.Entry e=ks.getEntry(ALIAS,null);if(e instanceof KeyStore.SecretKeyEntry)return((KeyStore.SecretKeyEntry)e).getSecretKey();KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return g.generateKey();}
    }
}
