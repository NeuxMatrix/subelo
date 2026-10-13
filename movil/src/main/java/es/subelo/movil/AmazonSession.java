package es.subelo.movil;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * La sesión de Amazon guardada en el móvil, para ver las copias de seguridad fuera de casa
 * (sin el box). Va cifrada con una llave del almacén seguro del móvil, que no se puede sacar de él.
 *
 * Aquí también se guarda la huella de la contraseña de Súbelo que pide «☁ Copias en Amazon»,
 * para poder comprobarla sin el box.
 */
final class AmazonSession {
    private static final String KEY_ALIAS = "subelo-amazon";
    private static final int ITERATIONS = 20000;

    private AmazonSession() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("amazon", Context.MODE_PRIVATE);
    }

    // ---------- sesión de Amazon ----------

    static void save(Context c, String cookie, String tld) {
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] enc = ci.doFinal(cookie.getBytes("UTF-8"));
            p(c).edit().putString("iv", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP))
                    .putString("cookie", Base64.encodeToString(enc, Base64.NO_WRAP))
                    .putString("tld", tld).remove("caducada").apply();
        } catch (Exception e) {
            clear(c); // sin almacén seguro no se guarda: tocará entrar otra vez
        }
    }

    /** La sesión, o null si no hay (o no se puede descifrar). */
    static String cookie(Context c) {
        SharedPreferences p = p(c);
        String iv = p.getString("iv", null), enc = p.getString("cookie", null);
        if (iv == null || enc == null) return null;
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(enc, Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static String tld(Context c) {
        return p(c).getString("tld", "es");
    }

    static void clear(Context c) {
        p(c).edit().remove("iv").remove("cookie").remove("caducada").apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    // ---------- contraseña para ver las copias ----------

    /** ¿Ya se sabe comprobar la contraseña sin el box? */
    static boolean hasPassword(Context c) {
        return p(c).contains("hash") && user(c).equals(Prefs.user(c));
    }

    private static String user(Context c) {
        return p(c).getString("usuario", "");
    }

    /** Guarda la huella de la contraseña (ya comprobada con el box). */
    static void rememberPassword(Context c, String password) {
        if (Prefs.user(c) == null) return;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        p(c).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
                .putString("usuario", Prefs.user(c))
                .remove("fallos").remove("hasta").apply();
    }

    static void forgetPassword(Context c) {
        p(c).edit().remove("salt").remove("hash").remove("usuario").apply();
    }

    static long waitMs(Context c) {
        return Math.max(0, p(c).getLong("hasta", 0) - System.currentTimeMillis());
    }

    /** Comprueba la contraseña sin el box; tras 5 fallos seguidos hay que esperar. */
    static boolean check(Context c, String password) {
        if (waitMs(c) > 0 || !hasPassword(c)) return false;
        SharedPreferences p = p(c);
        byte[] salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP);
        byte[] want = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP);
        if (want.length > 0 && MessageDigest.isEqual(want, hash(password, salt))) {
            p.edit().remove("fallos").remove("hasta").apply();
            return true;
        }
        int fails = p.getInt("fallos", 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt("fallos", fails);
        if (fails >= 5) e.putLong("hasta", System.currentTimeMillis() + (30000L << Math.min(6, fails - 5)));
        e.apply();
        return false;
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
