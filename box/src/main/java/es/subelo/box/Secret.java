package es.subelo.box;

import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cifra secretos pequeños (la sesión de Amazon) con una llave del almacén seguro de Android, que
 * no se puede sacar del box. En Android 5 (sin ese almacén) no cifra: devuelve null y se guarda
 * como antes.
 */
final class Secret {
    private static final String KEY_ALIAS = "subelo-amazon";

    private Secret() {}

    private static Boolean works;

    static boolean available() {
        return Build.VERSION.SDK_INT >= 23;
    }

    /** ¿Se puede cifrar y descifrar de verdad en este box? (se prueba una vez) */
    static synchronized boolean works() {
        if (works == null) {
            String[] s = seal("prueba");
            works = s != null && "prueba".equals(open(s[0], s[1]));
        }
        return works;
    }

    /** {iv, cifrado} en base64, o null si no se puede cifrar aquí. */
    static String[] seal(String plain) {
        if (!available()) return null;
        try {
            return sealM(plain);
        } catch (Exception e) {
            return null;
        }
    }

    /** El texto, o null si no se puede descifrar (llave perdida, datos rotos). */
    static String open(String iv, String data) {
        if (!available() || iv == null || data == null) return null;
        try {
            return openM(iv, data);
        } catch (Exception e) {
            return null;
        }
    }

    @TargetApi(23)
    private static String[] sealM(String plain) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] enc = ci.doFinal(plain.getBytes("UTF-8"));
        return new String[]{Base64.encodeToString(ci.getIV(), Base64.NO_WRAP), Base64.encodeToString(enc, Base64.NO_WRAP)};
    }

    @TargetApi(23)
    private static String openM(String iv, String data) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(ci.doFinal(Base64.decode(data, Base64.NO_WRAP)), "UTF-8");
    }

    @TargetApi(23)
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
}
