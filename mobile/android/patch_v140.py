from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch_v140: missing pattern: {label}')
    s = s.replace(old, new, 1)

rep('private static final String APP_VERSION = "1.2.0";',
    'private static final String APP_VERSION = "1.4.0";', 'app version')

rep('private static final long MAX_BLOB_BYTES = 60L * 1024L * 1024L;',
    '''private static final long MAX_BLOB_BYTES = 60L * 1024L * 1024L;\n    private static final int FILE_CONNECT_TIMEOUT_MS = 10000;\n    private static final int FILE_READ_TIMEOUT_MS = 180000;''', 'download timeouts')

rep('private final AtomicBoolean connectInFlight = new AtomicBoolean(false);',
    '''private final AtomicBoolean connectInFlight = new AtomicBoolean(false);\n    private final AtomicBoolean downloadInFlight = new AtomicBoolean(false);''', 'download state')

rep('pdf.setOnClickListener(v -> printCurrentPage());',
    '''pdf.setOnClickListener(v -> saveCurrentDocument());\n        pdf.setOnLongClickListener(v -> { printCurrentPage(); return true; });''', 'pdf action')

rep('reload.setOnClickListener(v -> reloadCurrent());',
    '''reload.setOnClickListener(v -> reloadCurrent());\n        reload.setOnLongClickListener(v -> { clearWebCacheAndReload(); return true; });''', 'reload action')

rep('''        if (config != null && config.sameOrigin(uri)) return false;\n        if ("about:blank".equalsIgnoreCase(raw)) return false;''',
    '''        if (config != null && config.sameOrigin(uri)) {\n            if (isNativeDownloadUri(uri)) {\n                saveHttpDownload(raw, web == null ? null : web.getSettings().getUserAgentString(), null, null);\n                return true;\n            }\n            return false;\n        }\n        if ("about:blank".equalsIgnoreCase(raw)) return false;''', 'native route interception')

old_js = '''            "if(window.__alcometMobile120)return;window.__alcometMobile120=true;" +\n            "function fix(){document.querySelectorAll('a[target=\\\"_blank\\\"]').forEach(function(a){a.target='_self';});}" +\n            "fix();new MutationObserver(fix).observe(document.documentElement,{subtree:true,childList:true});" +\n            "document.addEventListener('click',function(e){" +\n            "var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;" +\n            "var h=a.href||'';if(h.indexOf('blob:')===0||h.indexOf('data:application/pdf')===0){" +\n            "e.preventDefault();e.stopPropagation();AlcometMobile.requestBlob(h,a.download||'Alcomet_Coil_Report.pdf');}" +\n            "},true);" +'''
new_js = '''            "if(window.__alcometMobile140)return;window.__alcometMobile140=true;" +\n            "function fix(){document.querySelectorAll('a[target=\\\"_blank\\\"]').forEach(function(a){a.target='_self';});}" +\n            "fix();new MutationObserver(fix).observe(document.documentElement,{subtree:true,childList:true});" +\n            "document.addEventListener('click',function(e){" +\n            "var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;" +\n            "var h=a.href||'';if(h.indexOf('blob:')===0||h.indexOf('data:application/pdf')===0){" +\n            "e.preventDefault();e.stopPropagation();AlcometMobile.requestBlob(h,a.download||'Alcomet_Coil_Report.pdf');return;}" +\n            "try{var u=new URL(h,location.href);var p=u.pathname||'';" +\n            "if(u.origin===location.origin&&(p.indexOf('/download-pdf/')===0||p==='/download-monthly-pdf'||p==='/download-monthly-excel'||p==='/yearly-statistics.csv')){" +\n            "e.preventDefault();e.stopPropagation();AlcometMobile.requestHttp(u.href,a.download||'');}}catch(_){ }" +\n            "},true);" +'''
rep(old_js, new_js, 'mobile javascript helpers')

start = s.index('    private void saveHttpDownload(String url,String ua,String disp,String mime) {')
end = s.index('    private String cleanMime(', start)
new_download = r'''    private void saveHttpDownload(String url,String ua,String disp,String mime) {
        if (url == null || config == null) return;
        Uri initial;
        try { initial = Uri.parse(url); }
        catch (Exception e) { toast("Invalid download URL."); return; }
        if (!config.sameOrigin(initial)) { toast("Blocked external download."); return; }
        if (!downloadInFlight.compareAndSet(false,true)) {
            toast("A file is already being prepared. Please wait.");
            return;
        }
        final String requestedName = safeDownloadName(disp,url,mime);
        setStatus("● SAVING FILE",C_AMBER);
        toast("Preparing " + requestedName + "…");
        executor.execute(() -> {
            HttpURLConnection con = null;
            try {
                String current = url;
                for (int redirect=0; redirect<5; redirect++) {
                    con = (HttpURLConnection)new URL(current).openConnection();
                    con.setConnectTimeout(FILE_CONNECT_TIMEOUT_MS);
                    con.setReadTimeout(FILE_READ_TIMEOUT_MS);
                    con.setUseCaches(false);
                    con.setInstanceFollowRedirects(false);
                    con.setRequestProperty("Accept","application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv,*/*;q=0.8");
                    if (ua != null && !ua.isEmpty()) con.setRequestProperty("User-Agent",ua);
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(current);
                    if (cookie != null && !cookie.isEmpty()) con.setRequestProperty("Cookie",cookie);
                    String referer = web == null ? null : web.getUrl();
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
                    if (code != 200) {
                        String detail = "";
                        try { detail = readSmall(con.getErrorStream()); } catch (Exception ignored) {}
                        if (detail != null) detail = detail.replace('\n',' ').replace('\r',' ').trim();
                        if (detail != null && detail.length() > 120) detail = detail.substring(0,120);
                        throw new IOException("Server returned HTTP " + code + (detail == null || detail.isEmpty() ? "" : ": " + detail));
                    }
                    String ct = cleanMime(con.getContentType(),mime);
                    String cd = con.getHeaderField("Content-Disposition");
                    String name = safeDownloadName(cd != null ? cd : disp,current,ct);
                    BufferedInputStream input = new BufferedInputStream(con.getInputStream(),32768);
                    if (expectsPdf(name,ct,current)) verifyPdfHeader(input);
                    saveStreamToDownloads(input,name,ct);
                    final String savedName = name;
                    runOnUiThread(() -> {
                        setStatus("● ONLINE",C_GREEN);
                        toast("Saved: " + savedName + " • Downloads/Alcomet Coil Archive");
                    });
                    return;
                }
                throw new IOException("Too many redirects");
            } catch (SocketTimeoutException e) {
                runOnUiThread(() -> {
                    setStatus("● ONLINE",C_GREEN);
                    toast("PDF generation timed out. CoilReport may still be preparing it; try Save again.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("● ONLINE",C_GREEN);
                    toast("Save failed: " + safe(e));
                });
            } finally {
                if (con != null) con.disconnect();
                downloadInFlight.set(false);
            }
        });
    }

    private boolean isNativeDownloadUri(Uri uri) {
        if (uri == null || config == null || !config.sameOrigin(uri)) return false;
        String p = uri.getPath();
        if (p == null) return false;
        return p.startsWith("/download-pdf/") ||
            p.equals("/download-monthly-pdf") ||
            p.equals("/download-monthly-excel") ||
            p.equals("/yearly-statistics.csv");
    }

    private boolean expectsPdf(String name,String mime,String url) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || m.contains("pdf") || u.contains("/download-pdf/") || u.contains("/download-monthly-pdf");
    }

    private void verifyPdfHeader(BufferedInputStream in) throws IOException {
        in.mark(16);
        byte[] sig = new byte[5];
        int off = 0;
        while (off < sig.length) {
            int n = in.read(sig,off,sig.length-off);
            if (n < 0) break;
            off += n;
        }
        in.reset();
        if (off != 5 || sig[0] != '%' || sig[1] != 'P' || sig[2] != 'D' || sig[3] != 'F' || sig[4] != '-') {
            throw new IOException("Server response is not a valid PDF");
        }
    }

    private void saveCurrentDocument() {
        if (web == null || web.getVisibility() != View.VISIBLE || web.getUrl() == null || config == null) {
            toast("Open a coil report first.");
            return;
        }
        Uri current;
        try { current = Uri.parse(web.getUrl()); }
        catch (Exception e) { printCurrentPage(); return; }
        if (!config.sameOrigin(current)) { printCurrentPage(); return; }
        String path = current.getPath();
        if (path != null && path.startsWith("/report/")) {
            String id = path.substring("/report/".length());
            if (!id.isEmpty()) {
                String target = config.baseUrl() + "/download-pdf/" + Uri.encode(id);
                saveHttpDownload(target,web.getSettings().getUserAgentString(),null,"application/pdf");
                return;
            }
        }
        if ("/monthly-report".equals(path)) {
            String machine = current.getQueryParameter("machine");
            String period = current.getQueryParameter("period");
            if (machine != null && period != null) {
                String target = config.baseUrl() + "/download-monthly-pdf?machine=" + Uri.encode(machine) + "&period=" + Uri.encode(period);
                saveHttpDownload(target,web.getSettings().getUserAgentString(),null,"application/pdf");
                return;
            }
        }
        toast("No direct server PDF on this page. Opening Android Print…");
        printCurrentPage();
    }

    private void clearWebCacheAndReload() {
        if (web == null) return;
        try {
            web.stopLoading();
            web.clearCache(true);
            toast("Web cache cleared. Reloading…");
        } catch (Exception ignored) {}
        reloadCurrent();
    }

'''
s = s[:start] + new_download + s[end:]

# Fix blob filename handling and verify the completed blob PDF before committing it to Downloads.
rep('name = safeDownloadName(suggestedName,"Alcomet_Coil_Report.pdf","application/pdf");',
    '''name = sanitizeSuggestedName(suggestedName,"Alcomet_Coil_Report.pdf","application/pdf");''', 'blob filename')

insert_before = '    private void saveStreamToDownloads(InputStream in,String name,String mime) throws IOException {'
helper = r'''    private String sanitizeSuggestedName(String suggested,String fallback,String mime) {
        String name = suggested == null ? "" : suggested.trim();
        if (name.isEmpty()) name = fallback;
        name = name.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]+","_").trim();
        if (name.length() > 100) name = name.substring(0,100);
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("pdf") && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name += ".pdf";
        return name;
    }

'''
if insert_before not in s:
    raise SystemExit('patch_v140: missing saveStreamToDownloads insertion point')
s = s.replace(insert_before, helper + insert_before, 1)

rep('''        @JavascriptInterface public void requestBlob(String url,String suggestedName) {\n            runOnUiThread(() -> captureBlob(url,suggestedName));\n        }''',
    '''        @JavascriptInterface public void requestBlob(String url,String suggestedName) {\n            runOnUiThread(() -> captureBlob(url,suggestedName));\n        }\n\n        @JavascriptInterface public void requestHttp(String url,String suggestedName) {\n            runOnUiThread(() -> {\n                try {\n                    Uri u = Uri.parse(url);\n                    if (config == null || !config.sameOrigin(u) || !isNativeDownloadUri(u)) {\n                        toast("Blocked unsupported download.");\n                        return;\n                    }\n                    saveHttpDownload(url,web == null ? null : web.getSettings().getUserAgentString(),null,null);\n                } catch (Exception e) {\n                    toast("Invalid download request.");\n                }\n            });\n        }''', 'http bridge')

# Validate blob output before copying it to Downloads.
rep('''                try (InputStream in = new BufferedInputStream(new FileInputStream(ready))) {\n                    saveStreamToDownloads(in,readyName,readyMime);''',
    '''                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(ready))) {\n                    verifyPdfHeader(in);\n                    saveStreamToDownloads(in,readyName,readyMime);''', 'blob signature validation')

p.write_text(s, encoding='utf-8')
print('patch_v140: MobileActivity.java updated successfully')
