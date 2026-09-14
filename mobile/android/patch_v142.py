from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch_v142: missing pattern: {label}')
    s = s.replace(old, new, 1)

rep('private static final String APP_VERSION = "1.4.1";',
    'private static final String APP_VERSION = "1.4.2";', 'app version')

rep('ImageButton home = actionButton(R.drawable.ic_home_v120, "Home");',
    'ImageButton home = actionButton(R.drawable.ic_nav_home_v142, "Home", false);', 'home icon')
rep('ImageButton reload = actionButton(R.drawable.ic_refresh_v120, "Reload");',
    'ImageButton reload = actionButton(R.drawable.ic_nav_refresh_v142, "Reload", false);', 'reload icon')
rep('ImageButton pdf = actionButton(R.drawable.ic_pdf_v120, "Save current page as PDF");',
    'ImageButton pdf = actionButton(R.drawable.ic_nav_download_v142, "Save PDF", true);', 'pdf icon')
rep('ImageButton settings = actionButton(R.drawable.ic_settings_v120, "Connection settings");',
    'ImageButton settings = actionButton(R.drawable.ic_nav_tune_v142, "Connection settings", false);', 'settings icon')

rep('int actionSize = dp(38);', 'int actionSize = dp(40);', 'touch target size')
rep('lp.setMargins(dp(2),0,0,0);', 'lp.setMargins(dp(4),0,0,0);', 'toolbar action spacing')

rep('private ImageButton actionButton(int drawable, String description) {',
    'private ImageButton actionButton(int drawable, String description, boolean primary) {', 'action button signature')
rep('b.setScaleType(ImageView.ScaleType.CENTER);',
    'b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);', 'icon scale type')
rep('b.setPadding(dp(8),dp(8),dp(8),dp(8));',
    'b.setPadding(dp(9),dp(9),dp(9),dp(9));', 'icon optical padding')
rep('''        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(17,38,52));
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), Color.rgb(41,69,87));
        b.setBackground(bg);
        return b;''',
    '''        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? Color.rgb(12,53,76) : Color.TRANSPARENT);
        bg.setCornerRadius(dp(40));
        bg.setStroke(dp(1), primary ? Color.rgb(69,154,203) : Color.rgb(47,72,89));
        b.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(primary ? dp(1) : 0);
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(description);
        return b;''', 'circular toolbar button style')

p.write_text(s, encoding='utf-8')
print('patch_v142: new circular outline toolbar and redesigned icon set applied')
