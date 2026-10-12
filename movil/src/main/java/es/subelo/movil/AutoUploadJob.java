package es.subelo.movil;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.MediaStore;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subida automática en segundo plano. Android la lanza:
 *  - cuando aparece una foto/vídeo nuevo en la galería (y hay Wi-Fi),
 *  - y cada hora como respaldo (por si al hacer la foto estabas fuera de casa).
 */
public class AutoUploadJob extends JobService {
    private static final int JOB_NEW_MEDIA = 1;
    private static final int JOB_PERIODIC = 2;
    private static final int JOB_CONTINUE = 3;

    private AtomicBoolean cancel;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!Prefs.auto(c)) {
            js.cancel(JOB_NEW_MEDIA);
            js.cancel(JOB_PERIODIC);
            js.cancel(JOB_CONTINUE);
            return;
        }
        scheduleNewMedia(c);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, AutoUploadJob.class))
                    .setPeriodic(60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .build());
        }
    }

    private static void scheduleNewMedia(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_NEW_MEDIA, new ComponentName(c, AutoUploadJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(15 * 1000L)
                .setTriggerContentMaxDelay(2 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build());
    }

    private static void scheduleContinue(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_CONTINUE, new ComponentName(c, AutoUploadJob.class))
                .setMinimumLatency(5 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        if (!Prefs.auto(ctx) || !Prefs.loggedIn(ctx)) return false;
        final AtomicBoolean myCancel = new AtomicBoolean();
        cancel = myCancel;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean more = false;
                try {
                    more = runOnce(ctx, myCancel);
                } finally {
                    jobFinished(params, false);
                    if (params.getJobId() == JOB_NEW_MEDIA) scheduleNewMedia(ctx);
                    if (more && !myCancel.get()) scheduleContinue(ctx);
                }
            }
        }, "subelo-auto").start();
        return true;
    }

    /** @return true si quedan fotos por subir (se acabó el tiempo del trabajo). */
    private static boolean runOnce(Context ctx, AtomicBoolean cancel) {
        if (!Uploader.SCAN_LOCK.tryLock()) return false; // ya hay una subida de pendientes en marcha
        try {
            if (Uploader.pending(ctx, 1).isEmpty()) return false;
            BoxClient bc;
            try {
                bc = BoxClient.connect(ctx);
            } catch (IOException e) {
                return false; // fuera de casa o box apagado: se reintentará
            }
            long deadline = System.currentTimeMillis() + 8 * 60 * 1000L;
            Uploader.Stats s = Uploader.runPending(ctx, bc, cancel, deadline, null);
            if (s.uploaded > 0 || s.failed > 0) {
                String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
                String txt = s.uploaded + " subidas" + (s.failed > 0 ? ", " + s.failed + " con error" : "");
                Prefs.setLastAuto(ctx, when + ": " + txt);
                Notifs.result(ctx, "Súbelo: fotos copiadas al disco", txt);
            }
            return s.more;
        } finally {
            Uploader.SCAN_LOCK.unlock();
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (cancel != null) cancel.set(true);
        return true;
    }
}
