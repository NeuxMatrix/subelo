package es.subelo.box;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Lo que ocupa cada usuario en el disco: su carpeta («Fer», «Julss») con todo lo de dentro. Lo que el
 * otro añade en una carpeta que le compartes está en tu carpeta, así que cuenta como tuyo.
 * Recorrer un disco grande tarda, así que se cuenta de fondo y se guarda lo último contado (también
 * para después de un reinicio). Se vuelve a contar si algo ha cambiado (subir, borrar…) y ha pasado
 * un minuto, o cada media hora.
 */
final class Usage {
    /** Lo de un usuario. */
    static final class Of {
        long bytes, files, photos, videos, photoBytes, videoBytes;
    }

    private static final long AGAIN_MS = 60 * 1000L, MAX_AGE_MS = 30 * 60 * 1000L, RETRY_MS = 15 * 1000L;
    private static final int MAX_DEPTH = 40;

    private static Map<String, Of> last; // usuario → lo último contado (null: nunca)
    private static long at, tried; // cuándo se terminó de contar y cuándo se empezó por última vez
    private static boolean counting, dirty, loaded;

    private Usage() {}

    /** Algo ha cambiado en el disco: la próxima vez que se mire, se vuelve a contar. */
    static synchronized void changed() {
        dirty = true;
    }

    /** Si toca, empieza a contar de fondo (no espera). */
    static void refresh(final Context c, final Store st) {
        synchronized (Usage.class) {
            load(c);
            long now = System.currentTimeMillis(), age = now - at;
            if (counting || st == null || !st.available() || now - tried < RETRY_MS) return;
            boolean stale = last == null || age < 0 || age > MAX_AGE_MS || (dirty && age > AGAIN_MS);
            if (!stale) return;
            counting = true;
            dirty = false;
            tried = now;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Map<String, Of> fresh = new LinkedHashMap<String, Of>();
                boolean ok = true;
                try {
                    for (String u : Users.NAMES) {
                        Of o = new Of();
                        walk(st, u, 0, o);
                        fresh.put(u, o);
                    }
                } catch (IOException e) {
                    ok = false; // disco desconectado a mitad: se queda lo de antes
                } catch (RuntimeException e) {
                    ok = false;
                }
                synchronized (Usage.class) {
                    counting = false;
                    if (ok) {
                        last = fresh;
                        at = System.currentTimeMillis();
                        save(c);
                    }
                }
            }
        }, "subelo-espacio");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void walk(Store st, String rel, int depth, Of o) throws IOException {
        Object d = st.dir(rel, false);
        if (d == null) return;
        for (Store.FileInfo f : st.listFiles(d)) {
            long size = Math.max(0, f.size);
            o.bytes += size;
            o.files++;
            if (Thumbs.isImage(f.name)) {
                o.photos++;
                o.photoBytes += size;
            } else if (Thumbs.isVideo(f.name)) {
                o.videos++;
                o.videoBytes += size;
            }
        }
        if (depth >= MAX_DEPTH) return;
        for (String sub : st.listDirs(d)) {
            if (!Store.hidden(sub)) walk(st, rel + "/" + sub, depth + 1, o);
        }
    }

    /**
     * Para la web y el móvil: {users: [{name, bytes, files, photos, videos, photoBytes, videoBytes}],
     * free, total, others (lo demás del disco), counting, at}. Sin users si aún no se ha contado nunca.
     */
    static JSONObject json(Context c, Store st) throws JSONException {
        refresh(c, st);
        JSONObject o = new JSONObject();
        JSONArray users = new JSONArray();
        long sum = 0;
        boolean known;
        synchronized (Usage.class) {
            known = last != null;
            if (known) {
                for (Map.Entry<String, Of> e : last.entrySet()) {
                    Of u = e.getValue();
                    JSONObject j = new JSONObject();
                    j.put("name", e.getKey());
                    j.put("bytes", u.bytes);
                    j.put("files", u.files);
                    j.put("photos", u.photos);
                    j.put("videos", u.videos);
                    j.put("photoBytes", u.photoBytes);
                    j.put("videoBytes", u.videoBytes);
                    users.put(j);
                    sum += u.bytes;
                }
            }
            o.put("counting", counting);
            o.put("at", at);
        }
        boolean available = st != null && st.available();
        long free = available ? st.free() : -1, total = available ? st.total() : -1;
        o.put("users", users);
        o.put("free", free);
        o.put("total", total);
        o.put("others", known && free >= 0 && total > 0 ? Math.max(0, total - free - sum) : -1);
        return o;
    }

    /** Para la tele: una línea por usuario, o que se está contando. */
    static String lines(Context c, Store st) {
        refresh(c, st);
        StringBuilder sb = new StringBuilder();
        synchronized (Usage.class) {
            if (last == null) return counting ? "Contando lo que ocupa cada uno…" : "";
            for (Map.Entry<String, Of> e : last.entrySet()) {
                Of u = e.getValue();
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append(": ").append(human(u.bytes)).append(" · ")
                        .append(count(u.photos, "foto", "fotos")).append(", ").append(count(u.videos, "vídeo", "vídeos"));
            }
        }
        return sb.toString();
    }

    static String human(long bytes) {
        if (bytes >= 1e12) return String.format(Locale.getDefault(), "%.2f TB", bytes / 1e12);
        if (bytes >= 1e9) return String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9);
        if (bytes >= 1e6) return String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6);
        return String.format(Locale.getDefault(), "%.0f KB", Math.max(bytes, bytes > 0 ? 1000 : 0) / 1e3);
    }

    private static String count(long n, String one, String many) {
        return String.format(Locale.getDefault(), "%,d", n) + " " + (n == 1 ? one : many);
    }

    // ---------- lo último contado, en filesDir/espacio.properties ----------

    private static File file(Context c) {
        return new File(c.getFilesDir(), "espacio.properties");
    }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = file(c);
        if (!f.isFile()) return;
        Properties p = new Properties();
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            Map<String, Of> m = new LinkedHashMap<String, Of>();
            for (String u : Users.NAMES) {
                String[] v = p.getProperty(u, "").split(",");
                if (v.length < 6) return;
                Of o = new Of();
                o.bytes = Long.parseLong(v[0]);
                o.files = Long.parseLong(v[1]);
                o.photos = Long.parseLong(v[2]);
                o.videos = Long.parseLong(v[3]);
                o.photoBytes = Long.parseLong(v[4]);
                o.videoBytes = Long.parseLong(v[5]);
                m.put(u, o);
            }
            at = Long.parseLong(p.getProperty("at", "0"));
            last = m;
        } catch (IOException e) {
            // se vuelve a contar
        } catch (NumberFormatException e) {
            // idem
        }
    }

    private static void save(Context c) {
        Properties p = new Properties();
        for (Map.Entry<String, Of> e : last.entrySet()) {
            Of o = e.getValue();
            p.setProperty(e.getKey(), o.bytes + "," + o.files + "," + o.photos + "," + o.videos + ","
                    + o.photoBytes + "," + o.videoBytes);
        }
        p.setProperty("at", String.valueOf(at));
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                p.store(out, null);
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException ignored) {
            // la próxima vez se cuenta otra vez
        }
    }
}
