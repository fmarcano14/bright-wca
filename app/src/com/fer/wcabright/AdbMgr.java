package com.fer.wcabright;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

public class AdbMgr extends AbsAdbConnectionManager {
    private static AdbMgr INSTANCE;
    public static synchronized AdbMgr get(Context c) throws Exception {
        if (INSTANCE == null) INSTANCE = new AdbMgr(c.getApplicationContext());
        return INSTANCE;
    }
    private final PrivateKey key;
    private final Certificate cert;

    private AdbMgr(Context c) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File kf = new File(c.getFilesDir(), "adb.key"), cf = new File(c.getFilesDir(), "adb.crt");
        if (kf.exists() && cf.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(kf)));
            try (InputStream in = new FileInputStream(cf)) {
                cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom());
            KeyPair kp = g.generateKeyPair();
            key = kp.getPrivate();
            X500Name n = new X500Name("CN=Bright WCA");
            Date nb = new Date(System.currentTimeMillis() - 86400000L);
            Date na = new Date(System.currentTimeMillis() + 3650L * 86400000L);
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(n,
                    BigInteger.valueOf(System.currentTimeMillis()), nb, na, n, kp.getPublic());
            cert = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withRSA").build(key)));
            write(kf, key.getEncoded());
            write(cf, cert.getEncoded());
        }
    }
    static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return o.toByteArray();
        }
    }
    static void write(File f, byte[] d) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(d); }
    }
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return cert; }
    @Override protected String getDeviceName() { return "BrightWCA"; }
}
