package com.alcomet.rollingcoilarchive;

import android.app.*;
import android.content.*;
import android.content.ContentValues;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.http.SslError;
import android.os.*;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLHandshakeException;

public class MobileActivity extends Activity {
    private static final String APP_VERSION = "1.2.0";
    private static final int C_BG = Color.rgb(7,17,26);
    private static final int C_PANEL = Color.rgb(13,31,44);
    private static final int C_PANEL_2 = Color.rgb(20,43,59);
    private static final int C_PRIMARY = Color.rgb(70,167,223);
    private static final int C_GREEN = Color.rgb(53,201,131);
    private static final int C_AMBER = Color.rgb(255,183,74);
    private static final int C_RED = Color.rgb(255,102,122);
    private static final int C_TEXT = Color.rgb(232,241,247);
    private static final int C_MUTED = Color.rgb(145,169,187);
    private static final long MAX_BLOB_BYTES = 60L * 1024L * 1024L;

    private LinearLayout root;
    private WebView web;
    private LinearLayout offline, loading;
    private TextView offlineTitle, offlineMsg, loadingMsg, status;
    private ProgressBar pageProgress;
    private MainActivity.Config config;
    private String lastRequestedUrl;
    private boolean restoredState, settingsShowing, destroyed;
    private long lastBackAt;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean connectInFlight = new AtomicBoolean(false);
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final PdfBridge pdfBridge = new PdfBridge();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        configureSystemBars();
        config = MainActivity.SecureStore.load(this);
        buildUi();
        configureWeb();
        applySafeInsets();

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
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !destroyed) showSettings();
            }, 250);
        } else if (!restoredState) {
            connectAndOpen(homeUrl(), true);
        }
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    private void applySafeInsets() {
        if (root == null) return;
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    @Override protected void onStart() { super.onStart(); registerNetworkCallback(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onStop() { unregisterNetworkCallback(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle outState) {
        try { if (web != null) web.saveState(outState); } catch (Exception ignored) {}
        super.onSaveInstanceState(outState);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(3), dp(6), dp(3));
        bar.setBackgroundColor(C_PANEL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.alcomet_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setContentDescription("Alcomet");
        logo.setOnClickListener(v -> goHome());
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(94), dp(42));
        logoLp.setMargins(0,0,dp(6),0);
        bar.addView(logo, logoLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("COIL ARCHIVE");
        title.setTextColor(C_TEXT);
        title.setTextSize(10.5f);
        title.setTypeface(null,1);
        title.setSingleLine(true);
        labels.addView(title);
        status = new TextView(this);
        status.setText("OFFLINE");
        status.setTextColor(C_MUTED);
        status.setTextSize(8.5f);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(status);
        bar.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));

        ImageButton home = actionButton(R.drawable.ic_home_v120, "Home");
        ImageButton reload = actionButton(R.drawable.ic_refresh_v120, "Reload");
        ImageButton pdf = actionButton(R.drawable.ic_pdf_v120, "Save current page as PDF");
        ImageButton settings = actionButton(R.drawable.ic_settings_v120, "Connection settings");
        home.setOnClickListener(v -> goHome());
        reload.setOnClickListener(v -> reloadCurrent());
        pdf.setOnClickListener(v -> printCurrentPage());
        settings.setOnClickListener(v -> showSettings());
        int actionSize = dp(42);
        bar.addView(home, new LinearLayout.LayoutParams(actionSize, actionSize));
        bar.addView(reload, new LinearLayout.LayoutParams(actionSize, actionSize));
        bar.addView(pdf, new LinearLayout.LayoutParams(actionSize, actionSize));
        bar.addView(settings, new LinearLayout.LayoutParams(actionSize, actionSize));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setVisibility(View.GONE);
        root.addView(pageProgress, new LinearLayout.LayoutParams(-1, dp(2)));

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(C_BG);
        content.addView(web, new FrameLayout.LayoutParams(-1,-1));

        loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(28),dp(28),dp(28),dp(28));
        loading.setBackgroundColor(C_BG);
        ProgressBar spinner = new ProgressBar(this);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(44),dp(44)));
        loadingMsg = new TextView(this);
        loadingMsg.setTextColor(C_MUTED);
        loadingMsg.setTextSize(14);
        loadingMsg.setGravity(Gravity.CENTER);
        loadingMsg.setPadding(0,dp(18),0,0);
        loading.addView(loadingMsg);
        content.addView(loading, new FrameLayout.LayoutParams(-1,-1));

        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setPadding(dp(20),dp(24),dp(20),dp(24));
        offline.setBackgroundColor(C_BG);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22),dp(24),dp(22),dp(22));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_PANEL);
        cardBg.setCornerRadius(dp(22));
        cardBg.setStroke(dp(1), Color.rgb(37,65,84));
        card.setBackground(cardBg);

        ImageView offlineLogo = new ImageView(this);
        offlineLogo.setImageResource(R.drawable.alcomet_logo);
        offlineLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(offlineLogo, new LinearLayout.LayoutParams(dp(205),dp(62)));
        offlineTitle = new TextView(this);
        offlineTitle.setTextColor(C_TEXT);
        offlineTitle.setTextSize(21);
        offlineTitle.setTypeface(null,1);
        offlineTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams otp = new LinearLayout.LayoutParams(-1,-2);
        otp.setMargins(0,dp(18),0,0);
        card.addView(offlineTitle,otp);
        offlineMsg = new TextView(this);
        offlineMsg.setTextColor(C_MUTED);
        offlineMsg.setTextSize(13.5f);
        offlineMsg.setGravity(Gravity.CENTER);
        offlineMsg.setPadding(0,dp(10),0,dp(22));
        card.addView(offlineMsg);
        Button retry = button("Retry connection",true);
        retry.setOnClickListener(v -> connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true));
        card.addView(retry,new LinearLayout.LayoutParams(-1,dp(54)));
        Button cfg = button("Connection settings",false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,dp(54));
        cp.setMargins(0,dp(10),0,0);
        card.addView(cfg,cp);
        cfg.setOnClickListener(v -> showSettings());
        TextView hint = new TextView(this);
        hint.setText("Operator UI only  •  Admin 5050 blocked  •  v" + APP_VERSION);
        hint.setTextColor(Color.rgb(100,128,148));
        hint.setTextSize(9.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,dp(18),0,0);
        card.addView(hint);

        offline.addView(card, new LinearLayout.LayoutParams(-1,-2));
        content.addView(offline,new FrameLayout.LayoutParams(-1,-1));

        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private ImageButton actionButton(int drawable, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(10),dp(10),dp(10),dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(17,38,52));
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), Color.rgb(41,69,87));
        b.setBackground(bg);
        return b;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(null,1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? C_PRIMARY : C_PANEL_2);
        bg.setCornerRadius(dp(14));
        if (!primary) bg.setStroke(dp(1),Color.rgb(55,81,99));
        b.setBackground(bg);
        return b;
    }

    private void configureWeb() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);

        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web,false);

        web.addJavascriptInterface(pdfBridge, "AlcometMobile");

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int progress) {
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleNavigation(r == null ? null : r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleNavigation(url == null ? null : Uri.parse(url));
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap f) {
                setStatus("● LOADING",C_AMBER);
            }
            @Override public void onPageFinished(WebView v,String url) {
                if (!destroyed && config != null && config.sameOrigin(Uri.parse(url))) {
                    lastRequestedUrl = url;
                    injectMobileHelpers();
                    showWeb();
                    setStatus("● ONLINE",C_GREEN);
                }
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e) {
                if (r != null && r.isForMainFrame()) showOffline("Connection lost",friendlyWebError(e));
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse response) {
                if (r != null && r.isForMainFrame() && response != null) {
                    int code = response.getStatusCode();
                    if (code >= 500) showOffline("Server error","CoilReport returned HTTP " + code + ".");
                    else if (code == 401 || code == 403) showOffline("Access denied","Operator service returned HTTP " + code + ".");
                }
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e) {
                h.cancel();
                showOffline("Secure connection blocked","HTTPS certificate validation failed. Invalid certificates are never bypassed.");
            }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
                showOffline("WebView restarted","Android WebView stopped unexpectedly. Tap Retry to reconnect safely.");
                return true;
            }
        });

        web.setDownloadListener((url,ua,disp,mime,len) -> handleDownload(url,ua,disp,mime));
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String raw = uri.toString();
        if (raw.startsWith("blob:") || raw.startsWith("data:application/pdf")) {
            captureBlob(raw, "Alcomet_Coil_Report.pdf");
            return true;
        }
        if (config != null && config.sameOrigin(uri)) return false;
        if ("about:blank".equalsIgnoreCase(raw)) return false;
        toast("Blocked navigation outside the configured CoilReport Operator server.");
        return true;
    }

    private void injectMobileHelpers() {
        String js =
            "(function(){try{" +
            "if(window.__alcometMobile120)return;window.__alcometMobile120=true;" +
            "function fix(){document.querySelectorAll('a[target=\"_blank\"]').forEach(function(a){a.target='_self';});}" +
            "fix();new MutationObserver(fix).observe(document.documentElement,{subtree:true,childList:true});" +
            "document.addEventListener('click',function(e){" +
            "var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;" +
            "var h=a.href||'';if(h.indexOf('blob:')===0||h.indexOf('data:application/pdf')===0){" +
            "e.preventDefault();e.stopPropagation();AlcometMobile.requestBlob(h,a.download||'Alcomet_Coil_Report.pdf');}" +
            "},true);" +
            "}catch(_){}})();";
        try { web.evaluateJavascript(js,null); } catch (Exception ignored) {}
    }

    private void handleDownload(String url,String ua,String disp,String mime) {
        if (url == null) return;
        if (url.startsWith("blob:") || url.startsWith("data:application/pdf")) {
            captureBlob(url, safeDownloadName(disp,url,mime));
            return;
        }
        Uri u = Uri.parse(url);
        if (config == null || !config.sameOrigin(u)) {
            toast("Blocked external download.");
            return;
        }
        saveHttpDownload(url,ua,disp,mime);
    }

    private void saveHttpDownload(String url,String ua,String disp,String mime) {
        final String requestedName = safeDownloadName(disp,url,mime);
        toast("Saving " + requestedName + "…");
        executor.execute(() -> {
            HttpURLConnection con = null;
            try {
                String current = url;
                for (int redirect=0; redirect<4; redirect++) {
                    con = (HttpURLConnection)new URL(current).openConnection();
                    con.setConnectTimeout(6000);
                    con.setReadTimeout(30000);
                    con.setUseCaches(false);
                    con.setInstanceFollowRedirects(false);
                    if (ua != null && !ua.isEmpty()) con.setRequestProperty("User-Agent",ua);
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(current);
                    if (cookie != null && !cookie.isEmpty()) con.setRequestProperty("Cookie",cookie);
                    String referer = web.getUrl();
                    if (referer != null && config.sameOrigin(Uri.parse(referer))) con.setRequestProperty("Referer",referer);
                    int code = con.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = con.getHeaderField("Location");
                        if (loc == null) throw new IOException("Redirect without Location");
                        URL next = new URL(new URL(current),loc);
                        Uri nextUri = Uri.parse(next.toString());
                        if (!config.sameOrigin(nextUri)) throw new SecurityException("Blocked external redirect");
                        con.disconnect(); con = null; current = next.toString(); continue;
                    }
                    if (code != 200) throw new IOException("Server returned HTTP " + code);
                    String ct = cleanMime(con.getContentType(),mime);
                    String cd = con.getHeaderField("Content-Disposition");
                    String name = safeDownloadName(cd != null ? cd : disp,current,ct);
                    saveStreamToDownloads(con.getInputStream(),name,ct);
                    runOnUiThread(() -> toast("Saved to Downloads/Alcomet Coil Archive"));
                    return;
                }
                throw new IOException("Too many redirects");
            } catch (Exception e) {
                runOnUiThread(() -> toast("Save failed: " + safe(e)));
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    private String cleanMime(String primary,String fallback) {
        String m = primary;
        if (m == null || m.trim().isEmpty()) m = fallback;
        if (m == null || m.trim().isEmpty()) return "application/octet-stream";
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0,semi);
        return m.trim().toLowerCase(Locale.ROOT);
    }

    private String safeDownloadName(String disposition,String url,String mime) {
        String name = URLUtil.guessFileName(url,disposition,mime);
        if (name == null || name.trim().isEmpty()) name = "Alcomet_Coil_Report.pdf";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+","_").trim();
        if (name.length() > 100) name = name.substring(0,100);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("pdf") && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name += ".pdf";
        return name;
    }

    private void saveStreamToDownloads(InputStream in,String name,String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,name);
            values.put(MediaStore.Downloads.MIME_TYPE,mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/Alcomet Coil Archive");
            values.put(MediaStore.Downloads.IS_PENDING,1);
            Uri dst = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if (dst == null) throw new IOException("Cannot create Downloads file");
            boolean ok = false;
            try (InputStream src = in; OutputStream out = resolver.openOutputStream(dst,"w")) {
                if (out == null) throw new IOException("Cannot open Downloads file");
                byte[] buf = new byte[32768];
                int n;
                while ((n = src.read(buf)) != -1) out.write(buf,0,n);
                out.flush();
                ok = true;
            } finally {
                if (ok) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING,0);
                    resolver.update(dst,done,null,null);
                } else {
                    resolver.delete(dst,null,null);
                }
            }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create Downloads folder");
            File outFile = uniqueFile(dir,name);
            try (InputStream src=in; OutputStream out=new FileOutputStream(outFile)) {
                byte[] buf=new byte[32768]; int n; while((n=src.read(buf))!=-1) out.write(buf,0,n);
            }
        }
    }

    private File uniqueFile(File dir,String name) {
        File f = new File(dir,name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0,dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i=2;i<1000;i++) {
            f = new File(dir,stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir,System.currentTimeMillis() + "_" + name);
    }

    private void captureBlob(String url,String suggestedName) {
        if (url == null || !(url.startsWith("blob:") || url.startsWith("data:application/pdf"))) {
            toast("Unsupported PDF source.");
            return;
        }
        final String u = JSONObject.quote(url);
        final String name = JSONObject.quote(suggestedName == null ? "Alcomet_Coil_Report.pdf" : suggestedName);
        String js =
            "(async function(){try{" +
            "const u=" + u + ";const suggested=" + name + ";" +
            "const r=await fetch(u);const b=await r.blob();" +
            "const mime=(b.type||'application/pdf').toLowerCase();" +
            "if(mime.indexOf('pdf')<0)throw new Error('Only PDF blob saving is allowed');" +
            "if(b.size>" + MAX_BLOB_BYTES + ")throw new Error('PDF is larger than 60 MB');" +
            "const data=await new Promise((ok,bad)=>{const fr=new FileReader();fr.onload=()=>ok(String(fr.result).split(',')[1]||'');fr.onerror=()=>bad(fr.error||new Error('Read failed'));fr.readAsDataURL(b);});" +
            "AlcometMobile.begin(suggested,mime,b.size);" +
            "for(let i=0;i<data.length;i+=196608){AlcometMobile.chunk(data.substring(i,i+196608));}" +
            "AlcometMobile.finish();" +
            "}catch(e){AlcometMobile.failed(String(e&&e.message?e.message:e));}})();";
        try { web.evaluateJavascript(js,null); }
        catch (Exception e) { toast("PDF save failed: " + safe(e)); }
    }

    private void printCurrentPage() {
        if (web == null || web.getVisibility() != View.VISIBLE || web.getUrl() == null) {
            toast("Open a coil report first.");
            return;
        }
        try {
            PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
            if (pm == null) throw new IllegalStateException("Print service unavailable");
            String title = web.getTitle();
            if (title == null || title.trim().isEmpty()) title = "Alcomet_Coil_Report";
            title = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+","_");
            if (title.length() > 50) title = title.substring(0,50);
            PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(title);
            PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
            toast("Choose Save as PDF in the Android print screen.");
            pm.print(title,adapter,attrs);
        } catch (Exception e) {
            toast("PDF print failed: " + safe(e));
        }
    }

    private void connectAndOpen(String url,boolean healthFirst) {
        if (!valid(config)) {
            showOffline("Connection required",config == null ? "Set the CoilReport Operator server to begin." : config.validate());
            return;
        }
        if (url == null || !config.sameOrigin(Uri.parse(url))) url = homeUrl();
        final String target = url;
        lastRequestedUrl = target;
        hideKeyboard();
        if (!healthFirst) {
            showLoading("Opening Operator UI…");
            web.loadUrl(target);
            return;
        }
        if (!connectInFlight.compareAndSet(false,true)) return;
        showLoading("Connecting to Operator server…");
        executor.execute(() -> {
            HealthResult result = checkHealth(config);
            runOnUiThread(() -> {
                connectInFlight.set(false);
                if (destroyed) return;
                if (result.ok) {
                    setStatus("● ONLINE",C_GREEN);
                    web.loadUrl(target);
                } else showOffline("Operator server unavailable",result.message);
            });
        });
    }

    private HealthResult checkHealth(MainActivity.Config c) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection)new URL(c.baseUrl() + "/health").openConnection();
            con.setConnectTimeout(3500);
            con.setReadTimeout(3500);
            con.setUseCaches(false);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Accept","application/json,text/plain");
            int code = con.getResponseCode();
            if (code != 200) return HealthResult.fail("Server returned HTTP " + code + " from /health.");
            String body = readSmall(con.getInputStream());
            String version = null;
            try {
                JSONObject obj = new JSONObject(body);
                version = obj.optString("version",null);
                if (version != null && version.trim().isEmpty()) version = null;
            } catch (Exception ignored) {}
            return HealthResult.ok(version);
        } catch (SSLHandshakeException e) {
            return HealthResult.fail("HTTPS certificate validation failed.");
        } catch (Exception e) {
            return HealthResult.fail(networkMessage(e));
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String readSmall(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int total = 0,n;
        while (total < 4096 && (n=in.read(b,0,Math.min(b.length,4096-total))) > 0) {
            out.write(b,0,n); total += n;
        }
        in.close();
        return out.toString("UTF-8");
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
        String m = e.getDescription().toString();
        return m.length() > 120 ? m.substring(0,120) : m;
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && (
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        );
    }

    private void reloadCurrent() {
        if (!valid(config)) { showSettings(); return; }
        if (web.getVisibility() == View.VISIBLE && web.getUrl() != null && config.sameOrigin(Uri.parse(web.getUrl()))) {
            setStatus("● RELOADING",C_AMBER);
            web.reload();
        } else connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true);
    }

    private void goHome() {
        if (!valid(config)) { showSettings(); return; }
        web.clearHistory();
        connectAndOpen(homeUrl(),offline.getVisibility() == View.VISIBLE);
    }

    private String homeUrl() {
        return valid(config) ? config.baseUrl() + "/" : "about:blank";
    }

    private void showWeb() {
        loading.setVisibility(View.GONE);
        offline.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    private void showLoading(String m) {
        web.setVisibility(View.GONE);
        offline.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        loadingMsg.setText(m);
        setStatus("● CONNECTING",C_AMBER);
    }

    private void showOffline(String title,String msg) {
        web.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
        offline.setVisibility(View.VISIBLE);
        offlineTitle.setText(title);
        offlineMsg.setText(msg);
        setStatus("● OFFLINE",C_RED);
    }

    private void setStatus(String s,int c) {
        if (status != null) {
            status.setText(s);
            status.setTextColor(c);
        }
    }

    private void showSettings() {
        if (settingsShowing || destroyed) return;
        settingsShowing = true;
        MainActivity.Config current = config != null ? config : MainActivity.SecureStore.load(this);
        if (current == null) current = MainActivity.Config.defaults();

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20),dp(10),dp(20),dp(8));
        form.setBackgroundColor(C_PANEL);
        scroll.addView(form);

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.alcomet_logo);
        brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        form.addView(brand,new LinearLayout.LayoutParams(-1,dp(62)));

        TextView heading = new TextView(this);
        heading.setText("Operator connection");
        heading.setTextColor(C_TEXT);
        heading.setTextSize(18);
        heading.setTypeface(null,1);
        heading.setPadding(0,dp(4),0,dp(8));
        form.addView(heading);

        TextView note = new TextView(this);
        note.setText("Connect only to the Operator service. Admin port 5050 is blocked. HTTPS is recommended; private-LAN HTTP must be explicitly enabled.");
        note.setTextColor(C_MUTED);
        note.setTextSize(12.5f);
        note.setPadding(0,0,0,dp(14));
        form.addView(note);

        Spinner scheme = new Spinner(this);
        ArrayAdapter<String> schemes = new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"https","http"});
        scheme.setAdapter(schemes);
        scheme.setSelection(current.scheme.equals("http") ? 1 : 0);
        form.addView(scheme);

        EditText host = new EditText(this);
        host.setHint("192.168.40.206");
        host.setHintTextColor(Color.rgb(100,125,143));
        host.setTextColor(C_TEXT);
        host.setSingleLine(true);
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(current.host);
        form.addView(host);

        EditText port = new EditText(this);
        port.setHint("5051");
        port.setHintTextColor(Color.rgb(100,125,143));
        port.setTextColor(C_TEXT);
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(current.port));
        form.addView(port);

        Switch allow = new Switch(this);
        allow.setText("Allow unencrypted HTTP on private LAN");
        allow.setTextColor(C_TEXT);
        allow.setChecked(current.allowLanHttp);
        form.addView(allow);

        TextView state = new TextView(this);
        state.setText("Not tested");
        state.setTextColor(C_MUTED);
        state.setTextSize(12);
        state.setPadding(0,dp(12),0,0);
        form.addView(state);

        TextView ver = new TextView(this);
        ver.setText("Android v" + APP_VERSION);
        ver.setTextColor(Color.rgb(98,125,144));
        ver.setTextSize(10);
        ver.setPadding(0,dp(12),0,0);
        form.addView(ver);

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Connection settings")
            .setView(scroll)
            .setNegativeButton("Close",null)
            .setNeutralButton("Test",null)
            .setPositiveButton("Save & connect",null)
            .create();

        dlg.setOnDismissListener(d -> settingsShowing = false);
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                MainActivity.Config c = readConfig(scheme,host,port,allow);
                String err = c.validate();
                if (err != null) {
                    state.setText(err); state.setTextColor(C_RED); return;
                }
                state.setText("Testing…"); state.setTextColor(C_MUTED);
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                executor.execute(() -> {
                    HealthResult r = checkHealth(c);
                    runOnUiThread(() -> {
                        if (!dlg.isShowing()) return;
                        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                        state.setText(r.ok ? "Connected" + (r.version == null ? "" : " • CoilReport " + r.version) : r.message);
                        state.setTextColor(r.ok ? C_GREEN : C_RED);
                    });
                });
            });

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                MainActivity.Config c = readConfig(scheme,host,port,allow);
                String err = c.validate();
                if (err != null) {
                    state.setText(err); state.setTextColor(C_RED); return;
                }
                try {
                    String old = config == null ? null : config.originKey();
                    MainActivity.SecureStore.save(this,c);
                    config = c;
                    if (old == null || !old.equals(c.originKey())) {
                        web.stopLoading();
                        web.clearHistory();
                        lastRequestedUrl = null;
                    }
                    dlg.dismiss();
                    connectAndOpen(homeUrl(),true);
                } catch (Exception e) {
                    state.setText("Secure settings save failed: " + safe(e));
                    state.setTextColor(C_RED);
                }
            });
        });
        dlg.show();
    }

    private MainActivity.Config readConfig(Spinner s,EditText h,EditText p,Switch a) {
        int port = -1;
        try { port = Integer.parseInt(p.getText().toString().trim()); } catch (Exception ignored) {}
        return new MainActivity.Config(String.valueOf(s.getSelectedItem()),h.getText().toString().trim(),port,a.isChecked());
    }

    private void hideKeyboard() {
        try {
            View f = getCurrentFocus();
            if (f != null) {
                InputMethodManager i = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if (i != null) i.hideSoftInputFromWindow(f.getWindowToken(),0);
            }
        } catch (Exception ignored) {}
    }

    private void registerNetworkCallback() {
        if (networkCallback != null || Build.VERSION.SDK_INT < 24) return;
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    if (!destroyed && valid(config) && offline.getVisibility() == View.VISIBLE && !connectInFlight.get()) {
                        connectAndOpen(lastRequestedUrl != null ? lastRequestedUrl : homeUrl(),true);
                    }
                });
            }
            @Override public void onLost(Network n) {
                runOnUiThread(() -> { if (!hasNetwork()) setStatus("● OFFLINE",C_RED); });
            }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); }
        catch (Exception e) { networkCallback = null; }
    }

    private void unregisterNetworkCallback() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        networkCallback = null;
    }

    private static boolean valid(MainActivity.Config c) {
        return c != null && c.validate() == null;
    }

    private void toast(String s) {
        if (!destroyed) Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
    }

    private static String safe(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e == null ? "Unknown error" : e.getClass().getSimpleName();
        m = m.replace('\n',' ').replace('\r',' ').trim();
        return m.length() > 120 ? m.substring(0,120) : m;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now-lastBackAt < 1800) super.onBackPressed();
        else {
            lastBackAt = now;
            toast("Press Back again to close");
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterNetworkCallback();
        connectInFlight.set(false);
        pdfBridge.cancel();
        executor.shutdownNow();
        if (web != null) {
            try {
                web.removeJavascriptInterface("AlcometMobile");
                web.stopLoading();
                web.loadUrl("about:blank");
                web.clearHistory();
                web.removeAllViews();
                web.destroy();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private final class PdfBridge {
        private File temp;
        private OutputStream out;
        private long bytes;
        private String name;
        private String mime;
        private boolean active;

        @JavascriptInterface public void requestBlob(String url,String suggestedName) {
            runOnUiThread(() -> captureBlob(url,suggestedName));
        }

        @JavascriptInterface public synchronized void begin(String suggestedName,String sourceMime,long total) {
            cancelLocked();
            if (total < 0 || total > MAX_BLOB_BYTES) {
                failed("PDF is larger than 60 MB");
                return;
            }
            String m = sourceMime == null ? "" : sourceMime.toLowerCase(Locale.ROOT);
            if (!m.contains("pdf")) {
                failed("Only PDF blob saving is allowed");
                return;
            }
            try {
                name = safeDownloadName(suggestedName,"Alcomet_Coil_Report.pdf","application/pdf");
                mime = "application/pdf";
                temp = File.createTempFile("alcomet_pdf_",".tmp",getCacheDir());
                out = new BufferedOutputStream(new FileOutputStream(temp));
                bytes = 0;
                active = true;
            } catch (Exception e) {
                cancelLocked();
                failed("Cannot prepare PDF: " + safe(e));
            }
        }

        @JavascriptInterface public synchronized void chunk(String base64) {
            if (!active || out == null || base64 == null) return;
            try {
                byte[] data = android.util.Base64.decode(base64,android.util.Base64.DEFAULT);
                bytes += data.length;
                if (bytes > MAX_BLOB_BYTES) throw new IOException("PDF is larger than 60 MB");
                out.write(data);
            } catch (Exception e) {
                cancelLocked();
                failed("PDF transfer failed: " + safe(e));
            }
        }

        @JavascriptInterface public synchronized void finish() {
            if (!active || out == null || temp == null) return;
            File ready = temp;
            String readyName = name;
            String readyMime = mime;
            try {
                out.flush(); out.close();
            } catch (Exception e) {
                cancelLocked();
                failed("PDF finalization failed: " + safe(e));
                return;
            }
            out = null; temp = null; active = false; bytes = 0;
            runOnUiThread(() -> toast("Saving " + readyName + "…"));
            executor.execute(() -> {
                try (InputStream in = new BufferedInputStream(new FileInputStream(ready))) {
                    saveStreamToDownloads(in,readyName,readyMime);
                    runOnUiThread(() -> toast("PDF saved to Downloads/Alcomet Coil Archive"));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("PDF save failed: " + safe(e)));
                } finally {
                    if (!ready.delete()) ready.deleteOnExit();
                }
            });
        }

        @JavascriptInterface public void failed(String message) {
            runOnUiThread(() -> toast("PDF save failed: " + (message == null ? "Unknown error" : message)));
        }

        synchronized void cancel() { cancelLocked(); }

        private void cancelLocked() {
            active = false; bytes = 0;
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            out = null;
            if (temp != null) {
                try { temp.delete(); } catch (Exception ignored) {}
            }
            temp = null; name = null; mime = null;
        }
    }

    static final class HealthResult {
        final boolean ok;
        final String message,version;
        private HealthResult(boolean ok,String message,String version) {
            this.ok=ok; this.message=message; this.version=version;
        }
        static HealthResult ok(String v) { return new HealthResult(true,"Connected",v); }
        static HealthResult fail(String m) { return new HealthResult(false,m,null); }
    }
}
