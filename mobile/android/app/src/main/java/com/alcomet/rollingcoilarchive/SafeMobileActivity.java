package com.alcomet.rollingcoilarchive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Crash-resistant launcher for the Alcomet mobile shell. */
public class SafeMobileActivity extends MobileActivity {
    @Override public void onCreate(Bundle state) {
        try { super.onCreate(state); }
        catch (Throwable error) { showRecovery(error); }
    }

    private void showRecovery(Throwable error) {
        try {
            getWindow().setStatusBarColor(Color.rgb(7,17,26));
            getWindow().setNavigationBarColor(Color.rgb(7,17,26));

            LinearLayout root=new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(24),dp(30),dp(24),dp(30));
            root.setBackgroundColor(Color.rgb(7,17,26));

            TextView brand=text("ALCOMET",30,Color.WHITE,Typeface.BOLD);root.addView(brand);
            TextView title=text("Coil Archive recovery",21,Color.rgb(232,241,247),Typeface.BOLD);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(0,dp(18),0,0);root.addView(title,tp);
            TextView message=text("The mobile interface could not start. CoilReport server/archive data is not affected.\n\n"+safeName(error),14,Color.rgb(145,169,187),Typeface.NORMAL);
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.setMargins(0,dp(10),0,dp(22));root.addView(message,mp);

            Button retry=button("Retry",true);retry.setOnClickListener(v->recreate());root.addView(retry,new LinearLayout.LayoutParams(-1,dp(52)));
            Button reset=button("Reset connection",false);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(52));rp.setMargins(0,dp(9),0,0);reset.setOnClickListener(v->{getSharedPreferences("secure_connection",MODE_PRIVATE).edit().clear().commit();recreate();});root.addView(reset,rp);
            Button basic=button("Basic Operator mode",false);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(52));bp.setMargins(0,dp(9),0,0);basic.setOnClickListener(v->{try{startActivity(new Intent(this,MainActivity.class));}catch(Throwable ignored){}});root.addView(basic,bp);

            TextView version=text("Android production • v1.6.0",10,Color.rgb(90,117,136),Typeface.NORMAL);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.setMargins(0,dp(16),0,0);root.addView(version,vp);
            setContentView(root);
        } catch (Throwable fatal) {
            TextView fallback=text("ALCOMET Coil Archive\nRecovery required",20,Color.WHITE,Typeface.BOLD);fallback.setBackgroundColor(Color.rgb(7,17,26));setContentView(fallback);
        }
    }

    private TextView text(String s,float size,int color,int style){TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setTypeface(null,style);v.setGravity(Gravity.CENTER);return v;}
    private Button button(String text,boolean primary){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setTypeface(null,Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?Color.rgb(70,167,223):Color.rgb(20,43,59));bg.setCornerRadius(dp(14));if(!primary)bg.setStroke(dp(1),Color.rgb(55,81,99));b.setBackground(bg);return b;}
    private static String safeName(Throwable t){if(t==null)return"Unknown startup error";String name=t.getClass().getSimpleName();String msg=t.getMessage();if(msg==null||msg.trim().isEmpty())return name;msg=msg.replace('\n',' ').replace('\r',' ').trim();if(msg.length()>140)msg=msg.substring(0,140);return name+": "+msg;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
