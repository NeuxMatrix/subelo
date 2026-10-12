package es.subelo.movil;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones automáticas desde GitHub Releases.
 * Cada release se llama «v<versionCode>» y trae los dos APK. Si hay una más nueva que la instalada,
 * se descarga y se instala con el instalador de Android (que pide confirmación una vez).
 * Android solo acepta la actualización si está firmada con la misma clave que la app instalada.
 */
final class Updater {
    static final String REPO = "NeuxMatrix/subelo";
    static final String ASSET = "Subelo-movil.apk";
    private static final long CHECK_EVERY_MS = 10 * 60 * 1000L;

    static final class Release {
        int code;
        String name;
        String url;
        long size;
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    /** Mensaje del último intento de instalación que no salió bien (para mostrarlo en pantalla). */
    static volatile String lastError;

    private Updater() {}

    static int installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("actualizaciones", Context.MODE_PRIVATE);
    }

    /** Consulta GitHub ya (botón «Buscar actualizaciones») y guarda el resultado. */
    static Release checkNow(Context c) throws IOException {
        Release r = check(c);
        prefs(c).edit().putLong("last", System.currentTimeMillis()).putInt("code", r == null ? 0 : r.code)
                .putString("name", r == null ? null : r.name).putString("url", r == null ? null : r.url)
                .putLong("size", r == null ? -1 : r.size).apply();
        return r;
    }

    /** Como check(), pero como mucho una consulta a GitHub cada 10 minutos (si no, devuelve lo último visto). */
    static Release checkThrottled(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("last", 0) > CHECK_EVERY_MS) {
            try {
                Release r = check(c);
                p.edit().putLong("last", now).putInt("code", r == null ? 0 : r.code)
                        .putString("name", r == null ? null : r.name).putString("url", r == null ? null : r.url)
                        .putLong("size", r == null ? -1 : r.size).apply();
                return r;
            } catch (IOException e) {
                return null;
            }
        }
        if (p.getInt("code", 0) <= installedCode(c) || p.getString("url", null) == null) return null;
        Release r = new Release();
        r.code = p.getInt("code", 0);
        r.name = p.getString("name", "");
        r.url = p.getString("url", null);
        r.size = p.getLong("size", -1);
        return r;
    }

    /** Última release publicada, o null si no hay ninguna más nueva que la instalada. */
    static Release check(Context c) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "Subelo");
        h.setConnectTimeout(8000);
        h.setReadTimeout(15000);
        try {
            int code = h.getResponseCode();
            if (code == 404) return null; // aún no hay ninguna release
            if (code != 200) throw new IOException("GitHub respondió " + code);
            JSONObject o = new JSONObject(readAll(h.getInputStream()));
            String tag = o.optString("tag_name", "");
            int vcode;
            try {
                vcode = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return null;
            }
            if (vcode <= installedCode(c)) return null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && ASSET.equals(a.optString("name"))) {
                    Release r = new Release();
                    r.code = vcode;
                    r.name = o.optString("name", tag);
                    r.url = a.optString("browser_download_url");
                    r.size = a.optLong("size", -1);
                    return r;
                }
            }
            return null;
        } catch (org.json.JSONException e) {
            throw new IOException("Respuesta no válida de GitHub");
        } finally {
            h.disconnect();
        }
    }

    /** Descarga el APK y abre el instalador de Android. Llamar desde un hilo de fondo. */
    static void install(Context c, Release r, Progress progress) throws IOException {
        lastError = null;
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        if (r.size > 0) sp.setSize(r.size);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        boolean ok = false;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(r.url).openConnection();
            h.setRequestProperty("User-Agent", "Subelo");
            h.setConnectTimeout(10000);
            h.setReadTimeout(60000);
            h.setInstanceFollowRedirects(true);
            try {
                if (h.getResponseCode() != 200) throw new IOException("Descarga fallida (" + h.getResponseCode() + ")");
                long total = r.size > 0 ? r.size : h.getContentLength();
                InputStream in = h.getInputStream();
                OutputStream out = s.openWrite("subelo.apk", 0, total > 0 ? total : -1);
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
                s.fsync(out);
                out.close();
                in.close();
            } finally {
                h.disconnect();
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Result.class), flags);
            s.commit(done.getIntentSender());
            ok = true;
        } finally {
            if (!ok) s.abandon();
            s.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** Respuesta del instalador de Android. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                lastError = "No se pudo actualizar" + (msg != null ? ": " + msg : "");
                Toast.makeText(c, lastError, Toast.LENGTH_LONG).show();
            }
        }
    }
}
