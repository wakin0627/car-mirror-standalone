package com.niofirefly.standalone;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

final class MirrorWebServer {
    interface Listener { void onTouch(byte[] data); String onCommand(String action); }
    private static final String TAG = "CarMirrorWeb";
    private static final String ALIAS = "niofirefly-standalone-tls-v2";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Context context;
    private final Listener listener;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private SSLServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile byte[] configPacket;
    private volatile byte[] lastKeyframe;

    MirrorWebServer(Context context, Listener listener) { this.context = context; this.listener = listener; }

    void start() throws Exception {
        SSLContext tls = createTlsContext();
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(8443, 32);
        running = true;
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
        for (Client client : clients) client.close();
        clients.clear();
    }

    void broadcastVideo(byte[] packet, boolean config, boolean key) {
        if (config) configPacket = packet;
        if (key) lastKeyframe = packet;
        for (Client client : clients) if (!client.audio) client.offerVideo(packet, config, key);
    }
    void broadcastAudio(byte[] packet) {
        for (Client client : clients) if (client.audio) client.offerAudio(packet);
    }
    void notifyAudioError() {
        for (Client client : clients) if (client.audio) client.sendText("{\"error\":\"capture_failed\"}");
    }

    private SSLContext createTlsContext() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            long now = System.currentTimeMillis();
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
                            | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=car-mirror"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 86400000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365 * 86400000L))
                    .build();
            generator.initialize(spec);
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        Certificate[] storedChain = store.getCertificateChain(ALIAS);
        if (key == null || storedChain == null || storedChain.length == 0)
            throw new IllegalStateException("TLS certificate unavailable");
        X509Certificate[] chain = new X509Certificate[storedChain.length];
        for (int i = 0; i < storedChain.length; i++) chain[i] = (X509Certificate) storedChain[i];
        X509ExtendedKeyManager manager = new X509ExtendedKeyManager() {
            private String match(String type) { return "RSA".equals(type) ? ALIAS : null; }
            @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
            @Override public String[] getServerAliases(String type, Principal[] issuers) {
                return match(type) == null ? null : new String[]{ALIAS};
            }
            @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return match(type); }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? chain.clone() : null;
            }
            @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
            @Override public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) { return null; }
            @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return match(type); }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(new KeyManager[]{manager}, null, null);
        return tls;
    }

    private void serve(Socket socket) {
        new Thread(() -> {
            try {
                socket.setSoTimeout(15000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                String first = readLine(in);
                if (first == null || !first.startsWith("GET ")) { socket.close(); return; }
                String path = first.split(" ")[1];
                Map<String,String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && (path.equals("/stream") || path.equals("/audio"))) {
                    String key = headers.get("sec-websocket-key");
                    if (key == null) { socket.close(); return; }
                    byte[] hash = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
                    String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + Base64.encodeToString(hash, Base64.NO_WRAP) + "\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush();
                    socket.setSoTimeout(0);
                    Client client = new Client(socket, in, out, path.equals("/audio"));
                    clients.add(client);
                    if (!client.audio) {
                        if (configPacket != null) client.offerVideo(configPacket, true, false);
                        if (lastKeyframe != null) client.offerVideo(lastKeyframe, false, true);
                    }
                    client.run();
                    return;
                }
                String asset = path.equals("/audio-worklet.js") ? "audio-worklet.js" : "index.html";
                byte[] body = readAsset(asset);
                String type = asset.endsWith(".js") ? "application/javascript" : "text/html";
                String response = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush(); socket.close();
            } catch (Exception e) {
                if (e instanceof SSLException) {
                    Log.e(TAG, "TLS handshake failed", e);
                    MirrorService.state = "TLS 握手失败：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " / " + e.getMessage());
                } else {
                    Log.w(TAG, "Client disconnected: " + e.getMessage());
                }
                try { socket.close(); } catch (Exception ignored) {}
            }
        }, "car-mirror-http").start();
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
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
        volatile boolean open = true;
        volatile boolean dropping;
        Thread writer;
        Client(Socket socket, InputStream in, OutputStream out, boolean audio) {
            this.socket = socket; this.in = in; this.out = out; this.audio = audio;
        }
        void offerVideo(byte[] packet, boolean config, boolean key) {
            if (config || key) dropping = false;
            if (dropping) return;
            if (!queue.offer(frame((byte) 2, packet))) {
                queue.clear(); dropping = !key && !config;
                if (!dropping) {
                    if (configPacket != null) queue.offer(frame((byte) 2, configPacket));
                    if (key && !config) queue.offer(frame((byte) 2, packet));
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
                    if (opcode == 2) listener.onTouch(payload);
                    if (opcode == 1) {
                        String text = new String(payload, StandardCharsets.UTF_8);
                        String action = text.contains("disconnect") ? "disconnect" : text.contains("getDisplayState") ? "getDisplayState" :
                                text.contains("displayOff") ? "displayOff" : text.contains("displayOn") ? "displayOn" : "";
                        String response = listener.onCommand(action);
                        if (response != null) sendText(response);
                    }
                }
            } catch (Exception ignored) { }
            close();
        }
        void close() {
            if (!open) return;
            open = false; clients.remove(this);
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
