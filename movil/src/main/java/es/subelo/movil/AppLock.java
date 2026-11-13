package es.subelo.movil;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Bloqueo de la app: al abrirla (o al volver tras más de un minuto fuera) pide la huella o la
 * contraseña de Súbelo. La contraseña no se guarda: solo una huella PBKDF2 con sal, para poder
 * comprobarla sin el box (fuera de casa también).
 */
final class AppLock {
    /** Tiempo fuera de la app que se perdona (para compartir a Amazon Photos, elegir fotos…). */
    static final long GRACE_MS = 60 * 1000L;
    private static final int ITERATIONS = 20000;
    private static final int MAX_FAILS = 5;

    private static boolean unlocked;
    private static int started;
    private static long leftAt;

    private AppLock() {}

    static boolean isUnlocked() {
        return unlocked;
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("bloqueo", Context.MODE_PRIVATE);
    }

    /** Activado y con contraseña guardada (y con sesión: sin sesión ya pide entrar en el box). */
    static boolean enabled(Context c) {
        SharedPreferences p = p(c);
        return p.getBoolean("on", false) && p.contains("hash") && Prefs.loggedIn(c);
    }

    static boolean useBiometric(Context c) {
        return p(c).getBoolean("huella", true) && biometricPossible(c);
    }

    static void setUseBiometric(Context c, boolean on) {
        p(c).edit().putBoolean("huella", on).apply();
    }

    /** Si el móvil tiene huella (o cara) configurada para apps. */
    static boolean biometricPossible(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }
        if (Build.VERSION.SDK_INT == 29) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        }
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
    }

    /** Activa el bloqueo con esta contraseña (ya comprobada con el box). */
    static void enable(Context c, String password) {
        setPassword(c, password);
        p(c).edit().putBoolean("on", true).apply();
        unlocked = true;
    }

    static void disable(Context c) {
        p(c).edit().putBoolean("on", false).remove("hash").remove("salt").remove("fallos").remove("hasta").apply();
    }

    /** Tras entrar en el box con la contraseña: si el bloqueo está activado, se queda con la nueva. */
    static void loggedIn(Context c, String password) {
        if (p(c).getBoolean("on", false)) setPassword(c, password);
        unlocked = true;
    }

    private static void setPassword(Context c, String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        p(c).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
                .remove("fallos").remove("hasta").apply();
    }

    /** Milisegundos que quedan de espera por fallar muchas veces (0 = puede intentarlo). */
    static long waitMs(Context c) {
        return Math.max(0, p(c).getLong("hasta", 0) - System.currentTimeMillis());
    }

    /** Comprueba la contraseña; tras 5 fallos seguidos hay que esperar (30 s, 1 min, 2 min…). */
    static boolean check(Context c, String password) {
        if (waitMs(c) > 0) return false;
        SharedPreferences p = p(c);
        byte[] salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP);
        byte[] want = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP);
        if (want.length > 0 && MessageDigest.isEqual(want, hash(password, salt))) {
            p.edit().remove("fallos").remove("hasta").apply();
            unlocked = true;
            return true;
        }
        int fails = p.getInt("fallos", 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt("fallos", fails);
        if (fails >= MAX_FAILS) {
            long wait = 30000L << Math.min(6, fails - MAX_FAILS);
            e.putLong("hasta", System.currentTimeMillis() + wait);
        }
        e.apply();
        return false;
    }

    /** Huella o cara correcta. */
    static void biometricOk(Context c) {
        p(c).edit().remove("fallos").remove("hasta").apply();
        unlocked = true;
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Vigila las pantallas de la app: si toca, pone delante la de desbloqueo. */
    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(Activity a) {
            if (started++ == 0) {
                if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > GRACE_MS) unlocked = false;
                leftAt = 0;
            }
        }

        @Override
        public void onActivityStopped(Activity a) {
            if (--started <= 0) {
                started = 0;
                leftAt = a.isChangingConfigurations() ? 0 : SystemClock.elapsedRealtime(); // girar no cuenta
            }
        }

        @Override
        public void onActivityResumed(Activity a) {
            if (unlocked || a instanceof LockActivity || a instanceof LoginActivity || !enabled(a)) return;
            a.startActivity(new Intent(a, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            a.overridePendingTransition(0, 0);
        }

        @Override
        public void onActivityCreated(Activity a, Bundle b) {}

        @Override
        public void onActivityPaused(Activity a) {}

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        @Override
        public void onActivityDestroyed(Activity a) {}
    }
}
