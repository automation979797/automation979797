package com.alcomet.rollingcoilarchive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Crash-resistant launcher for the Alcomet mobile shell.
 * If MobileActivity cannot initialize, the operator gets a recovery screen
 * instead of an application that immediately disappears.
 */
public class SafeMobileActivity extends MobileActivity {
    @Override public void onCreate(Bundle state) {
        try {
            super.onCreate(state);
        } catch (Throwable error) {
            showRecovery(error);
        }
    }

    private void showRecovery(Throwable error) {
        try {
            getWindow().setStatusBarColor(Color.rgb(7,17,26));
            getWindow().setNavigationBarColor(Color.rgb(7,17,26));

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(24), dp(30), dp(24), dp(30));
            root.setBackgroundColor(Color.rgb(7,17,26));

            TextView brand = new TextView(this);
            brand.setText("ALCOMET");
            brand.setTextColor(Color.WHITE);
            brand.setTextSize(30);
            brand.setTypeface(null, 1);
            brand.setGravity(Gravity.CENTER);
            root.addView(brand);

            TextView title = new TextView(this);
            title.setText("Coil Archive recovery mode");
            title.setTextColor(Color.rgb(232,241,247));
            title.setTextSize(21);
            title.setTypeface(null, 1);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,-2);
            tp.setMargins(0,dp(20),0,0);
            root.addView(title,tp);

            TextView message = new TextView(this);
            message.setText("The advanced mobile interface could not finish startup. Your CoilReport server and archive data are not affected.\n\nError: " + safeName(error));
            message.setTextColor(Color.rgb(145,169,187));
            message.setTextSize(14);
            message.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1,-2);
            mp.setMargins(0,dp(12),0,dp(22));
            root.addView(message,mp);

            Button retry = button("Retry mobile interface", true);
            retry.setOnClickListener(v -> recreate());
            root.addView(retry,new LinearLayout.LayoutParams(-1,dp(54)));

            Button reset = button("Reset connection settings & retry", false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,dp(54));
            rp.setMargins(0,dp(10),0,0);
            reset.setOnClickListener(v -> {
                getSharedPreferences("secure_connection", MODE_PRIVATE).edit().clear().commit();
                recreate();
            });
            root.addView(reset,rp);

            Button basic = button("Open basic Operator mode", false);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,dp(54));
            bp.setMargins(0,dp(10),0,0);
            basic.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(this, MainActivity.class);
                    startActivity(i);
                } catch (Throwable ignored) {}
            });
            root.addView(basic,bp);

            TextView version = new TextView(this);
            version.setText("Android production recovery • v1.4.0");
            version.setTextColor(Color.rgb(90,117,136));
            version.setTextSize(10);
            version.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1,-2);
            vp.setMargins(0,dp(18),0,0);
            root.addView(version,vp);

            setContentView(root);
        } catch (Throwable fatal) {
            TextView fallback = new TextView(this);
            fallback.setText("ALCOMET Coil Archive\nStartup recovery required");
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(20);
            fallback.setGravity(Gravity.CENTER);
            fallback.setBackgroundColor(Color.rgb(7,17,26));
            setContentView(fallback);
        }
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(null,1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? Color.rgb(70,167,223) : Color.rgb(20,43,59));
        bg.setCornerRadius(dp(14));
        if (!primary) bg.setStroke(dp(1),Color.rgb(55,81,99));
        b.setBackground(bg);
        return b;
    }

    private static String safeName(Throwable t) {
        if (t == null) return "Unknown startup error";
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) return name;
        msg = msg.replace('\n',' ').replace('\r',' ').trim();
        if (msg.length() > 140) msg = msg.substring(0,140);
        return name + ": " + msg;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
