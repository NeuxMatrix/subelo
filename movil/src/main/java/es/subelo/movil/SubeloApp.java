package es.subelo.movil;

import android.app.Application;

/** Arranque de la app: vigila las pantallas para el bloqueo con huella o contraseña. */
public class SubeloApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new AppLock.Watcher());
    }
}
