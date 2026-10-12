package es.subelo.movil;

import android.app.Notification;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Servicio en primer plano que sube las fotos con una notificación de progreso. */
public class UploadService extends Service {
    private static final String ACTION_UPLOAD = "es.subelo.movil.UPLOAD";
    private static final String ACTION_SCAN = "es.subelo.movil.SCAN";
    private static final String ACTION_CANCEL = "es.subelo.movil.CANCEL";
    private static final String ACTION_DOWNLOAD = "es.subelo.movil.DOWNLOAD";
    private static final String ACTION_ALBUM = "es.subelo.movil.ALBUM";
    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_DIR = "dir";

    /** Foto del móvil que hay que subir; dir == null: carpeta elegida en ajustes. */
    private static final class UpTask {
        final Uri uri;
        final String dir;

        UpTask(Uri uri, String dir) {
            this.uri = uri;
            this.dir = dir;
        }
    }

    /** Texto de estado que muestra la pantalla principal. */
    static volatile String status = "";
    static volatile boolean busy;

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<Object>();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private int total, done, uploaded, skipped, failed, downloaded;
    private long lastNotify;
    private String fatal;

    /** Sube las fotos elegidas a mano. */
    static void upload(Context c, ArrayList<Uri> uris) {
        upload(c, uris, null);
    }

    /** Sube las fotos a una carpeta concreta del disco (null = la carpeta elegida en ajustes). */
    static void upload(Context c, ArrayList<Uri> uris, String dir) {
        if (uris.isEmpty()) return;
        Intent i = new Intent(c, UploadService.class).setAction(ACTION_UPLOAD);
        i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        if (dir != null) i.putExtra(EXTRA_DIR, dir);
        ClipData cd = null;
        for (Uri u : uris) {
            if (cd == null) cd = ClipData.newRawUri("fotos", u);
            else cd.addItem(new ClipData.Item(u));
        }
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(c, i);
    }

    /** Sube un álbum entero de la galería a una carpeta del disco (lo ya subido se salta). */
    static void uploadAlbum(Context c, long bucket, String dir) {
        start(c, new Intent(c, UploadService.class).setAction(ACTION_ALBUM).putExtra("bucket", bucket).putExtra(EXTRA_DIR, dir));
    }

    /** Sube todo lo pendiente de la galería (subida automática, en primer plano). */
    static void scan(Context c) {
        start(c, new Intent(c, UploadService.class).setAction(ACTION_SCAN));
    }

    /** Descarga al móvil ficheros del disco del box. */
    static void download(Context c, List<BoxClient.RemoteFile> files) {
        if (files.isEmpty()) return;
        ArrayList<String> dirs = new ArrayList<String>(), names = new ArrayList<String>();
        long[] sizes = new long[files.size()], mtimes = new long[files.size()];
        for (int k = 0; k < files.size(); k++) {
            BoxClient.RemoteFile f = files.get(k);
            dirs.add(f.dir);
            names.add(f.name);
            sizes[k] = f.size;
            mtimes[k] = f.mtime;
        }
        Intent i = new Intent(c, UploadService.class).setAction(ACTION_DOWNLOAD);
        i.putStringArrayListExtra("dirs", dirs);
        i.putStringArrayListExtra("names", names);
        i.putExtra("sizes", sizes);
        i.putExtra("mtimes", mtimes);
        start(c, i);
    }

    static void cancel(Context c) {
        start(c, new Intent(c, UploadService.class).setAction(ACTION_CANCEL));
    }

    private static void start(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(progressNotification("Preparando…", 0, 0));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancel.set(true);
            queue.clear();
            if (worker == null) {
                busy = false;
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_UPLOAD.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
            String dir = intent.getStringExtra(EXTRA_DIR);
            if (uris != null) {
                synchronized (this) {
                    total += uris.size();
                }
                for (Uri u : uris) queue.add(new UpTask(u, dir));
            }
        } else if (ACTION_ALBUM.equals(action)) {
            ArrayList<Uri> uris = AlbumsActivity.items(this, intent.getLongExtra("bucket", -1));
            String dir = intent.getStringExtra(EXTRA_DIR);
            synchronized (this) {
                total += uris.size();
            }
            for (Uri u : uris) queue.add(new UpTask(u, dir));
        } else if (ACTION_DOWNLOAD.equals(action)) {
            ArrayList<String> dirs = intent.getStringArrayListExtra("dirs");
            ArrayList<String> names = intent.getStringArrayListExtra("names");
            long[] sizes = intent.getLongArrayExtra("sizes");
            long[] mtimes = intent.getLongArrayExtra("mtimes");
            if (dirs != null && names != null && sizes != null && mtimes != null) {
                synchronized (this) {
                    total += names.size();
                }
                for (int k = 0; k < names.size(); k++) {
                    queue.add(new BoxClient.RemoteFile(dirs.get(k), names.get(k), sizes[k], mtimes[k]));
                }
            }
        } else if (ACTION_SCAN.equals(action)) {
            queue.add(ACTION_SCAN);
        }
        if (worker == null) startWorker();
        return START_NOT_STICKY;
    }

    private void startWorker() {
        cancel.set(false);
        busy = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                work();
            }
        }, "subelo-upload");
        worker.start();
    }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifs.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(Notifs.ID_PROGRESS, n);
    }

    private void work() {
        PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subelo:upload");
        wl.acquire(6 * 60 * 60 * 1000L);
        try {
            setStatus("Conectando con el box…", 0, 0);
            BoxClient bc;
            try {
                bc = BoxClient.connect(this);
            } catch (IOException e) {
                fatal = e.getMessage();
                return;
            }
            Object task;
            while (!cancel.get() && (task = queue.poll()) != null) {
                if (task instanceof UpTask) {
                    if (!uploadOne(bc, (UpTask) task)) return;
                } else if (task instanceof BoxClient.RemoteFile) {
                    if (!downloadOne(bc, (BoxClient.RemoteFile) task)) return;
                } else {
                    if (!scanAll(bc)) return;
                }
            }
        } finally {
            if (wl.isHeld()) wl.release();
            main.post(new Runnable() {
                @Override
                public void run() {
                    onWorkerDone();
                }
            });
        }
    }

    /** @return false si hay que parar (box inaccesible). */
    private boolean uploadOne(BoxClient bc, UpTask task) {
        final Uploader.Item it = Uploader.describe(getContentResolver(), task.uri);
        final String dir = task.dir != null ? task.dir : Uploader.destFor(this, it.mtime);
        final int n;
        synchronized (this) {
            n = done + 1;
        }
        setStatus("Subiendo " + n + " de " + total + ": " + it.name, 0, 0);
        IOException last = null;
        for (int attempt = 0; attempt < 3 && !cancel.get(); attempt++) {
            try {
                Uploader.Result r = Uploader.upload(this, bc, it, dir, new BoxClient.Progress() {
                    @Override
                    public void onProgress(long sent, long size) {
                        setStatus("Subiendo " + n + " de " + total + ": " + it.name, sent, size);
                    }
                }, cancel);
                if (r == Uploader.Result.UPLOADED) uploaded++;
                else skipped++;
                last = null;
                break;
            } catch (BoxClient.BoxException e) {
                if (e.code == 401 || e.code == 503) {
                    fatal = e.getMessage();
                    return false;
                }
                last = e;
            } catch (FileNotFoundException e) {
                last = e;
                break;
            } catch (IOException e) {
                last = e;
                SystemClock.sleep(2000);
            } catch (SecurityException e) {
                last = new IOException("Sin permiso para leer la foto");
                break;
            }
        }
        if (last != null) failed++;
        synchronized (this) {
            done++;
        }
        return true;
    }

    private boolean downloadOne(BoxClient bc, final BoxClient.RemoteFile f) {
        final int n;
        synchronized (this) {
            n = done + 1;
        }
        final String label = "Descargando " + n + " de " + total + ": " + f.name;
        setStatus(label, 0, 0);
        boolean ok = false;
        for (int attempt = 0; attempt < 3 && !cancel.get() && !ok; attempt++) {
            try {
                Downloader.Result r = Downloader.save(this, bc, f, new BoxClient.Progress() {
                    @Override
                    public void onProgress(long got, long size) {
                        setStatus(label, got, size);
                    }
                }, cancel);
                if (r == Downloader.Result.SAVED) downloaded++;
                else skipped++;
                ok = true;
            } catch (BoxClient.BoxException e) {
                if (e.code == 401 || e.code == 503) {
                    fatal = e.getMessage();
                    return false;
                }
                break;
            } catch (IOException e) {
                SystemClock.sleep(2000);
            } catch (RuntimeException e) {
                break;
            }
        }
        if (!ok && !cancel.get()) failed++;
        synchronized (this) {
            done++;
        }
        return true;
    }

    private boolean scanAll(BoxClient bc) {
        Uploader.SCAN_LOCK.lock();
        try {
            Uploader.Stats s = Uploader.runPending(this, bc, cancel, Long.MAX_VALUE, new Uploader.Listener() {
                @Override
                public void onItem(Uploader.Item it, int n, Uploader.Stats st) {
                    setStatus("Subiendo pendientes (" + (n + 1) + "): " + it.name, 0, 0);
                }

                @Override
                public void onBytes(Uploader.Item it, long sent, long size) {
                    setStatus("Subiendo pendientes: " + it.name, sent, size);
                }
            });
            uploaded += s.uploaded;
            skipped += s.skipped;
            failed += s.failed;
            synchronized (this) {
                total += s.uploaded + s.skipped + s.failed;
                done += s.uploaded + s.skipped + s.failed;
            }
            if (s.error != null) {
                fatal = s.error;
                return false;
            }
            return true;
        } finally {
            Uploader.SCAN_LOCK.unlock();
        }
    }

    private void setStatus(String text, long sent, long size) {
        String pct = size > 0 ? " (" + (sent * 100 / size) + "%)" : "";
        status = text + pct;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotify < 700 && sent > 0) return;
        lastNotify = now;
        int max, prog;
        synchronized (this) {
            max = total;
            prog = done;
        }
        try {
            Notifs.nm(this).notify(Notifs.ID_PROGRESS, progressNotification(text + pct, prog, max));
        } catch (Exception ignored) {
        }
    }

    private Notification progressNotification(String text, int progress, int max) {
        Notification.Builder b = Notifs.builder(this, Notifs.CH_PROGRESS)
                .setContentTitle("Súbelo: transfiriendo con el box")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (max > 0) b.setProgress(max, progress, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    /** Se ejecuta en el hilo principal, igual que onStartCommand, así no se pierde nada de la cola. */
    private void onWorkerDone() {
        worker = null;
        String summary;
        if (cancel.get()) {
            summary = "Cancelado. Subidas: " + uploaded + ". Descargadas: " + downloaded + ".";
        } else if (fatal != null) {
            summary = fatal + (uploaded > 0 ? "\nAntes del error se subieron " + uploaded + "." : "");
        } else {
            summary = (uploaded > 0 || downloaded == 0 ? "Subidas al disco: " + uploaded + ". " : "")
                    + (downloaded > 0 ? "Descargadas al móvil (carpeta Subelo): " + downloaded + ". " : "")
                    + (skipped > 0 ? "Ya estaban: " + skipped + ". " : "")
                    + (failed > 0 ? "Con error: " + failed + "." : "");
        }
        status = summary;
        Notifs.result(this, fatal != null ? "Súbelo: no se pudo terminar" : "Súbelo: terminado", summary);
        boolean again = !queue.isEmpty() && !cancel.get() && fatal == null;
        synchronized (this) {
            int pendingUris = 0;
            for (Object o : queue) if (!(o instanceof String)) pendingUris++;
            total = pendingUris;
            done = uploaded = skipped = failed = downloaded = 0;
        }
        fatal = null;
        if (again) {
            startWorker();
            return;
        }
        queue.clear();
        busy = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cancel.set(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
