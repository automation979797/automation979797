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

# Compact/professional native header for phones: smaller controls, more spacing and less visual weight.
rep('bar.setPadding(dp(10), dp(3), dp(6), dp(3));',
    'bar.setPadding(dp(8), dp(2), dp(5), dp(2));', 'toolbar padding')
rep('new LinearLayout.LayoutParams(dp(94), dp(42))',
    'new LinearLayout.LayoutParams(dp(84), dp(38))', 'toolbar logo size')
rep('logoLp.setMargins(0,0,dp(6),0);',
    'logoLp.setMargins(0,0,dp(4),0);', 'toolbar logo margin')
rep('title.setTextSize(10.5f);', 'title.setTextSize(10f);', 'toolbar title size')
rep('status.setTextSize(8.5f);', 'status.setTextSize(8f);', 'toolbar status size')
rep('''        int actionSize = dp(42);\n        bar.addView(home, new LinearLayout.LayoutParams(actionSize, actionSize));\n        bar.addView(reload, new LinearLayout.LayoutParams(actionSize, actionSize));\n        bar.addView(pdf, new LinearLayout.LayoutParams(actionSize, actionSize));\n        bar.addView(settings, new LinearLayout.LayoutParams(actionSize, actionSize));\n        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));''',
    '''        int actionSize = dp(38);\n        bar.addView(home, toolbarActionLayout(actionSize));\n        bar.addView(reload, toolbarActionLayout(actionSize));\n        bar.addView(pdf, toolbarActionLayout(actionSize));\n        bar.addView(settings, toolbarActionLayout(actionSize));\n        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));''', 'toolbar action sizing')
rep('''    private ImageButton actionButton(int drawable, String description) {\n        ImageButton b = new ImageButton(this);''',
    '''    private LinearLayout.LayoutParams toolbarActionLayout(int size) {\n        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size,size);\n        lp.setMargins(dp(2),0,0,0);\n        return lp;\n    }\n\n    private ImageButton actionButton(int drawable, String description) {\n        ImageButton b = new ImageButton(this);''', 'toolbar action layout helper')
rep('b.setPadding(dp(10),dp(10),dp(10),dp(10));',
    'b.setPadding(dp(8),dp(8),dp(8),dp(8));', 'toolbar icon padding')
rep('bg.setCornerRadius(dp(13));',
    'bg.setCornerRadius(dp(11));', 'toolbar icon corner radius')

p.write_text(s, encoding='utf-8')
print('patch_v141: PDF threading fixed and top toolbar polished')
