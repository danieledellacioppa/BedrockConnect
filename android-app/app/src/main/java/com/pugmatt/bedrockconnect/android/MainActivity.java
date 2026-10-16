package com.pugmatt.bedrockconnect.android;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 1200;
    private static final int MAX_LOG_BYTES = 32_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileReader = Executors.newSingleThreadExecutor();
    private final AtomicBoolean logReadInProgress = new AtomicBoolean();

    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView ipAddress;
    private TextView logs;
    private ScrollView logScroll;
    private Button startButton;
    private Button stopButton;

    private long lastLogModified = -1;
    private long lastLogLength = -1;
    private long lastStateModified = -1;

    private final Runnable refreshUi = new Runnable() {
        @Override
        public void run() {
            refreshServerState();
            refreshLogsIfNeeded();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.status_dot);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        ipAddress = findViewById(R.id.ip_address);
        logs = findViewById(R.id.logs);
        logScroll = findViewById(R.id.log_scroll);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);

        String ip = localIp();
        ipAddress.setText(getString(R.string.listen_address, ip));
        statusDetail.setText(getString(R.string.ready_detail, ip));

        startButton.setOnClickListener(v -> startServer());
        stopButton.setOnClickListener(v -> stopServer());
        findViewById(R.id.copy_address).setOnClickListener(v -> copyAddress());

        renderState("STOPPED", getString(R.string.ready_detail, ip));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String ip = localIp();
        ipAddress.setText(getString(R.string.listen_address, ip));
        handler.removeCallbacks(refreshUi);
        handler.post(refreshUi);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshUi);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        fileReader.shutdownNow();
        super.onDestroy();
    }

    private void startServer() {
        renderState("STARTING", getString(R.string.starting_detail, localIp()));
        Intent intent = new Intent(this, BedrockServerService.class)
                .setAction(BedrockServerService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopServer() {
        renderState("STOPPING", getString(R.string.stopping_detail));
        Intent intent = new Intent(this, BedrockServerService.class)
                .setAction(BedrockServerService.ACTION_STOP);
        startService(intent);
    }

    private void copyAddress() {
        String address = localIp();
        if (getString(R.string.ip_unavailable).equals(address)) {
            Toast.makeText(this, R.string.ip_unavailable_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.clipboard_label), address + ":19132"));
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show();
    }

    private void refreshServerState() {
        File file = new File(getFilesDir(), BedrockServerService.STATE_FILE);
        if (!file.isFile() || file.lastModified() == lastStateModified) return;
        lastStateModified = file.lastModified();

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 4096)];
            int count = input.read(bytes);
            if (count <= 0) return;
            JSONObject state = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            renderState(state.optString("state", "STOPPED"), state.optString("detail", ""));
        } catch (Exception ignored) {
            // The foreground service writes this file atomically. A later refresh will retry.
            lastStateModified = -1;
        }
    }

    private void renderState(String state, String detail) {
        boolean active = "RUNNING".equals(state);
        boolean busy = "STARTING".equals(state) || "STOPPING".equals(state);

        if (active) {
            statusDot.setBackgroundResource(R.drawable.status_dot_active);
            statusTitle.setText(R.string.status_active);
        } else if ("STARTING".equals(state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_pending);
            statusTitle.setText(R.string.status_starting);
        } else if ("STOPPING".equals(state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_pending);
            statusTitle.setText(R.string.status_stopping);
        } else if ("ERROR".equals(state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_error);
            statusTitle.setText(R.string.status_error);
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_idle);
            statusTitle.setText(R.string.status_idle);
        }

        statusDetail.setText(detail.isEmpty()
                ? getString(R.string.ready_detail, localIp())
                : detail);
        startButton.setEnabled(!active && !busy);
        stopButton.setEnabled(active || "STARTING".equals(state));
    }

    private void refreshLogsIfNeeded() {
        File file = new File(new File(getFilesDir(), "logs"), "latest.log");
        if (!file.isFile()) return;

        long modified = file.lastModified();
        long length = file.length();
        if (modified == lastLogModified && length == lastLogLength) return;
        if (!logReadInProgress.compareAndSet(false, true)) return;

        fileReader.execute(() -> {
            try {
                String text = readLogTail(file);
                lastLogModified = modified;
                lastLogLength = length;
                handler.post(() -> showLogs(text));
            } catch (Exception error) {
                handler.post(() -> logs.setText(
                        getString(R.string.log_read_error, error.getMessage())));
            } finally {
                logReadInProgress.set(false);
            }
        });
    }

    private String readLogTail(File file) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - MAX_LOG_BYTES);
            input.seek(start);
            byte[] bytes = new byte[(int) Math.min(MAX_LOG_BYTES, input.length() - start)];
            int count = input.read(bytes);
            if (count <= 0) return getString(R.string.no_logs);

            int first = 0;
            if (start > 0) {
                while (first < count && bytes[first] != '\n') first++;
                if (first < count) first++;
            }
            return new String(bytes, first, count - first, StandardCharsets.UTF_8);
        }
    }

    private void showLogs(String text) {
        boolean followTail = logScroll.getScrollY() + logScroll.getHeight()
                >= logs.getHeight() - dp(48);
        logs.setText(text);
        if (followTail) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private String localIp() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || wifi.getConnectionInfo() == null) return getString(R.string.ip_unavailable);
        int ip = wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return getString(R.string.ip_unavailable);
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }
}
