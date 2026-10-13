package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Enviar al Echo»: copia las fotos elegidas a la carpeta «Echo» del disco. El box sube esa carpeta
 * a Amazon Photos y la mete en el álbum que enseña el Echo (ver AmazonActivity).
 */
final class Echo {
    private Echo() {}

    /** Pregunta y, si dice que sí, copia las fotos de la lista (los vídeos se saltan). */
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
                .setMessage("Copio " + what + " a tu carpeta «Echo» y el box las añade al álbum de Amazon Photos que enseña tu Echo."
                        + (skipped > 0 ? "\n\n" + skipped + (skipped == 1 ? " vídeo o archivo se queda" : " vídeos o archivos se quedan")
                        + " fuera: el Echo solo enseña fotos." : ""))
                .setPositiveButton("Enviar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copy(a, photos);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void copy(final Activity a, final List<BoxClient.RemoteFile> photos) {
        final AlertDialog progress = new AlertDialog.Builder(a)
                .setTitle("Enviar al Echo")
                .setMessage("Copiando " + (photos.size() == 1 ? "la foto" : photos.size() + " fotos") + "…")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // por carpetas: normalmente es una sola
                Map<String, List<String>> byDir = new LinkedHashMap<String, List<String>>();
                for (BoxClient.RemoteFile f : photos) {
                    List<String> l = byDir.get(f.dir);
                    if (l == null) byDir.put(f.dir, l = new ArrayList<String>());
                    l.add(f.name);
                }
                JSONObject status = null;
                int copied = 0, already = 0;
                String error = null;
                try {
                    BoxClient bc = BoxClient.connect(a);
                    for (Map.Entry<String, List<String>> e : byDir.entrySet()) {
                        status = bc.echo(e.getKey(), e.getValue());
                        copied += status.optInt("copied");
                        already += status.optInt("already");
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                }
                final JSONObject st = status;
                final int c = copied, al = already;
                final String err = error;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        try {
                            progress.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        done(a, st, c, al, err);
                    }
                });
            }
        }, "subelo-echo").start();
    }

    private static void done(final Activity a, JSONObject st, int copied, int already, String err) {
        if (st == null) {
            new AlertDialog.Builder(a).setMessage("✖ No se pudieron enviar: " + err).setPositiveButton("Vale", null).show();
            return;
        }
        String msg = copied + already == 0 ? "No se ha copiado ninguna foto."
                : (copied > 0 ? (copied == 1 ? "1 foto copiada" : copied + " fotos copiadas") + " a la carpeta «Echo»." : "")
                + (already > 0 ? (copied > 0 ? " " : "") + (already == 1 ? "1 ya estaba" : already + " ya estaban") + " en la carpeta." : "");
        if (err != null) msg += "\n\n✖ Algunas no se pudieron copiar: " + err;
        boolean ready = st.optBoolean("connected") && !st.optBoolean("expired") && !st.isNull("album");
        if (ready) {
            JSONObject album = st.optJSONObject("album");
            Toast.makeText(a, msg + " Irán al álbum «" + (album == null ? "" : album.optString("name")) + "» en un momento.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        msg += "\n\n" + (!st.optBoolean("connected") ? "Falta conectar Súbelo con tu Amazon Photos"
                : st.optBoolean("expired") ? "La sesión de Amazon ha caducado: vuelve a entrar"
                : "Falta elegir el álbum del Echo")
                + " para que lleguen al Echo. Se quedan esperando en la carpeta.";
        new AlertDialog.Builder(a)
                .setTitle("Enviar al Echo")
                .setMessage(msg)
                .setPositiveButton("Conectar ahora", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        a.startActivity(new Intent(a, AmazonActivity.class));
                    }
                })
                .setNegativeButton("Luego", null)
                .show();
    }
}
