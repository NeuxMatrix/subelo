package es.subelo.box;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

/** Servicio en primer plano que mantiene vivo el servidor HTTP y lo anuncia en la red (mDNS). */
public class ServerService extends Service {
    private static final String TAG = "SubeloServer";
    private static final String CHANNEL = "servidor";
    static final String SERVICE_TYPE = "_subelo._tcp";

    static volatile boolean running;
    static volatile String error;

    private HttpServer http;
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicast;

    static void start(Context c) {
        Intent i = new Intent(c, ServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            error = "No se pudo arrancar el servidor: " + e.getMessage();
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ServerService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        if (http == null) {
            try {
                http = new HttpServer(this, Config.PORT);
                http.start();
                running = true;
                error = null;
                acquireLocks();
                registerNsd();
                startForeground(1, notification());
            } catch (IOException e) {
                Log.e(TAG, "start", e);
                error = "No se pudo abrir el puerto " + Config.PORT + ": " + e.getMessage();
                http = null;
                running = false;
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (http != null) http.stop();
        http = null;
        if (nsd != null && reg != null) {
            try {
                nsd.unregisterService(reg);
            } catch (Exception ignored) {
            }
        }
        if (wake != null && wake.isHeld()) wake.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (multicast != null && multicast.isHeld()) multicast.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subelo:server");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "subelo:wifi");
                wifiLock.acquire();
                multicast = wm.createMulticastLock("subelo:mdns");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "locks", e);
        }
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            NsdServiceInfo si = new NsdServiceInfo();
            String model = Build.MODEL == null ? "box" : Build.MODEL.replaceAll("[^A-Za-z0-9 _-]", "");
            si.setServiceName("Subelo " + model);
            si.setServiceType(SERVICE_TYPE);
            si.setPort(Config.PORT);
            reg = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo s, int code) {
                    Log.w(TAG, "mDNS registro falló " + code);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo s, int code) {
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo s) {
                    Log.i(TAG, "mDNS registrado como " + s.getServiceName());
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo s) {
                }
            };
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception e) {
            Log.w(TAG, "nsd", e);
        }
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Servidor", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        String url = Net.mainUrl();
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Súbelo: servidor activo")
                .setContentText(url != null ? url : "sin red")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags));
        return b.build();
    }
}
