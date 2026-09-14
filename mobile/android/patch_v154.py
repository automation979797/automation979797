from pathlib import Path

ROOT = Path('mobile/android')


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'patch_v154: missing pattern {label} in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# Version displayed by the native wrapper.
mobile = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java'
replace_once(mobile,
    'private static final String APP_VERSION = "1.5.2";',
    'private static final String APP_VERSION = "1.5.3";',
    'mobile version')

# Scanner must always resume automatically after screen lock/task switch. v1.5.2
# released the camera in onPause but relied on surfaceCreated to fire again, which
# is not guaranteed when the existing SurfaceView survives the pause.
scanner = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/BarcodeScannerActivity.java'
replace_once(scanner,
'''    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        build();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
        }
    }
''',
'''    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        build();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&surface!=null){
            handler.postDelayed(()->{
                try{
                    SurfaceHolder h=surface.getHolder();
                    if(h.getSurface()!=null&&h.getSurface().isValid())startCamera(h);
                }catch(Exception ignored){}
            },120);
        }
    }
''',
    'scanner automatic resume')
replace_once(scanner,
'''            if(r!=null){
                if(status!=null)status.setText("FOUND "+r.getText()+" • opening automatically…");
                vibrateRead();''',
'''            if(r!=null){
                decoding=true;
                if(status!=null)status.setText("FOUND "+r.getText()+" • opening automatically…");
                vibrateRead();''',
    'scanner duplicate callback guard')

# The existing scanner uses a short confirmation vibration. Declare the normal
# Android permission so it is not silently denied on devices that enforce it.
manifest = ROOT/'app/src/main/AndroidManifest.xml'
replace_once(manifest,
    '    <uses-permission android:name="android.permission.CAMERA" />\n',
    '    <uses-permission android:name="android.permission.CAMERA" />\n    <uses-permission android:name="android.permission.VIBRATE" />\n',
    'vibrate permission')

# Favorites page: do not pretend a duplicate favorite was newly added.
fav = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/FavoritesActivity.java'
replace_once(fav,
'''        MobileWatchStore.addWatch(this,coil);
        if(clear)addInput.setText("");
        renderFavorites();
        Toast.makeText(this,"Added "+coil+" to Favorites",Toast.LENGTH_SHORT).show();''',
'''        boolean existed=MobileWatchStore.getWatches(this).contains(coil);
        MobileWatchStore.addWatch(this,coil);
        if(clear)addInput.setText("");
        renderFavorites();
        Toast.makeText(this,existed?coil+" is already in Favorites":"Added "+coil+" to Favorites",Toast.LENGTH_SHORT).show();''',
    'duplicate favorite feedback')

print('patch_v154: Android v1.5.3 reliability and naming support applied')
