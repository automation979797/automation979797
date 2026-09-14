from pathlib import Path

ROOT = Path('mobile/android')
p = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java'
s = p.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'patch_v150: missing pattern: {label}')
    s = s.replace(old, new, count)

rep('private static final String APP_VERSION = "1.4.2";', 'private static final String APP_VERSION = "1.5.0";', 'app version')
rep('private final PdfBridge pdfBridge = new PdfBridge();', '''private final PdfBridge pdfBridge = new PdfBridge();
    private static final int REQ_SCAN = 1500;
    private ImageButton scanAction, watchAction;
    private final AtomicBoolean featureRefreshInFlight = new AtomicBoolean(false);
    private volatile MobileFeatureClient.Policy mobilePolicy = MobileFeatureClient.Policy.disabled();
    private volatile Uri lastSavedUri;
    private volatile String lastSavedMime = "";
    private volatile String lastSavedName = "";''', 'mobile feature fields')

rep('@Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }',
    '@Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); refreshMobilePolicy(); }', 'onResume policy refresh')
rep('connectAndOpen(homeUrl(), true);', 'connectAndOpen(startUrlFromIntent(), true);', 'initial deep link')

rep('''        web = new WebView(this);
        web.setBackgroundColor(C_BG);
        content.addView(web, new FrameLayout.LayoutParams(-1,-1));''',
    '''        web = new WebView(this);
        web.setBackgroundColor(C_BG);
        content.addView(web, new FrameLayout.LayoutParams(-1,-1));
        installMobileFeatureRail(content);''', 'feature rail')

rep('''                    lastRequestedUrl = url;
                    injectMobileHelpers();
                    showWeb();''',
    '''                    lastRequestedUrl = url;
                    injectMobileHelpers();
                    refreshMobilePolicy();
                    showWeb();''', 'page policy refresh')

marker = '    private void configureWeb() {'
if marker not in s:
    raise SystemExit('patch_v150: configureWeb insertion point missing')
methods = r'''    private void installMobileFeatureRail(FrameLayout content) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        scanAction = actionButton(R.drawable.ic_scan_v150, "Scan coil QR / barcode", true);
        watchAction = actionButton(R.drawable.ic_watch_v150, "Watchlist", false);
        scanAction.setVisibility(View.GONE);
        watchAction.setVisibility(View.GONE);
        scanAction.setOnClickListener(v -> startBarcodeScan());
        watchAction.setOnClickListener(v -> openWatchlistForCurrentPage());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48),dp(48));
        bp.setMargins(0,dp(7),0,0);
        rail.addView(scanAction,bp);
        rail.addView(watchAction,bp);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(58),dp(120),Gravity.END|Gravity.BOTTOM);
        rp.setMargins(0,0,dp(10),dp(14));
        content.addView(rail,rp);
    }

    private String startUrlFromIntent() {
        if (!valid(config)) return "about:blank";
        try {
            String path = getIntent() == null ? null : getIntent().getStringExtra("open_path");
            if (path != null && path.startsWith("/") && !path.startsWith("//")) return config.baseUrl() + path;
        } catch (Exception ignored) {}
        return homeUrl();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (valid(config)) connectAndOpen(startUrlFromIntent(), false);
    }

    private void refreshMobilePolicy() {
        if (!valid(config) || destroyed || !featureRefreshInFlight.compareAndSet(false,true)) return;
        final MainActivity.Config snapshot = config;
        executor.execute(() -> {
            MobileFeatureClient.Policy p;
            try { p = MobileFeatureClient.fetchPolicy(snapshot); }
            catch (Exception e) { p = MobileFeatureClient.Policy.disabled(); }
            final MobileFeatureClient.Policy ready = p;
            runOnUiThread(() -> {
                featureRefreshInFlight.set(false);
                if (destroyed) return;
                mobilePolicy = ready;
                if (scanAction != null) scanAction.setVisibility(ready.qrBarcodeSearch ? View.VISIBLE : View.GONE);
                if (watchAction != null) watchAction.setVisibility(ready.watchlist ? View.VISIBLE : View.GONE);
                NotificationJobService.configure(this, ready);
            });
        });
    }

    private void startBarcodeScan() {
        if (!mobilePolicy.qrBarcodeSearch) { toast("Scanner is disabled by Engineering."); return; }
        try { startActivityForResult(new Intent(this,BarcodeScannerActivity.class),REQ_SCAN); }
        catch (Exception e) { toast("Scanner unavailable: " + safe(e)); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK || data == null) return;
        String raw = data.getStringExtra(BarcodeScannerActivity.EXTRA_RESULT);
        MobileWatchStore.ScanTarget target = MobileWatchStore.parseScan(raw);
        if (target == null) { toast("Barcode does not contain a valid coil ID."); return; }
        String path = "/?q=" + Uri.encode(target.coil);
        if (target.machine != null && !target.machine.isEmpty()) path += "&machine=" + Uri.encode(target.machine);
        toast("Searching coil " + target.coil);
        connectAndOpen(config.baseUrl()+path,false);
    }

    private void openWatchlistForCurrentPage() {
        if (!mobilePolicy.watchlist) { toast("Watchlist is disabled by Engineering."); return; }
        if (web == null) { showWatchlistDialog(""); return; }
        String js = "(function(){var c=document.querySelector('meta[name=\\\"alcomet-coil\\\"]');return c?c.content:'';})()";
        try {
            web.evaluateJavascript(js, value -> {
                String coil = "";
                try { Object x = new org.json.JSONTokener(value).nextValue(); if (x instanceof String) coil=(String)x; } catch (Exception ignored) {}
                showWatchlistDialog(coil);
            });
        } catch (Exception e) { showWatchlistDialog(""); }
    }

    private void showWatchlistDialog(String prefill) {
        if (!mobilePolicy.watchlist) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(8),dp(18),0);
        EditText input = new EditText(this);
        input.setHint("Coil ID"); input.setSingleLine(true); input.setText(prefill == null ? "" : prefill);
        box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        TextView list = new TextView(this); list.setTextColor(Color.DKGRAY); list.setTextSize(12); list.setPadding(0,dp(12),0,0);
        java.util.Set<String> watches = MobileWatchStore.getWatches(this);
        list.setText(watches.isEmpty() ? "No watched coils." : "Watching:\n• " + android.text.TextUtils.join("\n• ",watches));
        box.addView(list,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Android Watchlist").setView(box)
            .setNegativeButton("Close",null).setNeutralButton("Remove",null).setPositiveButton("Add / Watch",null).create();
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String coil = MobileWatchStore.normalizeCoil(input.getText().toString());
                if (coil.isEmpty()) { input.setError("Enter a coil ID"); return; }
                MobileWatchStore.addWatch(this,coil);
                toast("Watching " + coil);
                ensureNotificationPermissionAndSchedule();
                dlg.dismiss();
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String coil = MobileWatchStore.normalizeCoil(input.getText().toString());
                if (coil.isEmpty()) { input.setError("Enter the coil to remove"); return; }
                MobileWatchStore.removeWatch(this,coil); toast("Removed " + coil); dlg.dismiss();
            });
        });
        dlg.show();
    }

    private void ensureNotificationPermissionAndSchedule() {
        if (!mobilePolicy.notifications) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1501);
            return;
        }
        NotificationJobService.configure(this,mobilePolicy);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if (requestCode==1501 && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) NotificationJobService.configure(this,mobilePolicy);
    }

    private void maybeOfferSavedFileActions(String name,String mime) {
        if (!mobilePolicy.nativePdfShare || lastSavedUri == null || mime == null || !mime.toLowerCase(Locale.ROOT).contains("pdf")) return;
        final Uri uri = lastSavedUri;
        new AlertDialog.Builder(this).setTitle("PDF saved").setMessage(name + "\nDownloads/Alcomet Coil Archive")
            .setNegativeButton("Close",null)
            .setNeutralButton("Open",(d,w)->openSavedUri(uri,mime))
            .setPositiveButton("Share",(d,w)->shareSavedUri(uri,mime,name)).show();
    }

    private void openSavedUri(Uri uri,String mime) {
        try { Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); }
        catch (Exception e) { toast("No app is available to open this PDF."); }
    }

    private void shareSavedUri(Uri uri,String mime,String name) {
        try {
            Intent i=new Intent(Intent.ACTION_SEND); i.setType(mime); i.putExtra(Intent.EXTRA_STREAM,uri); i.putExtra(Intent.EXTRA_SUBJECT,name); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"Share CoilReport PDF"));
        } catch (Exception e) { toast("Share failed: " + safe(e)); }
    }

'''
s = s.replace(marker, methods + marker, 1)

rep('import android.content.ContentValues;', 'import android.content.ContentValues;\nimport android.content.pm.PackageManager;', 'PackageManager import')

rep('''    private void saveStreamToDownloads(InputStream in,String name,String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {''',
    '''    private void saveStreamToDownloads(InputStream in,String name,String mime) throws IOException {
        lastSavedUri = null; lastSavedMime = mime == null ? "" : mime; lastSavedName = name == null ? "" : name;
        if (Build.VERSION.SDK_INT >= 29) {''', 'saved file tracking start')
rep('''                    resolver.update(dst,done,null,null);''', '''                    resolver.update(dst,done,null,null);
                    lastSavedUri = dst;''', 'saved MediaStore uri')
rep('''            try (InputStream src=in; OutputStream out=new FileOutputStream(outFile)) {
                byte[] buf=new byte[32768]; int n; while((n=src.read(buf))!=-1) out.write(buf,0,n);
            }
        }
    }

    private File uniqueFile''',
    '''            try (InputStream src=in; OutputStream out=new FileOutputStream(outFile)) {
                byte[] buf=new byte[32768]; int n; while((n=src.read(buf))!=-1) out.write(buf,0,n);
            }
        }
        if (lastSavedUri != null) {
            final String readyName=lastSavedName, readyMime=lastSavedMime;
            runOnUiThread(() -> maybeOfferSavedFileActions(readyName,readyMime));
        }
    }

    private File uniqueFile''', 'saved file actions')

p.write_text(s, encoding='utf-8')
