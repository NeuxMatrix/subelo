package es.subelo.movil;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class Notifs {
    static final String CH_PROGRESS = "progreso";
    static final String CH_RESULT = "resultado";
    static final int ID_PROGRESS = 1;
    static final int ID_RESULT = 2;

    private Notifs() {}

    static NotificationManager nm(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS, "Subida en curso", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_RESULT, "Resultado de las subidas", NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        nm(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE));
        return b;
    }

    static void result(Context c, String title, String text) {
        try {
            nm(c).notify(ID_RESULT, builder(c, CH_RESULT).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());
        } catch (Exception ignored) {
            // sin permiso de notificaciones
        }
    }
}
