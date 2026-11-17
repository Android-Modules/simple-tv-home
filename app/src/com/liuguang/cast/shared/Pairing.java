package com.liuguang.cast.shared;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class Pairing {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int PIN_LENGTH = 12, SECRET_LENGTH = 4;
    public static final int SHORT_CODE_LENGTH = 4;
    public static final long SHORT_CODE_LIFETIME_MILLIS = 60_000;
    public static final int TOKEN_LENGTH = 48;
    private static final String HEX = "0123456789abcdef";
    private static final String QR_PREFIX = "LGC2:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pairing() {}

    public static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean valid(String code) {
        if (code == null || code.length() != PIN_LENGTH + SECRET_LENGTH) return false;
        return validPin(code.substring(0, PIN_LENGTH)) && alphabet(code.substring(PIN_LENGTH));
    }

    public static boolean validPin(String pin) { return pin != null && pin.length() == PIN_LENGTH && alphabet(pin); }

    public static boolean validShortCode(String code) {
        return code != null && code.matches("[0-9]{4}");
    }

    private static boolean alphabet(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    public static boolean validToken(String token) {
        return token.length() == TOKEN_LENGTH && token.matches("[0-9a-f]+");
    }

    public static String newToken() {
        byte[] bytes = new byte[TOKEN_LENGTH / 2];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    public static String tokenHash(String token) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(HEX.charAt((b & 255) >>> 4)).append(HEX.charAt(b & 15));
        return value.toString();
    }

    public record Offer(String host, String code) {}

    public static String qr(String host, String code) { return QR_PREFIX + host + ":" + normalize(code); }

    public static Offer readQr(String payload) {
        if (payload == null || payload.length() > 40 || !payload.startsWith(QR_PREFIX)) return null;
        int separator = payload.indexOf(':', QR_PREFIX.length());
        if (separator == -1) return null;
        String host = payload.substring(QR_PREFIX.length(), separator);
        String code = payload.substring(separator + 1);
        return ipv4(host) && valid(code) ? new Offer(host, code) : null;
    }

    public static boolean ipv4(String value) {
        if (!value.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = value.split("\\.");
        int first = Integer.parseInt(parts[0]);
        if (first == 0 || first == 127 || first >= 224) return false;
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    public static String format(String code) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) result.append('-');
            result.append(code.charAt(i));
        }
        return result.toString();
    }

    public static String codeSecret() {
        StringBuilder result = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) result.append(ALPHABET.charAt(RANDOM.nextInt(32)));
        return result.toString();
    }

    public static String shortCode() {
        return String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
    }

    public static String fingerprintPin(X509Certificate leaf) throws GeneralSecurityException {
        byte[] hash = certificateHash(leaf);
        StringBuilder result = new StringBuilder(PIN_LENGTH);
        for (int i = 0; i < PIN_LENGTH; i++) {
            int offset = i * 5, index = offset / 8;
            int pair = (hash[index] & 255) << 8 | (hash[index + 1] & 255);
            result.append(ALPHABET.charAt(pair >> (11 - (offset % 8)) & 31));
        }
        return result.toString();
    }

    static String fingerprintSha256(X509Certificate leaf) throws GeneralSecurityException {
        return hex(certificateHash(leaf));
    }

    private static byte[] certificateHash(X509Certificate leaf) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
    }

    public static SSLContext clientContext(String pin) throws GeneralSecurityException {
        if (!validPin(pin)) throw new GeneralSecurityException("电视身份指纹无效");
        return newClientContext(pin);
    }

    /** First pairing only. The J-PAKE exchange authenticates the certificate if the address was entered manually. */
    public static SSLContext shortPairContext(String discoveredPin) throws GeneralSecurityException {
        if (discoveredPin != null && !validPin(discoveredPin)) {
            throw new GeneralSecurityException("电视身份指纹无效");
        }
        return newClientContext(discoveredPin);
    }

    private static SSLContext newClientContext(String pin) throws GeneralSecurityException {
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
                throw new CertificateException("Unexpected TLS client certificate");
            }
            @Override public void checkServerTrusted(X509Certificate[] c, String type) throws CertificateException {
                if (c == null || c.length == 0) throw new CertificateException("电视未提供证书");
                try {
                    c[0].checkValidity();
                    if (pin != null && !matches(pin, fingerprintPin(c[0]))) {
                        throw new CertificateException("电视身份与连接码不一致");
                    }
                } catch (GeneralSecurityException e) {
                    if (e instanceof CertificateException) throw (CertificateException) e;
                    throw new CertificateException(e);
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust}, RANDOM);
        return context;
    }

    public static boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    public static void restrictProtocols(SSLSocket socket) throws SSLException {
        boolean tls13 = false, tls12 = false;
        for (String protocol : socket.getSupportedProtocols()) {
            if ("TLSv1.3".equals(protocol)) tls13 = true;
            if ("TLSv1.2".equals(protocol)) tls12 = true;
        }
        if (!tls12 && !tls13) throw new SSLException("设备不支持 TLS 1.2 或更高版本");
        socket.setEnabledProtocols(tls13 && tls12 ? new String[] {"TLSv1.3", "TLSv1.2"}
                : new String[] {tls13 ? "TLSv1.3" : "TLSv1.2"});
    }
}
