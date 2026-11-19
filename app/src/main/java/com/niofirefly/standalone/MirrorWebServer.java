package com.niofirefly.standalone;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;

final class MirrorWebServer {
    interface Listener {
        void onTouch(byte[] data);
        String onCommand(String action);
        default void onSecondTouch(byte[] data) {}
        default String onSecondCommand(String action) { return null; }
        default void onVideoClientConnected(boolean second) {}
        default String desktopApps() { return "[]"; }
        default String onLaunch(boolean second, String component) { return null; }
    }
    private static final String TAG = "CarMirrorWeb";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Context context;
    private final Listener listener;
    private final boolean split;
    private final String splitToken, splitPin;
    private final Map<String, long[]> failedPins = new ConcurrentHashMap<>();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private SSLServerSocket server;
    private ServerSocket diagnosticServer;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile byte[] configPacket;
    private volatile byte[] lastKeyframe;
    private volatile byte[] secondConfigPacket;
    private volatile byte[] secondKeyframe;
    private volatile String splitStatus;
    private volatile boolean audioFailed;

    MirrorWebServer(Context context, Listener listener) { this(context, listener, false); }
    MirrorWebServer(Context context, Listener listener, boolean split) {
        this.context = context; this.listener = listener; this.split = split;
        if (split) {
            SecureRandom random = new SecureRandom(); byte[] bytes = new byte[16]; random.nextBytes(bytes);
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            splitToken = value.toString(); splitPin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } else { splitToken = null; splitPin = null; }
    }
    String pairPin() { return splitPin; }

    void start() throws Exception {
        SSLContext tls = createTlsContext();
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(8443, 32);
        running = true;
        try {
            diagnosticServer = new ServerSocket(8080, 8);
            new Thread(() -> {
                while (running) {
                    try { serveDiagnostic(diagnosticServer.accept()); }
                    catch (Exception e) { if (running) Log.w(TAG, "Diagnostic accept failed", e); }
                }
            }, "car-mirror-diagnostic").start();
        } catch (Exception e) { Log.w(TAG, "Diagnostic port unavailable", e); }
        acceptThread = new Thread(() -> {
            while (running) {
                try { serve(server.accept()); }
                catch (Exception e) { if (running) Log.e(TAG, "Accept failed", e); }
            }
        }, "car-mirror-accept");
        acceptThread.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (diagnosticServer != null) diagnosticServer.close(); } catch (Exception ignored) {}
        for (Client client : clients) client.close();
        clients.clear();
    }

    void broadcastVideo(byte[] packet, boolean config, boolean key) {
        if (config) configPacket = packet;
        if (key) lastKeyframe = packet;
        for (Client client : clients) if (!client.audio && !client.second) client.offerVideo(packet, config, key);
    }
    void broadcastSecondVideo(byte[] packet, boolean config, boolean key) {
        if (config) secondConfigPacket = packet;
        if (key) secondKeyframe = packet;
        for (Client client : clients) if (client.second) client.offerVideo(packet, config, key);
    }
    void broadcastAudio(byte[] packet) {
        for (Client client : clients) if (client.audio) client.offerAudio(packet);
    }
    void notifyAudioError() {
        audioFailed = true;
        for (Client client : clients) if (client.audio) client.sendText("{\"error\":\"capture_failed\"}");
    }
    void broadcastStatus(String message) {
        if (message.length() > 300) message = message.substring(0, 300);
        String payload = "{\"status\":" + org.json.JSONObject.quote(message) + "}";
        splitStatus = payload;
        for (Client client : clients) if (!client.audio) client.sendText(payload);
    }

    private SSLContext createTlsContext() throws Exception {
        TlsIdentity identity = TlsIdentity.loadOrCreate(context.getFilesDir());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{identity.keyManager()}, null, null);
        return tls;
    }

    private void serveDiagnostic(Socket socket) {
        new Thread(() -> {
            try (Socket client = socket) {
                client.setSoTimeout(3000);
                String first = readLine(client.getInputStream());
                if (first == null || !first.startsWith("GET ")) return;
                String host = client.getLocalAddress().getHostAddress();
                String body = "CarMirror phone service is reachable. Open https://" + host + ":8443/ for mirroring.\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n";
                OutputStream out = client.getOutputStream();
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
            } catch (Exception e) { Log.w(TAG, "Diagnostic request failed", e); }
        }, "car-mirror-diagnostic-client").start();
    }

    private void serve(Socket socket) {
        new Thread(() -> {
            try {
                socket.setSoTimeout(15000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                String first = readLine(in);
                if (first == null || !first.startsWith("GET ")) { socket.close(); return; }
                String target = first.split(" ")[1];
                String[] targetParts = target.split("\\?", 2);
                String path = targetParts[0];
                if (split) {
                    String query = targetParts.length == 2 ? targetParts[1] : "";
                    if (path.equals("/") && (query.isEmpty() || query.startsWith("pin="))) {
                        String message = "";
                        if (!query.isEmpty()) {
                            String remote = socket.getInetAddress().getHostAddress();
                            if (pinLimited(remote, false)) message = "尝试过多，请一分钟后重试";
                            else if (("pin=" + splitPin).equals(query)) {
                                String redirect = "HTTP/1.1 302 Found\r\nLocation: /?key=" + splitToken
                                        + "\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                                out.write(redirect.getBytes(StandardCharsets.US_ASCII)); out.flush(); socket.close(); return;
                            } else { pinLimited(remote, true); message = "配对码不正确"; }
                        }
                        byte[] body = new String(readAsset("split-pair.html"), StandardCharsets.UTF_8)
                                .replace("{{ERROR}}", message).getBytes(StandardCharsets.UTF_8);
                        String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n";
                        out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush(); socket.close(); return;
                    }
                    if (!("key=" + splitToken).equals(query)) {
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush(); socket.close(); return;
                    }
                }
                Map<String,String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && (path.equals("/stream") || path.equals("/audio") || (split && path.equals("/stream2")))) {
                    String key = headers.get("sec-websocket-key");
                    if (key == null) { socket.close(); return; }
                    byte[] hash = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
                    String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + Base64.encodeToString(hash, Base64.NO_WRAP) + "\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush();
                    socket.setSoTimeout(0);
                    Client client = new Client(socket, in, out, path.equals("/audio"), path.equals("/stream2"));
                    clients.add(client);
                    if (client.audio && audioFailed) client.sendText("{\"error\":\"capture_failed\"}");
                    if (!client.audio) {
                        MirrorService.state = "车机已连接";
                        byte[] config = client.second ? secondConfigPacket : configPacket;
                        byte[] keyframe = client.second ? secondKeyframe : lastKeyframe;
                        if (config != null) client.offerVideo(config, true, false);
                        if (keyframe != null) client.offerVideo(keyframe, false, true);
                        if (splitStatus != null) client.sendText(splitStatus);
                        if (split) listener.onVideoClientConnected(client.second);
                    }
                    client.run();
                    return;
                }
                if (split && path.equals("/apps")) {
                    byte[] body = listener.desktopApps().getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush(); socket.close(); return;
                }
                String asset = path.equals("/audio-worklet.js") ? "audio-worklet.js" : split ? "split.html" : "index.html";
                byte[] body = readAsset(asset);
                String type = asset.endsWith(".js") ? "application/javascript" : "text/html";
                String response = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush(); socket.close();
            } catch (Exception e) {
                if (e instanceof SSLException) {
                    Log.w(TAG, "TLS request rejected from " + socket.getRemoteSocketAddress(), e);
                    if (running && !hasVideoClient())
                        MirrorService.state = "等待车机连接；最近一次请求 TLS 失败：" + e.getMessage();
                } else {
                    Log.w(TAG, "Client disconnected: " + e.getMessage());
                }
                try { socket.close(); } catch (Exception ignored) {}
            }
        }, "car-mirror-http").start();
    }

    private boolean hasVideoClient() {
        for (Client client : clients) if (!client.audio && client.open) return true;
        return false;
    }
    private boolean pinLimited(String remote, boolean failed) {
        synchronized (failedPins) {
            long now = System.currentTimeMillis();
            long[] value = failedPins.computeIfAbsent(remote, key -> new long[]{now, 0});
            if (now - value[0] > 60000) { value[0] = now; value[1] = 0; }
            if (failed) value[1]++;
            return value[1] >= 5;
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (out.size() >= 8192) throw new IllegalArgumentException("Request line too long");
            out.write(c);
        }
        if (c < 0 && out.size() == 0) return null;
        byte[] bytes = out.toByteArray();
        int n = bytes.length;
        if (n > 0 && bytes[n - 1] == '\r') n--;
        return new String(bytes, 0, n, StandardCharsets.US_ASCII);
    }
    private static byte[] exact(InputStream in, int len) throws Exception {
        byte[] b = new byte[len]; int off = 0;
        while (off < len) { int n = in.read(b, off, len - off); if (n < 0) throw new IllegalStateException("Socket closed"); off += n; }
        return b;
    }

    private final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final boolean audio;
        final boolean second;
        final ArrayBlockingQueue<byte[]> queue;
        volatile boolean open = true;
        volatile boolean dropping;
        Thread writer;
        Client(Socket socket, InputStream in, OutputStream out, boolean audio, boolean second) {
            this.socket = socket; this.in = in; this.out = out; this.audio = audio; this.second = second;
            this.queue = new ArrayBlockingQueue<>(audio ? 16 : 6);
        }
        void offerVideo(byte[] packet, boolean config, boolean key) {
            if (key) dropping = false;
            if (config && dropping) return;
            if (dropping) return;
            if (!queue.offer(frame((byte) 2, packet))) {
                queue.clear(); dropping = !key;
                if (split && !key) listener.onVideoClientConnected(second);
                if (key) {
                    byte[] configData = second ? secondConfigPacket : configPacket;
                    if (configData != null) queue.offer(frame((byte) 2, configData));
                    queue.offer(frame((byte) 2, packet));
                }
            }
        }
        void offerAudio(byte[] packet) {
            byte[] encoded = frame((byte) 2, packet);
            while (queue.size() >= 8) queue.poll();
            if (!queue.offer(encoded)) { queue.poll(); queue.offer(encoded); }
        }
        void sendText(String message) { queue.offer(frame((byte) 1, message.getBytes(StandardCharsets.UTF_8))); }
        void run() {
            writer = new Thread(() -> {
                try {
                    while (open) { byte[] data = queue.take(); synchronized (out) { out.write(data); out.flush(); } }
                } catch (Exception ignored) { close(); }
            }, "car-mirror-ws-writer");
            writer.start();
            try {
                while (open) {
                    byte[] head = exact(in, 2);
                    int opcode = head[0] & 15;
                    long length = head[1] & 127;
                    if (length == 126) { byte[] n = exact(in, 2); length = ((n[0] & 255) << 8) | (n[1] & 255); }
                    else if (length == 127) { byte[] n = exact(in, 8); length = 0; for (byte x : n) length = (length << 8) | (x & 255); }
                    if (length > 65536) throw new IllegalArgumentException("Frame too long");
                    boolean masked = (head[1] & 128) != 0;
                    if (!masked) throw new IllegalArgumentException("Unmasked client frame");
                    byte[] mask = exact(in, 4), payload = exact(in, (int) length);
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                    if (opcode == 8) break;
                    if (opcode == 9) { queue.offer(frame((byte) 10, payload)); continue; }
                    if (audio) continue;
                    if (opcode == 2) { if (second) listener.onSecondTouch(payload); else listener.onTouch(payload); }
                    if (opcode == 1) {
                        String text = new String(payload, StandardCharsets.UTF_8);
                        if (split && text.startsWith("launch:")) {
                            String result = listener.onLaunch(second, text.substring(7));
                            if (result != null) sendText(result);
                            continue;
                        }
                        String action = text.contains("disconnect") ? "disconnect" : text.contains("launcher") ? "launcher" : text.contains("back") ? "back" : text.contains("getDisplayState") ? "getDisplayState" :
                                text.contains("displayOff") ? "displayOff" : text.contains("displayOn") ? "displayOn" : "";
                        String response = second ? listener.onSecondCommand(action) : listener.onCommand(action);
                        if (response != null) sendText(response);
                    }
                }
            } catch (Exception ignored) { }
            close();
        }
        void close() {
            if (!open) return;
            open = false; clients.remove(this);
            if (!audio && running && !hasVideoClient()) MirrorService.state = "服务已启动，等待车机";
            if (writer != null) writer.interrupt();
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
    private static byte[] frame(byte opcode, byte[] payload) {
        int size = payload.length;
        int header = size < 126 ? 2 : size <= 65535 ? 4 : 10;
        byte[] b = new byte[header + size]; b[0] = (byte)(0x80 | opcode);
        if (header == 2) b[1] = (byte) size;
        else if (header == 4) { b[1] = 126; b[2] = (byte)(size >> 8); b[3] = (byte) size; }
        else { b[1] = 127; long n = size; for (int i = 9; i >= 2; i--) { b[i] = (byte)n; n >>>= 8; } }
        System.arraycopy(payload, 0, b, header, size);
        return b;
    }
}
