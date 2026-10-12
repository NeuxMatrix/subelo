package es.subelo.box;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Arranca el servidor al encender el box. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Config.autostart(context)) ServerService.start(context);
    }
}
