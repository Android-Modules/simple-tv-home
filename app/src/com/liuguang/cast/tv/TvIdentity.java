package com.liuguang.cast.tv;

import android.content.Context;
import android.util.AtomicFile;

import com.liuguang.cast.shared.Pairing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;

/** An app-private TV identity, using software keys for TV Keystores without raw ECDSA. */
record TvIdentity(SSLContext context, String pin) {
    private static final String ALIAS = "receiver";

    static synchronized TvIdentity load(Context context) throws Exception {
        var file = new AtomicFile(new File(context.getNoBackupFilesDir(), "receiver.p12"));
        var password = new char[0]; // The app-private, non-backed-up directory protects this file.
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = file.openRead()) {
            store.load(in, password);
        } catch (FileNotFoundException notCreated) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            store.load(null, password);
            store.setKeyEntry(ALIAS, pair.getPrivate(), password, new Certificate[] {selfSign(pair)});
            FileOutputStream out = file.startWrite();
            try { store.store(out, password); file.finishWrite(out); }
            catch (Exception e) { file.failWrite(out); throw e; }
        }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), null, null);
        return new TvIdentity(tls, Pairing.fingerprintPin((X509Certificate) store.getCertificate(ALIAS)));
    }

    private static X509Certificate selfSign(KeyPair pair) throws Exception {
        // Only the small, fixed RFC 5280 structure for a pinned, self-signed leaf.
        byte[] algorithm = sequence(der(0x06, new byte[] {0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 4, 3, 2}));
        byte[] subject = new X500Principal("CN=Jian Desktop Cast").getEncoded();
        byte[] serial = new BigInteger(128, new SecureRandom()).setBit(127).toByteArray();
        byte[] time = sequence(der(0x17, "700101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x18, "20500101000000Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = sequence(der(0xa0, der(0x02, new byte[] {2})), der(0x02, serial), algorithm,
                subject, time, subject, pair.getPublic().getEncoded());
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(pair.getPrivate());
        ecdsa.update(tbs);
        byte[] cert = sequence(tbs, algorithm, der(0x03, new byte[] {0}, ecdsa.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] sequence(byte[]... values) { return der(0x30, values); }

    private static byte[] der(int tag, byte[]... values) {
        int length = 0;
        for (byte[] value : values) length += value.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 128) out.write(length);
        else if (length < 256) { out.write(0x81); out.write(length); }
        else { out.write(0x82); out.write(length >> 8); out.write(length & 255); }
        for (byte[] value : values) out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
