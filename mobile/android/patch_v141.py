from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch_v141: missing pattern: {label}')
    s = s.replace(old, new, 1)

rep('private static final String APP_VERSION = "1.4.0";',
    'private static final String APP_VERSION = "1.4.1";', 'app version')

# WebView methods must only be called on the UI thread. Capture all WebView/CookieManager
# state before entering the executor and use immutable snapshots in the worker.
rep('''        final String requestedName = safeDownloadName(disp,url,mime);\n        setStatus("● SAVING FILE",C_AMBER);\n        toast("Preparing " + requestedName + "…");\n        executor.execute(() -> {''',
    '''        final String requestedName = safeDownloadName(disp,url,mime);\n        final String requestReferer = web == null ? null : web.getUrl();\n        final String requestCookie = android.webkit.CookieManager.getInstance().getCookie(url);\n        setStatus("● SAVING FILE",C_AMBER);\n        toast("Preparing " + requestedName + "…");\n        executor.execute(() -> {''', 'capture UI-thread download state')

rep('''                    String cookie = android.webkit.CookieManager.getInstance().getCookie(current);\n                    if (cookie != null && !cookie.isEmpty()) con.setRequestProperty("Cookie",cookie);\n                    String referer = web == null ? null : web.getUrl();\n                    if (referer != null && config.sameOrigin(Uri.parse(referer))) con.setRequestProperty("Referer",referer);''',
    '''                    if (requestCookie != null && !requestCookie.isEmpty()) con.setRequestProperty("Cookie",requestCookie);\n                    if (requestReferer != null && config.sameOrigin(Uri.parse(requestReferer))) con.setRequestProperty("Referer",requestReferer);''', 'remove worker WebView calls')

p.write_text(s, encoding='utf-8')
print('patch_v141: PDF worker no longer touches WebView/CookieManager UI state')
