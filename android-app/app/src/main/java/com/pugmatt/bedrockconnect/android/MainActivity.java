package com.pugmatt.bedrockconnect.android;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView logs;

    private final Runnable refreshLogs = new Runnable() {
        @Override
        public void run() {
            readLogs();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("BedrockConnect LAN", 24);
        root.addView(title);

        status = text("Telefono: " + localIp() + " · porta UDP 19132", 15);
        status.setPadding(0, dp(8), 0, dp(14));
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, dp(10));

        Button start = new Button(this);
        start.setText("Avvia");
        start.setOnClickListener(v -> startServer());
        actions.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button stop = new Button(this);
        stop.setText("Ferma");
        stop.setOnClickListener(v -> stopServer());
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);

        TextView hint = text("Poi, sulla PlayStation: Minecraft → Gioca → Mondi → BedrockConnect.", 14);
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint);

        logs = text("Nessun log.", 12);
        logs.setTextIsSelectable(true);
        logs.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logs);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshLogs);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshLogs);
        super.onPause();
    }

    private void startServer() {
        Intent intent = new Intent(this, BedrockServerService.class)
                .setAction(BedrockServerService.ACTION_START);
        startForegroundService(intent);
        status.setText("Avvio su " + localIp() + ":19132…");
    }

    private void stopServer() {
        Intent intent = new Intent(this, BedrockServerService.class)
                .setAction(BedrockServerService.ACTION_STOP);
        startService(intent);
        status.setText("Server arrestato");
    }

    private void readLogs() {
        File file = new File(new File(getFilesDir(), "logs"), "latest.log");
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - 24_000);
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            byte[] bytes = new byte[24_000];
            int size = input.read(bytes);
            if (size > 0) logs.setText(new String(bytes, 0, size, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logs.setText("Errore lettura log: " + e.getMessage());
        }
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private String localIp() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || wifi.getConnectionInfo() == null) return "IP non disponibile";
        int ip = wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return "IP non disponibile";
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }
}
