package com.niofirefly.standalone;

import java.io.File;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Run on a desktop JDK to check certificate persistence and TLS handshakes. */
public final class TlsIdentitySmokeTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("car-mirror-tls-test").toFile();
        try {
            TlsIdentity identity = TlsIdentity.loadOrCreate(dir);
            TlsIdentity loaded = TlsIdentity.loadOrCreate(dir);
            if (!Arrays.equals(identity.certificate.getEncoded(), loaded.certificate.getEncoded()))
                throw new AssertionError("TLS certificate was not reused");
            for (String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) handshake(identity, protocol);
            System.out.println("TLS identity and TLSv1.2/TLSv1.3 handshakes passed");
        } finally {
            new File(dir, "tls-key.pk8").delete();
            new File(dir, "tls-cert.der").delete();
            dir.delete();
        }
    }

    private static void handshake(TlsIdentity identity, String protocol) throws Exception {
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(new KeyManager[]{identity.keyManager()}, null, null);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { throw new AssertionError(); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                if (chain.length != 1 || !Arrays.equals(chain[0].getEncoded(), identity.certificate.getEncoded()))
                    throw new java.security.cert.CertificateException("Unexpected server certificate");
            }
        }}, null);
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread worker = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setEnabledProtocols(new String[]{protocol});
                    socket.getOutputStream().write(42);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            worker.start();
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setEnabledProtocols(new String[]{protocol});
                if (client.getInputStream().read() != 42) throw new AssertionError("TLS payload mismatch");
            }
            worker.join(5000);
            if (worker.isAlive()) throw new AssertionError("TLS server did not finish");
        }
    }
}
