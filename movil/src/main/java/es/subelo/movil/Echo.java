package es.subelo.movil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * «Enviar al Echo»: copia las fotos elegidas del box a la galería del móvil (Imágenes/Subelo/Echo,
 * que la subida automática ignora) y las abre en Amazon Photos. Amazon no deja meterlas en un
 * álbum desde otra app, así que allí se añaden a mano al álbum que muestra el Echo.
 */
final class Echo {
    static final String AMAZON_PHOTOS = "com.amazon.clouddrive.photos";
    static final int REQ_WRITE = 41;

    private static List<BoxClient.RemoteFile> pending;

    private Echo() {}

    /** Pregunta y, si dice que sí, prepara y comparte las fotos de la lista (los vídeos se saltan). */
    static void send(final Activity a, List<BoxClient.RemoteFile> list) {
        final List<BoxClient.RemoteFile> photos = new ArrayList<BoxClient.RemoteFile>();
        for (BoxClient.RemoteFile f : list) if (Downloader.isImage(f.name)) photos.add(f);
        if (photos.isEmpty()) {
            Toast.makeText(a, "Al Echo solo se envían fotos", Toast.LENGTH_SHORT).show();
            return;
        }
        int skipped = list.size() - photos.size();
        String what = photos.size() == 1 ? "la foto" : "las " + photos.size() + " fotos";
        new AlertDialog.Builder(a)
                .setTitle("Enviar al Echo")
                .setMessage("Guardo " + what + " en el móvil (galería › Subelo › Echo) y las abro en Amazon Photos."
                        + "\n\nAmazon las sube a tu cuenta; después añádelas allí al álbum que muestra tu Echo."
                        + (skipped > 0 ? "\n\n" + skipped + (skipped == 1 ? " vídeo o archivo se queda" : " vídeos o archivos se quedan")
                        + " fuera: el Echo solo enseña fotos." : ""))
                .setPositiveButton("Enviar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        start(a, photos);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Para llamar desde onRequestPermissionsResult de la pantalla (Android 9 o anterior). */
    static boolean onPermission(Activity a, int requestCode, int[] results) {
        if (requestCode != REQ_WRITE || pending == null) return false;
        List<BoxClient.RemoteFile> l = pending;
        pending = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start(a, l);
        else Toast.makeText(a, "Sin permiso no puedo guardar las fotos en el móvil", Toast.LENGTH_LONG).show();
        return true;
    }

    private static void start(final Activity a, final List<BoxClient.RemoteFile> photos) {
        if (Build.VERSION.SDK_INT < 29
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = photos;
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        final AtomicBoolean cancel = new AtomicBoolean();
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Preparando para el Echo")
                .setMessage("Conectando con el box…")
                .setCancelable(false)
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        cancel.set(true);
                    }
                })
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Uri> uris = new ArrayList<Uri>();
                String error = null;
                try {
                    BoxClient bc = BoxClient.connect(a);
                    for (int i = 0; i < photos.size() && !cancel.get(); i++) {
                        final BoxClient.RemoteFile f = photos.get(i);
                        final String label = "Foto " + (i + 1) + " de " + photos.size() + "\n" + f.name;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                progress.setMessage(label);
                            }
                        });
                        try {
                            Downloader.Saved s = Downloader.save(a, bc, f, Downloader.ECHO_FOLDER, null, cancel);
                            if (s.uri != null) uris.add(s.uri);
                        } catch (BoxClient.BoxException e) {
                            if (e.code == 401 || e.code == 503) throw e;
                            error = e.getMessage(); // esa foto ya no está: sigue con las demás
                        }
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = String.valueOf(e.getMessage());
                }
                final String err = error;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        try {
                            progress.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        if (cancel.get()) return;
                        if (uris.isEmpty()) {
                            Toast.makeText(a, "No se pudieron preparar las fotos" + (err == null ? "" : ": " + err),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (err != null) {
                            Toast.makeText(a, "Solo se prepararon " + uris.size() + " de " + photos.size() + ": " + err,
                                    Toast.LENGTH_LONG).show();
                        }
                        share(a, uris);
                    }
                });
            }
        }, "subelo-echo").start();
    }

    /**
     * Abre Amazon Photos con las fotos. Busca entre las apps que reciben fotos compartidas la de
     * Amazon Photos (o, si cambia de nombre, otra de Amazon con «Photos»); si no está, explica qué
     * ve y abre el menú normal de compartir.
     */
    static void share(final Activity a, ArrayList<Uri> uris) {
        final Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType("image/*");
        ClipData cd = null;
        for (Uri u : uris) {
            if (cd == null) cd = ClipData.newRawUri("fotos", u);
            else cd.addItem(new ClipData.Item(u));
        }
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PackageManager pm = a.getPackageManager();
        List<ResolveInfo> targets = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
        ResolveInfo best = null;
        StringBuilder amazon = new StringBuilder();
        for (ResolveInfo r : targets) {
            String pkg = r.activityInfo.packageName;
            String label = String.valueOf(r.loadLabel(pm));
            String l = (pkg + " " + label).toLowerCase(Locale.ROOT);
            if (!l.contains("amazon")) continue;
            if (amazon.length() > 0) amazon.append(", ");
            amazon.append(label);
            if (pkg.equals(AMAZON_PHOTOS)) best = r;
            else if (best == null && (l.contains("photo") || l.contains("foto"))) best = r;
        }
        if (best != null) {
            try {
                a.startActivity(new Intent(i).setClassName(best.activityInfo.packageName, best.activityInfo.name));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // no se dejó abrir: se explica abajo
            }
        }
        boolean singleOnly = false; // ¿la acepta de una en una pero no varias a la vez?
        if (best == null && uris.size() > 1) {
            Intent one = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0)).setType("image/*");
            for (ResolveInfo r : pm.queryIntentActivities(one, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (r.activityInfo.packageName.equals(AMAZON_PHOTOS)) singleOnly = true;
            }
        }
        boolean installed;
        try {
            pm.getPackageInfo(AMAZON_PHOTOS, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        String msg = singleOnly
                ? "Amazon Photos solo acepta las fotos de una en una: elige una sola foto y vuelve a enviarla."
                : installed
                ? "Amazon Photos está instalada, pero no aparece entre las apps que reciben fotos compartidas."
                : "No veo la app Amazon Photos en este móvil.";
        msg += "\n\nApps de Amazon que reciben fotos: " + (amazon.length() > 0 ? amazon : "ninguna") + "."
                + "\n\nPulsa «Ver apps» y busca Amazon Photos en la lista. Las fotos ya están en la galería, "
                + "en Subelo › Echo, por si prefieres subirlas desde Amazon Photos.";
        new AlertDialog.Builder(a)
                .setTitle("No encuentro Amazon Photos")
                .setMessage(msg)
                .setPositiveButton("Ver apps", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            a.startActivity(Intent.createChooser(i, "Enviar al Echo con…"));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(a, "No hay ninguna app para compartir fotos", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }
}
