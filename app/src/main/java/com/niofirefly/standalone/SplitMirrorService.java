package com.niofirefly.standalone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Two independent scrcpy displays, with a single system audio capture. */
public final class SplitMirrorService extends Service implements MirrorWebServer.Listener {
    static volatile boolean active;
    static volatile String pairCode = "";
    private static final String TAG = "SplitMirror";
    private static final String BASE = "/data/local/tmp/niofirefly-standalone";
    private final Process[] processes = new Process[2];
    private final LocalSocket[] videos = new LocalSocket[2], controls = new LocalSocket[2];
    private LocalSocket audio;
    private volatile MirrorWebServer web;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private final Set<Network> wifiNetworks = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile boolean starting;
    private final AtomicIntegerArray displayIds = new AtomicIntegerArray(new int[]{-1, -1});

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && MirrorService.ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (starting || web != null) return START_NOT_STICKY;
        starting = true; active = true; MirrorService.active = true;
        MirrorService.state = "正在启动双应用桌面";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("split", "车机双应用", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, SplitMirrorService.class).setAction(MirrorService.ACTION_STOP);
        PendingIntent pending = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "split")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("车机双应用运行中")
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "断开", pending).build()).build();
        startForeground(102, notification);
        new Thread(() -> {
            try { startSplit(intent); }
            catch (Exception e) {
                Log.e(TAG, "Start failed", e);
                if (!stopping) { MirrorService.state = "启动失败：" + e.getMessage(); stopSelf(); }
            } finally { starting = false; }
        }, "split-start").start();
        return START_NOT_STICKY;
    }

    private void startSplit(Intent intent) throws Exception {
        if (intent == null) throw new Exception("无法读取连接设置");
        String size = intent.getStringExtra("size");
        int totalWidth = "800x1280".equals(size) ? 1280 : "1600x2560".equals(size) ? 2560 : 1920;
        int ratio = intent.getIntExtra("carRatio", 0);
        int fps = intent.getIntExtra("maxFps", 60);
        if (fps != 30 && fps != 60 && fps != 90 && fps != 120) fps = 60;
        int height = ratio == 1 ? totalWidth * 9 / 16 : ratio == 2 ? totalWidth / 2 : totalWidth * 5 / 8;
        int width = totalWidth / 2;
        File jar = new File(getFilesDir(), "scrcpy-server.jar");
        try (InputStream in = getAssets().open("scrcpy-server.jar"); FileOutputStream out = new FileOutputStream(jar)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        root("mkdir -p " + BASE + " && cp " + jar.getAbsolutePath() + " " + BASE + "/scrcpy-server.jar && chmod 644 " + BASE + "/scrcpy-server.jar");
        checkActive();
        MirrorWebServer server = new MirrorWebServer(this, this, true);
        server.start();
        synchronized (this) {
            if (stopping) { server.stop(); throw new Exception("连接已取消"); }
            web = server; pairCode = server.pairPin();
            wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarMirror:split");
            wake.acquire();
            wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarMirror:split-wifi");
            wifiLock.acquire();
            connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { wifiNetworks.add(network); }
                @Override public void onLost(Network network) {
                    if (wifiNetworks.remove(network) && wifiNetworks.isEmpty() && !stopping) {
                        MirrorService.state = "Wi-Fi 已断开"; stopSelf();
                    }
                }
            };
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
        }
        checkActive();
        startDisplay(0, width, height, fps);
        checkActive();
        startDisplay(1, width, height, fps);
        checkActive();
        new Thread(() -> readVideo(0), "split-video-left").start();
        new Thread(() -> readVideo(1), "split-video-right").start();
        new Thread(this::readAudio, "split-audio").start();
        MirrorService.state = "双应用服务已启动，等待车机";
    }

    private String checkedComponent(String component) throws Exception {
        if (component == null || !component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))
            throw new Exception("应用名称无效，请重新选择");
        return component;
    }

    private void startDisplay(int index, int width, int height, int fps) throws Exception {
        checkActive();
        String scid = index == 0 ? "01234567" : "01234568";
        String command = "su shell -c 'CLASSPATH=" + BASE + "/scrcpy-server.jar /system/bin/app_process /system/bin "
                + "com.genymobile.scrcpy.Server 3.1 log_level=info scid=" + scid
                + " new_display=" + width + "x" + height + "/160 vd_system_decorations=false"
                + " video_bit_rate=4000000 max_fps=" + fps + " audio=" + (index == 0)
                + " audio_source=output audio_codec=raw control=true cleanup=false tunnel_forward=true"
                + " send_device_meta=false send_codec_meta=false send_dummy_byte=false send_frame_meta=true'";
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        synchronized (this) {
            if (stopping) { process.destroy(); throw new Exception("连接已取消"); }
            processes[index] = process;
        }
        new Thread(() -> watchLog(process, index), "split-log-" + index).start();
        String socketName = "scrcpy_" + scid;
        LocalSocket video = connect(socketName);
        synchronized (this) { if (stopping) { video.close(); throw new Exception("连接已取消"); } videos[index] = video; }
        if (index == 0) {
            LocalSocket audioSocket = connect(socketName);
            synchronized (this) { if (stopping) { audioSocket.close(); throw new Exception("连接已取消"); } audio = audioSocket; }
        }
        LocalSocket control = connect(socketName);
        synchronized (this) { if (stopping) { control.close(); throw new Exception("连接已取消"); } controls[index] = control; }
    }

    private void watchLog(Process process, int index) {
        try (InputStream in = process.getInputStream()) {
            Pattern pattern = Pattern.compile("New display: .*?\\(id=(\\d+)\\)");
            StringBuilder text = new StringBuilder();
            byte[] buffer = new byte[2048]; int n; boolean started = false;
            while ((n = in.read(buffer)) >= 0) {
                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                Log.i(TAG, chunk); text.append(chunk);
                Matcher match = pattern.matcher(text);
                if (!started && match.find()) {
                    started = true; int id = Integer.parseInt(match.group(1));
                    displayIds.set(index, id);
                }
                if (text.length() > 4096) text.delete(0, text.length() - 2048);
            }
        } catch (Exception e) { Log.w(TAG, "Capture log stopped", e); }
        if (!stopping) { MirrorService.state = "应用屏幕已退出"; stopSelf(); }
    }

    private LocalSocket connect(String name) throws Exception {
        Exception last = null;
        for (int i = 0; i < 40 && !stopping; i++) {
            LocalSocket socket = new LocalSocket();
            try { socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)); return socket; }
            catch (Exception e) { last = e; socket.close(); Thread.sleep(250); }
        }
        throw new Exception("连接应用屏幕失败：" + (last == null ? "已取消" : last.getMessage()));
    }

    private void readVideo(int index) {
        try {
            DataInputStream in = new DataInputStream(videos[index].getInputStream());
            while (!stopping) {
                long flags = in.readLong(); int length = in.readInt();
                if (length < 0 || length > 2_000_000) throw new Exception("无效的视频帧");
                boolean config = (flags & Long.MIN_VALUE) != 0, key = (flags & (1L << 62)) != 0;
                byte[] packet = new byte[length + 2]; packet[0] = (byte) (key ? 1 : 0); packet[1] = (byte) (config ? 1 : 0);
                in.readFully(packet, 2, length);
                MirrorWebServer server = web;
                if (server != null) { if (index == 0) server.broadcastVideo(packet, config, key); else server.broadcastSecondVideo(packet, config, key); }
            }
        } catch (Exception e) { if (!stopping) { Log.e(TAG, "Video failed", e); MirrorService.state = "应用画面中断"; stopSelf(); } }
    }

    private void readAudio() {
        try {
            DataInputStream in = new DataInputStream(audio.getInputStream());
            while (!stopping) {
                in.readLong(); int length = in.readInt();
                if (length < 0 || length > 16384) throw new Exception("无效音频帧");
                byte[] packet = new byte[length]; in.readFully(packet);
                MirrorWebServer server = web; if (server != null) server.broadcastAudio(packet);
            }
        } catch (Exception e) { if (!stopping) { Log.w(TAG, "Audio unavailable", e); if (web != null) web.notifyAudioError(); } }
    }

    @Override public void onTouch(byte[] data) { touch(0, data); }
    @Override public void onSecondTouch(byte[] data) { touch(1, data); }
    @Override public void onVideoClientConnected(boolean second) {
        LocalSocket socket = controls[second ? 1 : 0];
        if (socket == null || stopping) return;
        try { synchronized (socket) { socket.getOutputStream().write(17); socket.getOutputStream().flush(); } }
        catch (Exception e) { Log.w(TAG, "Video refresh request failed", e); }
    }
    private void touch(int index, byte[] data) {
        LocalSocket socket = controls[index];
        if (data.length < 22 || socket == null) return;
        byte[] packet = new byte[32]; packet[0] = 2; packet[1] = data[1];
        System.arraycopy(data, 2, packet, 2, 20);
        boolean down = data[1] != 1;
        packet[22] = (byte) (down ? 255 : 0); packet[23] = (byte) (down ? 255 : 0);
        packet[27] = (byte) (down ? 1 : 0); packet[31] = (byte) (down ? 1 : 0);
        try { synchronized (socket) { socket.getOutputStream().write(packet); socket.getOutputStream().flush(); } }
        catch (Exception e) { Log.w(TAG, "Touch failed", e); }
    }
    @Override public String onCommand(String action) {
        if ("disconnect".equals(action)) stopSelf();
        if ("back".equals(action)) sendBack(0);
        return null;
    }
    @Override public String onSecondCommand(String action) {
        if ("back".equals(action)) sendBack(1);
        return null;
    }
    private List<ResolveInfo> availableApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        apps.removeIf(app -> app.activityInfo == null || getPackageName().equals(app.activityInfo.packageName));
        apps.sort((a, b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        return apps;
    }
    private String componentOf(ResolveInfo app) {
        return app.activityInfo.packageName + "/" + app.activityInfo.name;
    }
    @Override public String desktopApps() {
        StringBuilder json = new StringBuilder("[");
        for (ResolveInfo app : availableApps()) {
            if (json.length() > 1) json.append(',');
            json.append("{\"name\":").append(org.json.JSONObject.quote(app.loadLabel(getPackageManager()).toString()));
            json.append(",\"component\":").append(org.json.JSONObject.quote(componentOf(app))).append('}');
        }
        return json.append(']').toString();
    }
    @Override public String onLaunch(boolean second, String component) {
        try {
            checkedComponent(component);
            boolean found = false;
            for (ResolveInfo app : availableApps()) if (componentOf(app).equals(component)) { found = true; break; }
            if (!found) throw new Exception("应用不可用");
            int id = displayIds.get(second ? 1 : 0);
            if (id < 0) throw new Exception("桌面正在启动，请稍后重试");
            new Thread(() -> {
                try { if (!stopping) root("am start --display " + id + " -n '" + component + "'"); }
                catch (Exception e) {
                    Log.e(TAG, "App launch failed", e);
                    MirrorWebServer server = web;
                    if (server != null) server.broadcastStatus("应用打开失败：" + e.getMessage());
                }
            }, "desktop-launch").start();
            return null;
        } catch (Exception e) { return "{\"status\":" + org.json.JSONObject.quote(e.getMessage()) + "}"; }
    }

    private void sendBack(int index) {
        LocalSocket socket = controls[index];
        if (socket == null) return;
        try {
            synchronized (socket) {
                java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
                for (int action = 0; action <= 1; action++) {
                    out.writeByte(0); out.writeByte(action); out.writeInt(4); out.writeInt(0); out.writeInt(0);
                }
                out.flush();
            }
        } catch (Exception e) { Log.w(TAG, "Back failed", e); }
    }
    private void checkActive() throws Exception { if (stopping) throw new Exception("连接已取消"); }
    private void root(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) { byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); }
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw new Exception("Root 命令失败：" + out.toString("UTF-8").trim());
        String result = out.toString("UTF-8");
        if (result.contains("Error") || result.contains("Exception")) throw new Exception(result.trim());
    }
    @Override public void onDestroy() {
        synchronized (this) {
            stopping = true; active = false; MirrorService.active = false; pairCode = "";
            if (web != null) web.stop(); web = null;
            if (connectivity != null && wifiCallback != null) {
                try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
                wifiCallback = null;
            }
            for (LocalSocket socket : videos) if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            for (LocalSocket socket : controls) if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            if (audio != null) try { audio.close(); } catch (Exception ignored) {}
            for (Process process : processes) if (process != null) process.destroy();
            if (wake != null && wake.isHeld()) wake.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        }
        if (!MirrorService.state.startsWith("启动失败") && !MirrorService.state.startsWith("Wi-Fi") && !MirrorService.state.endsWith("中断") && !MirrorService.state.endsWith("已退出")) MirrorService.state = "已断开";
        super.onDestroy();
    }
}
