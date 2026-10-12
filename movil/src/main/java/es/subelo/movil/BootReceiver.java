package es.subelo.movil;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a programar la subida automática al reiniciar el móvil o actualizar la app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AutoUploadJob.schedule(context);
    }
}
