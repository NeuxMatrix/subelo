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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    // ---------- el ordenador: autoridad propia del box ----------
    //
    // Chrome no deja usar como app una web con aviso de certificado. Para el ordenador, el box crea una
    // autoridad propia (que se instala una vez en Windows) y firma con ella un certificado con sus IP, que
    // se sirve en otro puerto (Config.WEB_PORT). La autoridad solo vale para direcciones de casa y para
    // «subelo.local» (restricción de nombres): aunque alguien robara su clave, no podría hacerse pasar por
    // ninguna web de internet. El móvil sigue con el certificado de siempre, cuya huella memorizó.

    private static final byte[] O = oid(2, 5, 4, 10);
    private static final byte[] BASIC = oid(2, 5, 29, 19);
    private static final byte[] KEY_USAGE = oid(2, 5, 29, 15);
    private static final byte[] EXT_KEY_USAGE = oid(2, 5, 29, 37);
    private static final byte[] SERVER_AUTH = oid(1, 3, 6, 1, 5, 5, 7, 3, 1);
    private static final byte[] ALT_NAMES = oid(2, 5, 29, 17);
    private static final byte[] NAME_CONSTRAINTS = oid(2, 5, 29, 30);
    private static final byte[] SUBJECT_KEY_ID = oid(2, 5, 29, 14);
    private static final byte[] AUTHORITY_KEY_ID = oid(2, 5, 29, 35);
    static final String WEB_NAME = "subelo.local";

    private static SSLContext webCtx;
    private static List<String> webIps;

    /** La autoridad del box: se crea la primera vez y se guarda en la app (no en el disco). */
    private static synchronized KeyStore.PrivateKeyEntry ca(File dir) throws Exception {
        File f = new File(dir, "ordenador-ca.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (f.isFile()) {
            FileInputStream in = new FileInputStream(f);
            try {
                ks.load(in, PASS);
                KeyStore.Entry e = ks.getEntry(ALIAS, new KeyStore.PasswordProtection(PASS));
                if (e instanceof KeyStore.PrivateKeyEntry) return (KeyStore.PrivateKeyEntry) e;
            } catch (Exception ignored) {
                // dañado: se crea otra (habrá que volver a instalarla en el ordenador)
            } finally {
                in.close();
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        byte[] name = seq(set(seq(CN, tlv(0x0c, "Súbelo · box de casa".getBytes("UTF-8")))),
                set(seq(O, tlv(0x0c, "Súbelo".getBytes("UTF-8")))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 86400000L)), time(new Date(now + 20L * 365 * 86400000L)));
        byte[] constraints = seq(tlv(0xa0, concat( // solo direcciones de casa
                seq(tlv(0x87, new byte[]{(byte) 192, (byte) 168, 0, 0, (byte) 255, (byte) 255, 0, 0})),
                seq(tlv(0x87, new byte[]{10, 0, 0, 0, (byte) 255, 0, 0, 0})),
                seq(tlv(0x87, new byte[]{(byte) 172, 16, 0, 0, (byte) 255, (byte) 240, 0, 0})),
                seq(tlv(0x82, WEB_NAME.getBytes("US-ASCII"))))));
        byte[] exts = tlv(0xa3, seq(
                ext(BASIC, true, seq(tlv(0x01, new byte[]{(byte) 0xff}), tlv(0x02, new byte[]{0}))),
                ext(KEY_USAGE, true, tlv(0x03, new byte[]{1, 0x06})), // firmar certificados y listas
                ext(NAME_CONSTRAINTS, true, constraints),
                ext(SUBJECT_KEY_ID, false, tlv(0x04, keyId(kp.getPublic().getEncoded())))));
        X509Certificate cert = sign(name, validity, name, kp.getPublic().getEncoded(), exts, kp.getPrivate());
        ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASS);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{cert});
        File tmp = new File(dir, "ordenador-ca.p12.tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            ks.store(out, PASS);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar el certificado del ordenador");
        return new KeyStore.PrivateKeyEntry(kp.getPrivate(), new Certificate[]{cert});
    }

    /** El certificado de la autoridad (DER), para instalarlo en el ordenador. */
    static byte[] caCert(File dir) throws IOException {
        try {
            return ca(dir).getCertificate().getEncoded();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo preparar el certificado: " + e.getMessage(), e);
        }
    }

    /** Huella SHA-1 de la autoridad en grupos de 8, como la enseña Windows al instalarla. */
    static String caThumbprint(File dir) {
        try {
            String h = hex(MessageDigest.getInstance("SHA-1").digest(caCert(dir))).toUpperCase(Locale.US);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < h.length(); i += 8) sb.append(i > 0 ? " " : "").append(h, i, Math.min(h.length(), i + 8));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Cifrado para el puerto del ordenador; si han cambiado las IP del box, firma otro certificado. */
    static synchronized SSLContext webContext(File dir) throws IOException {
        List<String> ips = Net.localIps();
        if (webCtx != null && ips.equals(webIps)) return webCtx;
        try {
            KeyStore.PrivateKeyEntry ca = ca(dir);
            X509Certificate caCert = (X509Certificate) ca.getCertificate();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            byte[] name = seq(set(seq(CN, tlv(0x0c, "Súbelo (ordenador)".getBytes("UTF-8")))));
            long now = System.currentTimeMillis();
            byte[] validity = seq(time(new Date(now - 86400000L)), time(new Date(now + 397L * 86400000L)));
            ByteArrayOutputStream alt = new ByteArrayOutputStream();
            for (String ip : ips) {
                byte[] a = java.net.InetAddress.getByName(ip).getAddress();
                if (home(a)) alt.write(tlv(0x87, a)); // otra (VPN…) invalidaría el certificado entero
            }
            alt.write(tlv(0x82, WEB_NAME.getBytes("US-ASCII")));
            byte[] caKeyId = keyId(caCert.getPublicKey().getEncoded());
            byte[] exts = tlv(0xa3, seq(
                    ext(BASIC, true, seq()),
                    ext(KEY_USAGE, true, tlv(0x03, new byte[]{5, (byte) 0xa0})), // firma y cifrado de claves
                    ext(EXT_KEY_USAGE, false, seq(SERVER_AUTH)),
                    ext(ALT_NAMES, false, tlv(0x30, alt.toByteArray())),
                    ext(AUTHORITY_KEY_ID, false, seq(tlv(0x80, caKeyId)))));
            X509Certificate leaf = sign(caCert.getSubjectX500Principal().getEncoded(), validity, name,
                    kp.getPublic().getEncoded(), exts, ca.getPrivateKey());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, PASS);
            ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{leaf, caCert});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASS);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            webCtx = ctx;
            webIps = new ArrayList<String>(ips);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo preparar el cifrado del ordenador: " + e.getMessage(), e);
        }
    }

    /** ¿Es una dirección de casa (las que permite la autoridad)? */
    static boolean home(byte[] a) {
        if (a.length != 4) return false;
        int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
        return b0 == 10 || (b0 == 192 && b1 == 168) || (b0 == 172 && b1 >= 16 && b1 <= 31);
    }

    private static X509Certificate sign(byte[] issuer, byte[] validity, byte[] subject, byte[] publicKey, byte[] exts,
                                        PrivateKey key) throws Exception {
        byte[] algo = seq(SHA256_RSA, new byte[]{0x05, 0x00});
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xa0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, algo, issuer, validity, subject, publicKey, exts);
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

    private static byte[] ext(byte[] id, boolean critical, byte[] value) throws IOException {
        return critical ? seq(id, tlv(0x01, new byte[]{(byte) 0xff}), tlv(0x04, value)) : seq(id, tlv(0x04, value));
    }

    /** Identificador de clave: SHA-1 de la clave pública (el contenido del BIT STRING). */
    private static byte[] keyId(byte[] spki) throws Exception {
        // SubjectPublicKeyInfo = SEQUENCE { algoritmo, BIT STRING }: el BIT STRING es lo último
        int i = 1, len = spki[i++] & 0xff;
        if (len > 0x80) i += len - 0x80;
        i += 1;
        int algLen = spki[i++] & 0xff;
        if (algLen > 0x80) {
            int n = algLen - 0x80;
            algLen = 0;
            for (int k = 0; k < n; k++) algLen = (algLen << 8) | (spki[i++] & 0xff);
        }
        i += algLen; // saltado el algoritmo; ahora el BIT STRING
        i += 1;
        int bl = spki[i++] & 0xff;
        if (bl > 0x80) {
            int n = bl - 0x80;
            bl = 0;
            for (int k = 0; k < n; k++) bl = (bl << 8) | (spki[i++] & 0xff);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(spki, i + 1, bl - 1); // sin el byte de «bits sobrantes»
        return md.digest();
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
