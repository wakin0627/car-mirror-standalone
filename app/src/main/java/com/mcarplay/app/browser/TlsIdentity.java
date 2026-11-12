package com.mcarplay.app.browser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/** Per-install TLS identity. The private key stays in the app's private files directory. */
final class TlsIdentity {
    private static final String ALIAS = "car-mirror-local";
    final PrivateKey key;
    final X509Certificate certificate;

    private TlsIdentity(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    X509ExtendedKeyManager keyManager() {
        return new X509ExtendedKeyManager() {
            private String match(String type) { return "RSA".equals(type) ? ALIAS : null; }
            @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
            @Override public String[] getServerAliases(String type, Principal[] issuers) {
                return match(type) == null ? null : new String[]{ALIAS};
            }
            @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return match(type); }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
            @Override public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) { return null; }
            @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return match(type); }
        };
    }

    static TlsIdentity loadOrCreate(File dir) throws Exception {
        File keyFile = new File(dir, "tls-key.pk8");
        File certFile = new File(dir, "tls-cert.der");
        List<Inet4Address> ips = wifiAddresses();
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(readAll(keyFile)));
                X509Certificate cert = parseCertificate(readAll(certFile));
                cert.checkValidity();
                cert.verify(cert.getPublicKey());
                Signature proof = Signature.getInstance("SHA256withRSA");
                proof.initSign(key);
                proof.update(new byte[]{1, 2, 3, 4});
                byte[] signed = proof.sign();
                proof.initVerify(cert.getPublicKey());
                proof.update(new byte[]{1, 2, 3, 4});
                if (!proof.verify(signed)) throw new IllegalStateException("TLS key mismatch");
                if (coversAddresses(cert, ips)) return new TlsIdentity(key, cert);
            } catch (Exception ignored) {
                // A partial write or changed Wi-Fi address requires a new certificate.
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate cert = createCertificate(pair, ips);
        writeAll(keyFile, pair.getPrivate().getEncoded());
        writeAll(certFile, cert.getEncoded());
        return new TlsIdentity(pair.getPrivate(), cert);
    }

    private static List<Inet4Address> wifiAddresses() {
        List<Inet4Address> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.getName().startsWith("wlan")) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress())
                        result.add((Inet4Address) address);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static boolean coversAddresses(X509Certificate cert, List<Inet4Address> ips) throws Exception {
        if (ips.isEmpty()) return true;
        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        if (names == null) return false;
        for (Inet4Address ip : ips) {
            boolean found = false;
            for (List<?> name : names) {
                if (Integer.valueOf(7).equals(name.get(0)) && ip.getHostAddress().equals(name.get(1))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static X509Certificate createCertificate(KeyPair pair, List<Inet4Address> ips) throws Exception {
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        byte[] signatureAlgorithm = seq(oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b), tlv(0x05, new byte[0]));
        byte[] name = seq(tlv(0x31, seq(oid(0x55, 0x04, 0x03),
                tlv(0x0c, "car-mirror".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 86400000L)), time(new Date(now + 10L * 365 * 86400000L)));
        List<byte[]> fields = new ArrayList<>();
        fields.add(tlv(0xa0, tlv(0x02, new byte[]{2}))); // X.509 v3
        fields.add(tlv(0x02, new java.math.BigInteger(1, serial).toByteArray()));
        fields.add(signatureAlgorithm);
        fields.add(name);
        fields.add(validity);
        fields.add(name);
        fields.add(pair.getPublic().getEncoded());
        if (!ips.isEmpty()) {
            List<byte[]> ipNames = new ArrayList<>();
            for (Inet4Address ip : ips) ipNames.add(tlv(0x87, ip.getAddress()));
            byte[] san = seq(oid(0x55, 0x1d, 0x11), tlv(0x04, seq(ipNames.toArray(new byte[0][]))));
            fields.add(tlv(0xa3, seq(san)));
        }
        byte[] tbs = seq(fields.toArray(new byte[0][]));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bitString = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bitString, 1, signature.length);
        X509Certificate cert = parseCertificate(seq(tbs, signatureAlgorithm, tlv(0x03, bitString)));
        cert.checkValidity();
        cert.verify(pair.getPublic());
        return cert;
    }

    private static X509Certificate parseCertificate(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] time(Date value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x18, format.format(value).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int... bytes) {
        byte[] value = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) value[i] = (byte) bytes[i];
        return tlv(0x06, value);
    }

    private static byte[] seq(byte[]... values) { return tlv(0x30, concat(values)); }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 128) out.write(len);
        else {
            int count = 0;
            for (int n = len; n != 0; n >>>= 8) count++;
            out.write(0x80 | count);
            for (int shift = (count - 1) * 8; shift >= 0; shift -= 8) out.write(len >>> shift);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void writeAll(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }
}
