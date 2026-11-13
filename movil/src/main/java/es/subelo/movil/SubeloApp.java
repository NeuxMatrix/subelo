package es.subelo.movil;

import android.app.Application;

/** Arranque de la app: vigila las pantallas para el bloqueo con huella o contraseña. */
public class SubeloApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new AppLock.Watcher());
        forgetAmazonCopies();
    }

    /**
     * Hasta la 2.29, «☁ Copias en Amazon» guardaba en el móvil (cifradas) la sesión de Amazon y la
     * huella de la contraseña de Súbelo. Esa función ya no existe: se borra todo lo que dejó.
     */
    private void forgetAmazonCopies() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("amazon", MODE_PRIVATE);
            if (!p.getAll().isEmpty()) p.edit().clear().apply();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias("subelo-amazon")) ks.deleteEntry("subelo-amazon");
        } catch (Exception ignored) {
        }
    }
}
