package es.subelo.box;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Cifrado HTTPS con un certificado propio del box, creado la primera vez y guardado en la app.
 * El móvil memoriza su huella al emparejar y después solo habla con ese box.
 */
final class Tls {
    private static final char[] PASS = "subelo".toCharArray();
    private static final String ALIAS = "subelo";

    private Tls() {}

    static SSLServerSocketFactory serverFactory(File dir) throws IOException {
        return context(dir).getServerSocketFactory();
    }

    static SSLContext context(File dir) throws IOException {
        try {
            KeyStore ks = keyStore(dir);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASS);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo preparar el cifrado: " + e.getMessage(), e);
        }
    }

    /** SHA-256 del certificado en hexadecimal (la «huella» del box). */
    static String fingerprint(File dir) {
        try {
            Certificate c = keyStore(dir).getCertificate(ALIAS);
            return hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (Exception e) {
            return "";
        }
    }

    /** Código corto para comparar a simple vista con el que muestra el móvil, p. ej. «3F2A-91C0». */
    static String shortCode(String fp) {
        if (fp == null || fp.length() < 8) return "????-????";
        String s = fp.toUpperCase(Locale.US);
        return s.substring(0, 4) + "-" + s.substring(4, 8);
    }

    private static synchronized KeyStore keyStore(File dir) throws Exception {
        File f = new File(dir, "tls.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (f.isFile()) {
            FileInputStream in = new FileInputStream(f);
            try {
                ks.load(in, PASS);
                if (ks.getKey(ALIAS, PASS) != null) return ks;
            } catch (Exception ignored) {
                // fichero dañado: se crea otro
            } finally {
                in.close();
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = selfSigned(kp);
        ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASS);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{cert});
        File tmp = new File(dir, "tls.p12.tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            ks.store(out, PASS);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar el certificado");
        return ks;
    }

    // ---------- certificado X.509 autofirmado, escrito a mano en DER ----------

    private static final byte[] SHA256_RSA = oid(1, 2, 840, 113549, 1, 1, 11);
    private static final byte[] CN = oid(2, 5, 4, 3);

    static X509Certificate selfSigned(KeyPair kp) throws Exception {
        byte[] name = seq(set(seq(CN, tlv(0x0c, "Subelo box".getBytes("UTF-8")))));
        byte[] algo = seq(SHA256_RSA, new byte[]{0x05, 0x00});
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 86400000L)), time(new Date(now + 30L * 365 * 86400000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, algo, name, validity, name, kp.getPublic().getEncoded());
        PrivateKey key = kp.getPrivate();
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algo, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static byte[] time(Date d) throws IOException {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat gen = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        gen.setTimeZone(TimeZone.getTimeZone("UTC"));
        int year = Integer.parseInt(gen.format(d).substring(0, 4));
        return year < 2050 ? tlv(0x17, utc.format(d).getBytes("US-ASCII")) : tlv(0x18, gen.format(d).getBytes("US-ASCII"));
    }

    private static byte[] seq(byte[]... parts) throws IOException {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) throws IOException {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p);
        return b.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int len = value.length;
        if (len < 0x80) {
            b.write(len);
        } else {
            int n = len > 0xffffff ? 4 : len > 0xffff ? 3 : len > 0xff ? 2 : 1;
            b.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) b.write((len >> (8 * i)) & 0xff);
        }
        b.write(value);
        return b.toByteArray();
    }

    private static byte[] oid(long... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((int) (parts[0] * 40 + parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long v = parts[i];
            byte[] tmp = new byte[10];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7f);
            while ((v >>= 7) > 0) tmp[n++] = (byte) (0x80 | (v & 0x7f));
            for (int k = n - 1; k >= 0; k--) b.write(tmp[k]);
        }
        byte[] body = b.toByteArray();
        byte[] out = new byte[body.length + 2];
        out[0] = 0x06;
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
