package com.pugmatt.bedrockconnect.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import main.com.pyratron.pugmatt.bedrockconnect.BedrockConnect;

public final class BedrockServerService extends Service {
    public static final String ACTION_START = "bedrock.start";
    public static final String ACTION_STOP = "bedrock.stop";
    public static final String STATE_FILE = "server_state.json";
    private static final String CHANNEL = "bedrock_server";
    private static final int NOTIFICATION_ID = 19132;

    private volatile boolean started;
    private volatile String lastState = "STOPPED";
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Server in preparazione…"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;

        writeState("STARTING", "Preparazione del bridge sulla porta UDP 19132…");
        updateNotification("Server in preparazione…");
        acquireNetworkLocks();
        new Thread(this::runServer, "SonyMERDA-main").start();
        return START_NOT_STICKY;
    }

    private void runServer() {
        try {
            File data = getFilesDir();
            File logs = new File(data, "logs");
            if (!logs.exists()) logs.mkdirs();
            File customServers = new File(data, "custom_servers.json");
            File customLanguage = new File(data, "language_android.json");

            JSONObject server = new JSONObject();
            server.put("name", "DIOCALANDIA");
            server.put("address", "85.14.210.14");
            server.put("port", 21200);
            JSONArray list = new JSONArray();
            list.put(server);
            try (FileOutputStream output = new FileOutputStream(customServers, false)) {
                output.write(list.toString().getBytes(StandardCharsets.UTF_8));
            }

            JSONObject serverInfo = new JSONObject();
            serverInfo.put("motd", "SonyMERDA");
            serverInfo.put("subMotd", "Sony è la MERDA? Si!");
            JSONObject language = new JSONObject();
            language.put("serverInfo", serverInfo);
            try (FileOutputStream output = new FileOutputStream(customLanguage, false)) {
                output.write(language.toString().getBytes(StandardCharsets.UTF_8));
            }

            System.setProperty("logFile", "true");
            System.setProperty("logDirectory", logs.getAbsolutePath());
            System.setProperty("log4j2.disableJmx", "true");
            BedrockConnect.setWorkingDirectory(data);
            BedrockConnect.main(new String[]{
                    "debug=true",
                    "custom_servers=" + customServers.getAbsolutePath(),
                    "language=" + customLanguage.getAbsolutePath()
            });
            if (BedrockConnect.getServer() == null) {
                throw new IllegalStateException("Il server non è stato inizializzato");
            }
            writeState("RUNNING", "Tieni sempre a PS5 e u telefn ncopp a stessa Wi-Fi osino i tarantell");
            updateNotification("Attivo sulla porta UDP 19132");
        } catch (Throwable error) {
            writeState("ERROR", "Avvio non riuscito: " + error.getClass().getSimpleName());
            updateNotification("Errore: " + error.getClass().getSimpleName());
            android.util.Log.e("SonyMERDA", "Server failed", error);
            releaseLocks();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void acquireNetworkLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("BedrockConnect:LAN");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BedrockConnect:Server");
            wakeLock.acquire();
        }
    }

    private void shutdown() {
        writeState("STOPPING", "Arresto del bridge in corso…");
        updateNotification("Arresto in corso…");
        new Thread(() -> {
            try {
                BedrockConnect.stop();
            } catch (Throwable ignored) {
            }
            writeState("STOPPED", "Pronto per essere avviato");
            releaseLocks();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            Process.killProcess(Process.myPid());
        }, "BedrockConnect-stop").start();
    }

    private void releaseLocks() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (!"ERROR".equals(lastState) && !"STOPPED".equals(lastState)) {
            writeState("STOPPED", "Il servizio è stato arrestato");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, BedrockServerService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.primary))
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_stop), stop).build())
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private void writeState(String state, String detail) {
        lastState = state;
        AtomicFile file = new AtomicFile(new File(getFilesDir(), STATE_FILE));
        FileOutputStream output = null;
        try {
            JSONObject value = new JSONObject();
            value.put("state", state);
            value.put("detail", detail);
            value.put("updatedAt", System.currentTimeMillis());
            output = file.startWrite();
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            android.util.Log.w("BedrockConnect", "Unable to persist server state", error);
        }
    }
}
