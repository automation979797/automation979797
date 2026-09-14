package com.alcomet.rollingcoilarchive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class FavoritesActivity extends Activity {
    private static final int REQ_SCAN=1610;
    private static final int REQ_NOTIFY=1611;
    private static final int BG=Color.rgb(7,17,26);
    private static final int PANEL=Color.rgb(13,31,44);
    private static final int PANEL2=Color.rgb(20,43,59);
    private static final int TEXT=Color.rgb(232,241,247);
    private static final int MUTED=Color.rgb(145,169,187);
    private static final int PRIMARY=Color.rgb(70,167,223);
    private static final int GREEN=Color.rgb(53,201,131);
    private static final int RED=Color.rgb(255,102,122);

    private MainActivity.Config config;
    private volatile MobileFeatureClient.Policy policy=MobileFeatureClient.Policy.disabled();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout listBox;
    private TextView state,count,empty;
    private EditText addInput;
    private Button scanAdd,testNotification,clearAll;
    private boolean destroyed;
    private final Runnable policyTick=new Runnable(){@Override public void run(){if(!destroyed){refreshPolicy();handler.postDelayed(this,60000L);}}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        config=MainActivity.SecureStore.load(this);
        buildUi();
        refreshPolicy();
    }

    @Override protected void onResume(){super.onResume();refreshPolicy();handler.removeCallbacks(policyTick);handler.postDelayed(policyTick,60000L);}
    @Override protected void onPause(){handler.removeCallbacks(policyTick);super.onPause();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12),dp(8),dp(12),dp(8));
        top.setBackgroundColor(PANEL);
        Button back=smallButton("← Back",false);back.setOnClickListener(v->finish());
        top.addView(back,new LinearLayout.LayoutParams(dp(92),dp(44)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(12),0,0,0);
        TextView title=new TextView(this);title.setText("FAVORITE COILS");title.setTextColor(TEXT);title.setTextSize(18);title.setTypeface(null,android.graphics.Typeface.BOLD);titles.addView(title);
        state=new TextView(this);state.setText("Checking Engineering policy…");state.setTextColor(MUTED);state.setTextSize(11);titles.addView(state);
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(62)));

        ScrollView scroll=new ScrollView(this);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(14),dp(14),dp(24));

        LinearLayout addCard=card();
        TextView addTitle=label("Add favorite coil",16,TEXT);addTitle.setTypeface(null,android.graphics.Typeface.BOLD);addCard.addView(addTitle);
        TextView addHint=label("Add manually or scan a coil barcode. A successful scan is added automatically.",12,MUTED);addHint.setPadding(0,dp(3),0,dp(10));addCard.addView(addHint);
        addInput=new EditText(this);addInput.setHint("Coil ID");addInput.setSingleLine(true);addInput.setTextColor(TEXT);addInput.setHintTextColor(MUTED);addInput.setBackgroundColor(PANEL2);addInput.setPadding(dp(12),0,dp(12),0);addCard.addView(addInput,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout addActions=new LinearLayout(this);addActions.setGravity(Gravity.CENTER_VERTICAL);addActions.setPadding(0,dp(10),0,0);
        Button add=smallButton("Add Favorite",true);add.setOnClickListener(v->addFavorite(addInput.getText().toString(),true));
        scanAdd=smallButton("Scan + Add",false);scanAdd.setOnClickListener(v->startScanToAdd());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(48),1);ap.setMargins(0,0,dp(5),0);addActions.addView(add,ap);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(48),1);sp.setMargins(dp(5),0,0,0);addActions.addView(scanAdd,sp);
        addCard.addView(addActions,new LinearLayout.LayoutParams(-1,-2));
        body.addView(addCard,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tools=card();
        count=label("0 favorites",15,TEXT);count.setTypeface(null,android.graphics.Typeface.BOLD);tools.addView(count);
        TextView toolsHint=label("Open reports, remove favorites, or control notifications for each coil here.",12,MUTED);toolsHint.setPadding(0,dp(3),0,dp(10));tools.addView(toolsHint);
        LinearLayout toolRow=new LinearLayout(this);
        testNotification=smallButton("Test Notification",false);testNotification.setOnClickListener(v->testNotification());
        clearAll=smallButton("Clear All",false);clearAll.setOnClickListener(v->confirmClear());
        LinearLayout.LayoutParams t1=new LinearLayout.LayoutParams(0,dp(46),1);t1.setMargins(0,0,dp(5),0);toolRow.addView(testNotification,t1);
        LinearLayout.LayoutParams t2=new LinearLayout.LayoutParams(0,dp(46),1);t2.setMargins(dp(5),0,0,0);toolRow.addView(clearAll,t2);
        tools.addView(toolRow,new LinearLayout.LayoutParams(-1,-2));
        body.addView(tools,marginTop(dp(10)));

        empty=label("No favorite coils yet.",14,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(24),0,dp(18));body.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        listBox=new LinearLayout(this);listBox.setOrientation(LinearLayout.VERTICAL);body.addView(listBox,new LinearLayout.LayoutParams(-1,-2));

        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void refreshPolicy(){
        if(destroyed)return;
        if(config==null||config.validate()!=null){block("Connection settings are unavailable.");return;}
        state.setText("Checking Engineering policy…");
        final MainActivity.Config cfg=config;
        executor.execute(()->{
            MobileFeatureClient.Policy p;
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
            });
        });
    }

    private void block(String msg){
        policy=MobileFeatureClient.Policy.disabled();
        state.setText(msg);state.setTextColor(RED);
        scanAdd.setVisibility(View.GONE);testNotification.setVisibility(View.GONE);
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        handler.postDelayed(()->{if(!isFinishing())finish();},500);
    }

    private void renderFavorites(){
        if(!policy.watchlist)return;
        Set<String> favorites=MobileWatchStore.getWatches(this);
        count.setText(favorites.size()+ (favorites.size()==1?" favorite":" favorites"));
        empty.setVisibility(favorites.isEmpty()?View.VISIBLE:View.GONE);
        clearAll.setEnabled(!favorites.isEmpty());
        listBox.removeAllViews();
        for(String coil:favorites)listBox.addView(favoriteCard(coil),marginTop(dp(9)));
    }

    private View favoriteCard(String coil){
        LinearLayout card=card();
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView id=label(coil,18,TEXT);id.setTypeface(null,android.graphics.Typeface.BOLD);head.addView(id,new LinearLayout.LayoutParams(0,-2,1));
        if(policy.notifications){
            Switch alerts=new Switch(this);alerts.setText("Alerts");alerts.setTextColor(TEXT);alerts.setChecked(!MobileWatchStore.isMuted(this,coil));
            alerts.setOnCheckedChangeListener((b,on)->{MobileWatchStore.setMuted(this,coil,!on);Toast.makeText(this,on?"Alerts ON for "+coil:"Alerts OFF for "+coil,Toast.LENGTH_SHORT).show();});
            head.addView(alerts,new LinearLayout.LayoutParams(-2,dp(46)));
        }
        card.addView(head,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=label(policy.notifications?(MobileWatchStore.isMuted(this,coil)?"Favorite • notifications muted":"Favorite • notifications enabled"):"Favorite coil",12,MUTED);sub.setPadding(0,0,0,dp(8));card.addView(sub);
        LinearLayout actions=new LinearLayout(this);
        Button open=smallButton("Open Report",true);open.setOnClickListener(v->openCoil(coil));
        Button remove=smallButton("Remove",false);remove.setOnClickListener(v->{MobileWatchStore.removeWatch(this,coil);renderFavorites();});
        LinearLayout.LayoutParams p1=new LinearLayout.LayoutParams(0,dp(46),1);p1.setMargins(0,0,dp(5),0);actions.addView(open,p1);
        LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(46),1);p2.setMargins(dp(5),0,0,0);actions.addView(remove,p2);
        card.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }

    private void addFavorite(String raw,boolean clear){
        if(!policy.watchlist){block("Favorites are disabled by Engineering.");return;}
        String coil=MobileWatchStore.normalizeCoil(raw);
        if(coil.isEmpty()){addInput.setError("Enter a valid coil ID");return;}
        MobileWatchStore.addWatch(this,coil);
        if(clear)addInput.setText("");
        renderFavorites();
        Toast.makeText(this,"Added "+coil+" to Favorites",Toast.LENGTH_SHORT).show();
    }

    private void startScanToAdd(){
        if(!policy.watchlist||!policy.qrBarcodeSearch){Toast.makeText(this,"Barcode scanner is disabled by Engineering.",Toast.LENGTH_SHORT).show();return;}
        startActivityForResult(new Intent(this,BarcodeScannerActivity.class),REQ_SCAN);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_SCAN||resultCode!=RESULT_OK||data==null)return;
        MobileWatchStore.ScanTarget target=MobileWatchStore.parseScan(data.getStringExtra(BarcodeScannerActivity.EXTRA_RESULT));
        if(target==null){Toast.makeText(this,"Barcode does not contain a valid coil ID.",Toast.LENGTH_LONG).show();return;}
        addFavorite(target.coil,false);
    }

    private void openCoil(String coil){
        if(!policy.watchlist||config==null)return;
        Intent i=new Intent(this,SafeMobileActivity.class).putExtra("open_path","/?q="+Uri.encode(coil)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void testNotification(){
        if(!policy.watchlist||!policy.notifications){Toast.makeText(this,"Notifications are disabled by Engineering.",Toast.LENGTH_SHORT).show();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);return;}
        if(NotificationJobService.showTestNotification(this))Toast.makeText(this,"Test notification sent.",Toast.LENGTH_SHORT).show();
        else Toast.makeText(this,"Notification test could not be delivered.",Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_NOTIFY){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)testNotification();
            else Toast.makeText(this,"Android notification permission is required.",Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear(){
        if(MobileWatchStore.getWatches(this).isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Clear all favorites?").setMessage("This removes every favorite coil from this phone. CoilReport archive data is not changed.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear All",(d,w)->{MobileWatchStore.clearWatches(this);renderFavorites();}).show();
    }

    private LinearLayout card(){
        LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(14),dp(13),dp(14),dp(13));
        GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),Color.rgb(42,72,91));x.setBackground(bg);return x;
    }
    private TextView label(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button smallButton(String text,boolean primary){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(13);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?PRIMARY:PANEL2);bg.setCornerRadius(dp(12));if(!primary)bg.setStroke(dp(1),Color.rgb(55,81,99));b.setBackground(bg);return b;}
    private LinearLayout.LayoutParams marginTop(int px){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,px,0,0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);executor.shutdownNow();super.onDestroy();}
}
