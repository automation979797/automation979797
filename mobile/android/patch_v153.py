from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'patch_v153: missing pattern: {label}')
    s = s.replace(old, new, count)

rep('private static final String APP_VERSION = "1.5.1";', 'private static final String APP_VERSION = "1.5.2";', 'app version')
rep('watchAction = actionButton(R.drawable.ic_watch_v150, "Watchlist", false);', 'watchAction = actionButton(R.drawable.ic_watch_v150, "Favorite coils", false);', 'favorites button label')
rep('''                    openWatchlistForCurrentPage();''', '''                    openFavoritesPage();''', 'launch favorites page')

marker = '''    private void openWatchlistForCurrentPage() {'''
if marker not in s:
    raise SystemExit('patch_v153: favorites insertion point missing')
method = '''    private void openFavoritesPage() {\n        if (!mobilePolicy.watchlist) { toast("Favorites are disabled by Engineering."); return; }\n        try { startActivity(new Intent(this,FavoritesActivity.class)); }\n        catch (Exception e) { toast("Favorites page unavailable: " + safe(e)); }\n    }\n\n'''
s = s.replace(marker, method + marker, 1)

# Main scanner already returns immediately on a successful decode. Make the behavior explicit
# to the user when the scanned value is accepted and the search is launched.
rep('''        toast("Searching coil " + target.coil);\n        connectAndOpen(config.baseUrl()+path,false);''',
    '''        toast("Barcode read • opening coil " + target.coil);\n        connectAndOpen(config.baseUrl()+path,false);''',
    'automatic scan result open')

p.write_text(s, encoding='utf-8')
print('patch_v153: Android v1.5.2 favorites page and automatic scan flow applied')
