from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'patch_v152: missing pattern: {label}')
    s = s.replace(old, new, count)

rep('private static final String APP_VERSION = "1.5.0";', 'private static final String APP_VERSION = "1.5.1";', 'app version')
rep('''    private volatile String lastSavedName = "";''', '''    private volatile String lastSavedName = "";
    private boolean pendingNotificationTest = false;''', 'notification test state')

rep('''        list.setText(watches.isEmpty() ? "No watched coils." : "Watching:\\n• " + android.text.TextUtils.join("\\n• ",watches));
        box.addView(list,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Android Watchlist").setView(box)''',
    '''        list.setText(watches.isEmpty() ? "No watched coils." : "Watching:\\n• " + android.text.TextUtils.join("\\n• ",watches));
        box.addView(list,new LinearLayout.LayoutParams(-1,-2));
        if (mobilePolicy.notifications) {
            Button testNotification = new Button(this);
            testNotification.setText("Test notification");
            testNotification.setAllCaps(false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,dp(46));
            tp.setMargins(0,dp(12),0,0);
            box.addView(testNotification,tp);
            testNotification.setOnClickListener(v -> testNotificationNow());
        }
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Android Watchlist").setView(box)''',
    'watchlist notification test button')

rep('''    private void ensureNotificationPermissionAndSchedule() {
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
''',
    '''    private void ensureNotificationPermissionAndSchedule() {
        if (!mobilePolicy.notifications) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1501);
            return;
        }
        NotificationJobService.configure(this,mobilePolicy);
    }

    private void testNotificationNow() {
        if (!mobilePolicy.watchlist || !mobilePolicy.notifications) {
            toast("Notifications are disabled by Engineering.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationTest = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1501);
            return;
        }
        pendingNotificationTest = false;
        if (NotificationJobService.showTestNotification(this)) toast("Test notification sent.");
        else toast("Notification test could not be delivered.");
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if (requestCode==1501) {
            boolean granted = grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED;
            if (granted) {
                NotificationJobService.configure(this,mobilePolicy);
                if (pendingNotificationTest) {
                    pendingNotificationTest=false;
                    if (NotificationJobService.showTestNotification(this)) toast("Test notification sent.");
                }
            } else {
                pendingNotificationTest=false;
                toast("Android notification permission is required.");
            }
        }
    }
''',
    'notification test behavior')

p.write_text(s, encoding='utf-8')
print('patch_v152: Android v1.5.1 notification test and barcode reliability support applied')
