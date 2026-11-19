package com.niofirefly.standalone;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Desktop integration test for pairing, stream authentication, and independent packet routing. */
public final class SplitServerTest {
    private static SSLSocket socket() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) {}
            public void checkServerTrusted(X509Certificate[] chain, String auth) {}
        }};
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, trust, new SecureRandom());
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", 8443);
        socket.setSoTimeout(5000); return socket;
    }
    private static String headers(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int state = 0, c;
        while ((c = in.read()) >= 0) {
            out.write(c);
            state = state == 0 && c == '\r' ? 1 : state == 1 && c == '\n' ? 2 : state == 2 && c == '\r' ? 3 : state == 3 && c == '\n' ? 4 : 0;
            if (state == 4) return out.toString("UTF-8");
        }
        throw new EOFException("incomplete response headers");
    }
    private static void request(SSLSocket socket, String path, boolean websocket) throws Exception {
        String headers = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + (websocket
                ? "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" : "Connection: close\r\n") + "\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
    }
    private static byte[] readFrame(InputStream in) throws Exception {
        int first = in.read(), size = in.read();
        if (first != 0x82 || size < 0 || size >= 126) throw new AssertionError("unexpected websocket frame");
        byte[] data = new byte[size]; new DataInputStream(in).readFully(data); return data;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void sendText(SSLSocket socket, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8), mask = {1, 2, 3, 4};
        OutputStream out = socket.getOutputStream();
        out.write(0x81); out.write(0x80 | bytes.length); out.write(mask);
        for (int i = 0; i < bytes.length; i++) out.write(bytes[i] ^ mask[i % 4]);
        out.flush();
    }
    public static void main(String[] args) throws Exception {
        File files = Files.createTempDirectory("split-server-test").toFile();
        CountDownLatch secondBack = new CountDownLatch(1);
        CountDownLatch videoClients = new CountDownLatch(2);
        MirrorWebServer server = new MirrorWebServer(new Context(files, new File("app/src/main/assets")), new MirrorWebServer.Listener() {
            public void onTouch(byte[] data) {}
            public String onCommand(String action) { return null; }
            public String onSecondCommand(String action) { if ("back".equals(action)) secondBack.countDown(); return null; }
            public void onVideoClientConnected(boolean second) { videoClients.countDown(); }
        }, true);
        try {
            server.start();
            try (SSLSocket s = socket()) { request(s, "/", false); require(headers(s.getInputStream()).contains("200 OK"), "pair page unavailable"); }
            try (SSLSocket s = socket()) { request(s, "/stream", true); require(headers(s.getInputStream()).contains("403 Forbidden"), "stream must require pairing"); }
            String redirect;
            try (SSLSocket s = socket()) { request(s, "/?pin=" + server.pairPin(), false); redirect = headers(s.getInputStream()); }
            require(redirect.contains("302 Found"), "correct PIN did not pair");
            String token = Arrays.stream(redirect.split("\\r\\n")).filter(x -> x.startsWith("Location: /?key="))
                    .findFirst().orElseThrow(() -> new AssertionError("missing token")).substring("Location: /?key=".length());
            require(token.matches("[a-f0-9]{32}"), "bad session token");
            try (SSLSocket left = socket(); SSLSocket right = socket()) {
                request(left, "/stream?key=" + token, true); require(headers(left.getInputStream()).contains("101 Switching Protocols"), "left stream failed");
                request(right, "/stream2?key=" + token, true); require(headers(right.getInputStream()).contains("101 Switching Protocols"), "right stream failed");
                require(videoClients.await(2, TimeUnit.SECONDS), "stream clients did not initialize");
                server.broadcastVideo(new byte[]{1, 2, 3}, false, true);
                server.broadcastSecondVideo(new byte[]{4, 5, 6}, false, true);
                require(Arrays.equals(readFrame(left.getInputStream()), new byte[]{1, 2, 3}), "left stream received wrong frame");
                require(Arrays.equals(readFrame(right.getInputStream()), new byte[]{4, 5, 6}), "right stream received wrong frame");
                sendText(right, "{\"action\":\"back\"}");
                require(secondBack.await(2, TimeUnit.SECONDS), "right-window back did not reach the right listener");
            }
            System.out.println("Split server: TLS pairing, authentication and independent stream routing passed");
        } finally { server.stop(); }
    }
}
