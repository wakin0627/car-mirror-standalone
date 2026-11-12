package com.niofirefly.standalone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class MirrorService extends Service implements MirrorWebServer.Listener {
    static final String ACTION_START = "com.niofirefly.standalone.START";
    static final String ACTION_STOP = "com.niofirefly.standalone.STOP";
    static volatile String state = "未连接";
    private static final String TAG = "CarMirrorService";
    private static final String SOCKET = "scrcpy_01234567";
    private static final String ROOT_DIR = "/data/local/tmp/niofirefly-standalone";
    private volatile boolean starting;
    private volatile boolean stopping;
    private MirrorWebServer web;
    private Process scrcpy;
    private LocalSocket videoSocket, audioSocket, controlSocket;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private File rootScript;
    private volatile boolean dimmed;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (rootScript == null) try { rootScript = copyAsset("root-setup.sh"); } catch (Exception ignored) {}
            stopSelf(); return START_NOT_STICKY;
        }
        if (starting || web != null) return START_STICKY;
        starting = true;
        state = "正在启动";
        showNotification();
        new Thread(() -> {
            try { startMirror(intent); }
            catch (Exception e) {
                Log.e(TAG, "Start failed", e);
                if (!stopping) state = "启动失败：" + e.getMessage();
                else cleanupResources();
                stopSelf();
            } finally { starting = false; }
        }, "car-mirror-start").start();
        return START_STICKY;
    }

    private void showNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("mirror", "车机投屏", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, MirrorService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "mirror")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("车机投屏运行中")
                .setContentText("点“断开”恢复手机显示和声音")
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "断开", stopIntent).build())
                .build();
        startForeground(101, n);
    }

    private void startMirror(Intent intent) throws Exception {
        String size = intent == null ? "1200x1920" : intent.getStringExtra("size");
        if (!"1200x1920".equals(size) && !"800x1280".equals(size) && !"1600x2560".equals(size)) size = "1200x1920";
        int maxSize = size.equals("800x1280") ? 1280 : size.equals("1600x2560") ? 2560 : 1920;
        String orientation = intent == null ? "@270" : intent.getStringExtra("orientation");
        if (!"@90".equals(orientation)) orientation = "@270";
        rootScript = copyAsset("root-setup.sh");
        File jar = copyAsset("scrcpy-server.jar");
        root("sh " + rootScript.getAbsolutePath() + " install " + jar.getAbsolutePath());
        ensureActive();
        root("sh " + rootScript.getAbsolutePath() + " start " + size + " 320 " + android.os.Process.myPid());
        ensureActive();
        web = new MirrorWebServer(this, this);
        web.start();
        ensureActive();
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                if (!stopping) { state = "Wi-Fi 已断开"; stopSelf(); }
            }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarMirror:stream");
        wakeLock.acquire();
        String command = "su shell -c 'CLASSPATH=" + ROOT_DIR + "/scrcpy-server.jar /system/bin/app_process "
                + "/system/bin com.genymobile.scrcpy.Server 3.1 log_level=info scid=01234567 "
                + "max_size=" + maxSize + " capture_orientation=" + orientation + " video_bit_rate=8000000 max_fps=60 "
                + "audio=true audio_source=output audio_codec=raw control=true cleanup=false "
                + "tunnel_forward=true send_device_meta=false send_codec_meta=false "
                + "send_dummy_byte=false send_frame_meta=true'";
        scrcpy = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ensureActive();
        final Process captureProcess = scrcpy;
        new Thread(() -> {
            try (InputStream log = captureProcess.getInputStream()) {
                byte[] b = new byte[2048]; int n;
                while ((n = log.read(b)) >= 0) Log.i(TAG, new String(b, 0, n, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            if (!stopping) {
                state = "采集进程已退出";
                stopSelf();
            }
        }, "car-mirror-scrcpy-log").start();
        videoSocket = connectSocket();
        ensureActive();
        audioSocket = connectSocket();
        ensureActive();
        audioSocket.setSoTimeout(15000);
        controlSocket = connectSocket();
        ensureActive();
        new Thread(() -> readVideo(videoSocket), "car-mirror-video").start();
        new Thread(() -> readAudio(audioSocket), "car-mirror-audio").start();
        state = "投屏已开启";
    }

    private void ensureActive() throws Exception { if (stopping) throw new Exception("已取消连接"); }

    private LocalSocket connectSocket() throws Exception {
        Exception last = null;
        for (int i = 0; i < 40 && !stopping; i++) {
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                return socket;
            } catch (Exception e) { last = e; socket.close(); Thread.sleep(250); }
        }
        throw new Exception("无法连接投屏服务：" + (last == null ? "已停止" : last.getMessage()));
    }

    private void readVideo(LocalSocket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (!stopping) {
                long flags = in.readLong();
                int size = in.readInt();
                if (size < 0 || size > 2_000_000) throw new Exception("无效视频帧：" + size);
                byte[] packet = new byte[size + 2];
                boolean config = (flags & Long.MIN_VALUE) != 0;
                boolean key = (flags & (1L << 62)) != 0;
                packet[0] = (byte) (key ? 1 : 0);
                packet[1] = (byte) (config ? 1 : 0);
                in.readFully(packet, 2, size);
                if (web != null) web.broadcastVideo(packet, config, key);
            }
        } catch (Exception e) {
            if (!stopping) { Log.e(TAG, "Video stopped", e); state = "视频中断"; stopSelf(); }
        }
    }

    private void readAudio(LocalSocket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            boolean first = true;
            while (!stopping) {
                in.readLong();
                int size = in.readInt();
                if (size < 0 || size > 16384) throw new Exception("无效音频帧：" + size);
                byte[] packet = new byte[size];
                in.readFully(packet);
                if (first) { socket.setSoTimeout(0); first = false; }
                if (web != null) web.broadcastAudio(packet);
            }
        } catch (Exception e) {
            if (!stopping) {
                Log.e(TAG, "Audio stopped", e);
                if (web != null) web.notifyAudioError();
            }
        }
    }

    @Override public void onTouch(byte[] data) {
        if (data.length < 22 || controlSocket == null) return;
        byte[] packet = new byte[32];
        packet[0] = 2; packet[1] = data[1];
        System.arraycopy(data, 2, packet, 2, 20);
        boolean down = data[1] != 1;
        packet[22] = (byte) (down ? 255 : 0);
        packet[23] = (byte) (down ? 255 : 0);
        packet[27] = (byte) (down ? 1 : 0);
        packet[31] = (byte) (down ? 1 : 0);
        try { synchronized (controlSocket) { OutputStream out = controlSocket.getOutputStream(); out.write(packet); out.flush(); } }
        catch (Exception e) { Log.w(TAG, "Touch failed", e); }
    }

    @Override public String onCommand(String action) {
        if ("disconnect".equals(action)) { stopSelf(); return null; }
        if ("displayOff".equals(action) || "displayOn".equals(action)) {
            try {
                root("sh " + rootScript.getAbsolutePath() + ("displayOff".equals(action) ? " dim" : " wake"));
                dimmed = "displayOff".equals(action);
            } catch (Exception e) { return "{\"displayError\":\"" + e.getClass().getSimpleName() + "\"}"; }
        }
        if ("getDisplayState".equals(action) || "displayOff".equals(action) || "displayOn".equals(action))
            return "{\"display\":\"" + (dimmed ? "off" : "on") + "\"}";
        return null;
    }

    private File copyAsset(String name) throws Exception {
        File target = new File(getFilesDir(), name);
        try (InputStream in = getAssets().open(name); FileOutputStream out = new FileOutputStream(target)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        }
        return target;
    }
    private void root(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) >= 0) log.write(b, 0, n);
        }
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new Exception("Root 命令失败：" + log.toString("UTF-8").trim());
    }
    private synchronized void cleanupResources() {
        if (connectivity != null && wifiCallback != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
            wifiCallback = null;
        }
        if (web != null) { web.stop(); web = null; }
        for (LocalSocket socket : new LocalSocket[]{videoSocket, audioSocket, controlSocket})
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        videoSocket = null; audioSocket = null; controlSocket = null;
        if (scrcpy != null) scrcpy.destroy();
        scrcpy = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (rootScript != null) try { root("sh " + rootScript.getAbsolutePath() + " stop"); } catch (Exception e) { Log.e(TAG, "Restore failed", e); }
    }
    @Override public void onDestroy() {
        stopping = true;
        cleanupResources();
        if (!state.startsWith("启动失败") && !state.startsWith("Wi-Fi") && !state.endsWith("中断") && !state.equals("采集进程已退出")) state = "已断开";
        super.onDestroy();
    }
}
