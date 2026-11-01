package es.subelo.box;

import android.app.Application;

/** Arranca antes que la pantalla, el servicio o el arranque automático: carga los nombres de los usuarios. */
public class BoxApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Users.init(this);
    }
}
