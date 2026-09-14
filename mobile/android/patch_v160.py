from pathlib import Path

ROOT = Path('mobile/android')


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'patch_v160: missing pattern {label} in {path}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

mobile = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java'
replace_once(mobile,
    'private static final String APP_VERSION = "1.5.3";',
    'private static final String APP_VERSION = "1.6.0";',
    'mobile version')
replace_once(mobile,
    '    private ImageButton scanAction, watchAction;\n',
    '    private ImageButton scanAction, watchAction;\n    private LinearLayout mobileFeatureRail;\n',
    'feature rail field')
replace_once(mobile,
'''        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);''',
'''        mobileFeatureRail = new LinearLayout(this);
        LinearLayout rail = mobileFeatureRail;
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(dp(3),dp(2),dp(3),dp(2));''',
    'compact feature rail')
replace_once(mobile,
'''        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48),dp(48));
        bp.setMargins(0,dp(7),0,0);
        rail.addView(scanAction,bp);
        rail.addView(watchAction,bp);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(58),dp(120),Gravity.END|Gravity.BOTTOM);
        rp.setMargins(0,0,dp(10),dp(14));
        content.addView(rail,rp);''',
'''        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(dp(48),dp(48));
        bp1.setMargins(dp(3),0,dp(3),0);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(dp(48),dp(48));
        bp2.setMargins(dp(3),0,dp(3),0);
        rail.addView(scanAction,bp1);
        rail.addView(watchAction,bp2);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(112),dp(54),Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM);
        rp.setMargins(0,0,0,dp(9));
        content.addView(rail,rp);''',
    'horizontal rail layout')
replace_once(mobile,
'''        if (scanAction != null) scanAction.setVisibility(ready.qrBarcodeSearch ? View.VISIBLE : View.GONE);
        if (watchAction != null) watchAction.setVisibility(ready.watchlist ? View.VISIBLE : View.GONE);
        NotificationJobService.configure(this, ready);''',
'''        if (scanAction != null) scanAction.setVisibility(ready.qrBarcodeSearch ? View.VISIBLE : View.GONE);
        if (watchAction != null) watchAction.setVisibility(ready.watchlist ? View.VISIBLE : View.GONE);
        if (mobileFeatureRail != null) mobileFeatureRail.setVisibility((ready.qrBarcodeSearch || ready.watchlist) ? View.VISIBLE : View.GONE);
        NotificationJobService.configure(this, ready);''',
    'hide empty feature rail')

scanner = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/BarcodeScannerActivity.java'
replace_once(scanner,
    'title.setText("AUTO SCAN COIL QR / BARCODE");',
    'title.setText("SCAN COIL");',
    'scanner title')
replace_once(scanner,
    'status.setText("Scanning starts automatically • keep the whole 1D barcode horizontal inside the blue frame");',
    'status.setText("AUTO SCANNING • QR + barcode");',
    'scanner initial status')
replace_once(scanner,
    'if(status!=null)status.setText("AUTO SCANNING • point the camera at the code • result opens automatically");',
    'if(status!=null)status.setText("AUTO SCANNING • result opens automatically");',
    'scanner active status')

fav = ROOT/'app/src/main/java/com/alcomet/rollingcoilarchive/FavoritesActivity.java'
replace_once(fav,
    '    private Button scanAdd,testNotification,clearAll;\n',
    '    private Button addFavoriteButton,scanAdd,testNotification,clearAll;\n',
    'favorites button field')
replace_once(fav,
    'title.setText("FAVORITE COILS");',
    'title.setText("FAVORITES");',
    'favorites title')
replace_once(fav,
    'TextView addTitle=label("Add favorite coil",16,TEXT);',
    'TextView addTitle=label("Add coil",16,TEXT);',
    'favorites add title')
replace_once(fav,
    'TextView addHint=label("Add manually or scan a coil barcode. A successful scan is added automatically.",12,MUTED);',
    'TextView addHint=label("Enter a coil number or scan its code.",12,MUTED);',
    'favorites add hint')
replace_once(fav,
    'Button add=smallButton("Add Favorite",true);add.setOnClickListener(v->addFavorite(addInput.getText().toString(),true));',
    'addFavoriteButton=smallButton("Add",true);addFavoriteButton.setOnClickListener(v->addFavorite(addInput.getText().toString(),true));',
    'favorites add button')
replace_once(fav,
    'addActions.addView(add,ap);',
    'addActions.addView(addFavoriteButton,ap);',
    'favorites add button view')
replace_once(fav,
    'TextView toolsHint=label("Open reports, remove favorites, or control notifications for each coil here.",12,MUTED);',
    'TextView toolsHint=label("Open, remove or choose alerts for each favorite.",12,MUTED);',
    'favorites tools hint')
replace_once(fav,
'''            MobileFeatureClient.Policy p;
            try{p=MobileFeatureClient.fetchPolicy(cfg);}catch(Exception e){p=MobileFeatureClient.Policy.disabled();}
            final MobileFeatureClient.Policy ready=p;
            runOnUiThread(()->{
                if(destroyed)return;
                policy=ready;
                if(!ready.watchlist){block("Favorites are disabled by Engineering.");return;}
                state.setText("Enabled by Engineering");state.setTextColor(GREEN);
                scanAdd.setVisibility(ready.qrBarcodeSearch?View.VISIBLE:View.GONE);
                testNotification.setVisibility(ready.notifications?View.VISIBLE:View.GONE);
                NotificationJobService.configure(this,ready);
                renderFavorites();
            });''',
'''            MobileFeatureClient.Policy p=null;
            boolean fetched=true;
            try{p=MobileFeatureClient.fetchPolicy(cfg);}catch(Exception e){fetched=false;}
            final MobileFeatureClient.Policy ready=p;
            final boolean policyFetched=fetched;
            runOnUiThread(()->{
                if(destroyed)return;
                if(!policyFetched||ready==null){policyUnavailable();return;}
                policy=ready;
                if(!ready.watchlist){block("Favorites are disabled by Engineering.");return;}
                state.setText("Enabled by Engineering");state.setTextColor(GREEN);
                addFavoriteButton.setEnabled(true);
                addInput.setEnabled(true);
                scanAdd.setVisibility(ready.qrBarcodeSearch?View.VISIBLE:View.GONE);
                testNotification.setVisibility(ready.notifications?View.VISIBLE:View.GONE);
                NotificationJobService.configure(this,ready);
                renderFavorites();
            });''',
    'favorites policy refresh')
replace_once(fav,
'''    private void block(String msg){
        policy=MobileFeatureClient.Policy.disabled();
        state.setText(msg);state.setTextColor(RED);
        scanAdd.setVisibility(View.GONE);testNotification.setVisibility(View.GONE);
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        handler.postDelayed(()->{if(!isFinishing())finish();},500);
    }
''',
'''    private void policyUnavailable(){
        policy=MobileFeatureClient.Policy.disabled();
        state.setText("Server policy unavailable • retrying");state.setTextColor(RED);
        addFavoriteButton.setEnabled(false);addInput.setEnabled(false);
        scanAdd.setVisibility(View.GONE);testNotification.setVisibility(View.GONE);
        clearAll.setEnabled(false);
        listBox.removeAllViews();empty.setText("Favorites are temporarily unavailable.");empty.setVisibility(View.VISIBLE);
        NotificationJobService.configure(this,policy);
    }

    private void block(String msg){
        policy=MobileFeatureClient.Policy.disabled();
        state.setText(msg);state.setTextColor(RED);
        addFavoriteButton.setEnabled(false);addInput.setEnabled(false);
        scanAdd.setVisibility(View.GONE);testNotification.setVisibility(View.GONE);
        NotificationJobService.configure(this,policy);
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        finish();
    }
''',
    'favorites fail closed without transient close')
replace_once(fav,
'''            alerts.setOnCheckedChangeListener((b,on)->{MobileWatchStore.setMuted(this,coil,!on);Toast.makeText(this,on?"Alerts ON for "+coil:"Alerts OFF for "+coil,Toast.LENGTH_SHORT).show();});''',
'''            alerts.setOnCheckedChangeListener((b,on)->{
                MobileWatchStore.setMuted(this,coil,!on);
                if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);
                }
                Toast.makeText(this,on?"Alerts ON for "+coil:"Alerts OFF for "+coil,Toast.LENGTH_SHORT).show();
            });''',
    'favorite alert permission')

print('patch_v160: Android v1.6.0 simplified UI and reliability fixes applied')
