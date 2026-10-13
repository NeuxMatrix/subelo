package es.subelo.box;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dónde se hizo cada foto, para el mapa. Recorre la carpeta del usuario en segundo plano, lee la
 * ubicación del EXIF (fotos) o de los metadatos (vídeos) y lo guarda en una caché por usuario:
 * la siguiente vez solo mira las fotos nuevas o cambiadas.
 */
final class Places {
    private static final long RESCAN_MS = 60 * 1000L;
    private static final int MAX_DEPTH = 12;
    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    /** Una entrada de la caché: carpeta, nombre, tamaño, fecha de modificación, lat, lon, día. */
    static final class Rec {
        String dir, name, date;
        long size, mtime;
        double lat = Double.NaN, lon = Double.NaN;
    }

    private static final class State {
        volatile boolean scanning;
        volatile int seen;
        long lastScan;
        Map<String, Rec> recs; // carpeta + "/" + nombre → registro
    }

    private static final Map<String, State> STATES = new HashMap<String, State>();

    private Places() {}

    /** El estado (caché) de un usuario; arranca un repaso de su carpeta si toca. */
    private static State state(final Context ctx, final Store st, final String user) {
        synchronized (STATES) {
            State x = STATES.get(user);
            if (x == null) {
                x = new State();
                x.recs = load(file(ctx, user));
                STATES.put(user, x);
            }
            final State s = x;
            if (!s.scanning && System.currentTimeMillis() - s.lastScan > RESCAN_MS) {
                s.scanning = true;
                s.seen = 0;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            scan(ctx, st, user, s);
                        } finally {
                            synchronized (STATES) {
                                s.lastScan = System.currentTimeMillis();
                                s.scanning = false;
                            }
                        }
                    }
                }, "subelo-mapa-" + user);
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
            return s;
        }
    }

    /**
     * JSON con las fotos con ubicación que se conocen ya; arranca un repaso si toca. Salen las tuyas,
     * las de las carpetas que el otro te comparte y las que el otro ha ubicado «con» contigo (📍).
     * Cada una: [carpeta para pedirla, nombre, lat, lon, día, tamaño, puesta a mano (1/0),
     * dueño («» si es tuya), compartida en el mapa (1/0)].
     */
    static String json(final Context ctx, final Store st, final String user) {
        State mine = state(ctx, st, user);
        Map<String, Geo.Entry> geo = Geo.all(ctx); // las puestas a mano mandan sobre el GPS
        List<Shares.Share> in = Shares.incoming(ctx, user);
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\"places\":[");
        boolean[] first = {true};
        boolean scanning = mine.scanning;
        int total, seen = mine.seen;
        synchronized (mine) {
            total = mine.recs.size();
            for (Rec r : mine.recs.values()) {
                String store = store(user, r);
                place(sb, first, r, r.dir, geo, store, "", !Geo.sharedWith(geo, store).isEmpty());
            }
        }
        for (String other : Users.NAMES) {
            if (other.equals(user) || !mayShare(geo, in, other, user)) continue;
            State s = state(ctx, st, other);
            scanning |= s.scanning;
            synchronized (s) {
                for (Rec r : s.recs.values()) {
                    String store = store(other, r), path = null;
                    for (Shares.Share sh : in) { // en una carpeta que te comparte: por ahí se pide
                        String base = sh.owner + "/" + sh.path + "/";
                        if (!store.startsWith(base)) continue;
                        String inner = store.substring(base.length()), sub = inner.substring(0, Math.max(0, inner.lastIndexOf('/')));
                        path = Shares.VIRTUAL + "/" + sh.name + (sub.isEmpty() ? "" : "/" + sub);
                        break;
                    }
                    if (path == null) {
                        if (!Geo.sharedWith(geo, store).contains(user)) continue;
                        path = Geo.VIRTUAL + "/" + other + (r.dir.isEmpty() ? "" : "/" + r.dir);
                    }
                    place(sb, first, r, path, geo, store, other, true);
                }
            }
        }
        sb.append("],\"media\":").append(total)
                .append(",\"scanning\":").append(scanning)
                .append(",\"seen\":").append(seen).append('}');
        return sb.toString();
    }

    private static String store(String owner, Rec r) {
        return owner + "/" + (r.dir.isEmpty() ? "" : r.dir + "/") + r.name;
    }

    /** ¿Puede haber algo de «owner» en el mapa de «user»? (para no repasar su carpeta si no). */
    private static boolean mayShare(Map<String, Geo.Entry> geo, List<Shares.Share> in, String owner, String user) {
        for (Shares.Share sh : in) if (sh.owner.equals(owner)) return true;
        for (Map.Entry<String, Geo.Entry> e : geo.entrySet()) {
            if (e.getKey().startsWith(owner + "/") && e.getValue().with.contains(user)) return true;
        }
        return false;
    }

    /** Añade una foto al JSON si se sabe dónde se hizo: la puesta a mano, el GPS o la de su carpeta. */
    private static void place(StringBuilder sb, boolean[] first, Rec r, String path, Map<String, Geo.Entry> geo,
                              String store, String owner, boolean shared) {
        Geo.Entry m = geo.get(store);
        double lat = r.lat, lon = r.lon;
        if (m != null) {
            lat = m.lat;
            lon = m.lon;
        } else if (Double.isNaN(lat)) {
            Geo.Entry f = Geo.folderOf(geo, store);
            if (f == null) return;
            lat = f.lat;
            lon = f.lon;
            m = f;
        }
        if (!first[0]) sb.append(',');
        first[0] = false;
        sb.append('[');
        str(sb, path);
        sb.append(',');
        str(sb, r.name);
        sb.append(',').append(String.format(Locale.US, "%.6f,%.6f", lat, lon)).append(',');
        str(sb, r.date);
        sb.append(',').append(r.size).append(',').append(m != null ? 1 : 0).append(',');
        str(sb, owner);
        sb.append(',').append(shared ? 1 : 0).append(']');
    }

    /** Ubicación del GPS de una foto si ya se ha leído (sin esperar a repasar el disco), o null. */
    static double[] known(Context ctx, String user, String rel, String name) {
        State s;
        synchronized (STATES) {
            s = STATES.get(user);
            if (s == null) {
                s = new State();
                s.recs = load(file(ctx, user));
                STATES.put(user, s);
            }
        }
        synchronized (s) {
            Rec r = s.recs.get(rel + "/" + name);
            return r == null || Double.isNaN(r.lat) ? null : new double[]{r.lat, r.lon};
        }
    }

    private static void scan(Context ctx, Store st, String user, State s) {
        Map<String, Rec> old;
        synchronized (s) {
            old = new HashMap<String, Rec>(s.recs);
        }
        Map<String, Rec> fresh = new LinkedHashMap<String, Rec>();
        int[] changed = {0};
        try {
            walk(ctx, st, user, "", 0, old, fresh, s, changed);
        } catch (IOException e) {
            return; // disco desconectado a mitad: se queda la caché anterior
        }
        synchronized (s) {
            s.recs = fresh;
        }
        if (changed[0] > 0 || fresh.size() != old.size()) save(ctx, user, s);
    }

    private static void walk(Context ctx, Store st, String user, String rel, int depth, Map<String, Rec> old,
                             Map<String, Rec> fresh, State s, int[] changed) throws IOException {
        Object d = st.dir(rel.isEmpty() ? user : user + "/" + rel, false);
        if (d == null) return;
        for (Store.FileInfo f : st.listFiles(d)) {
            if (!Thumbs.isImage(f.name) && !Thumbs.isVideo(f.name)) continue;
            if (f.name.indexOf('\t') >= 0 || f.name.indexOf('\n') >= 0) continue;
            String key = rel + "/" + f.name;
            Rec r = old.get(key);
            if (r == null || r.size != f.size || r.mtime != f.mtime) {
                r = locate(st, d, rel, f);
                if (++changed[0] % 100 == 0) {
                    synchronized (s) {
                        Map<String, Rec> m = new LinkedHashMap<String, Rec>(old);
                        m.putAll(fresh);
                        m.put(key, r);
                        s.recs = m;
                    }
                    save(ctx, user, s);
                }
            }
            fresh.put(key, r);
            s.seen++;
        }
        if (depth >= MAX_DEPTH) return;
        for (String sub : st.listDirs(d)) {
            if (Store.hidden(sub)) continue;
            walk(ctx, st, user, rel.isEmpty() ? sub : rel + "/" + sub, depth + 1, old, fresh, s, changed);
        }
    }

    private static Rec locate(Store st, Object d, String rel, Store.FileInfo f) {
        Rec r = new Rec();
        r.dir = rel;
        r.name = f.name;
        r.size = f.size;
        r.mtime = f.mtime;
        try {
            Store.Readable rd = st.read(d, f.name);
            if (rd != null) {
                try {
                    if (Thumbs.isImage(f.name)) {
                        InputStream in = rd.open(0);
                        try {
                            Exif.Info i = Exif.read(in);
                            r.lat = i.lat;
                            r.lon = i.lon;
                            r.date = i.date;
                        } finally {
                            in.close();
                        }
                    } else {
                        video(rd, r);
                    }
                } finally {
                    rd.close();
                }
            }
        } catch (Throwable ignored) {
            // fichero ilegible: sin ubicación
        }
        if (r.date == null && f.mtime > 0) r.date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(f.mtime));
        return r;
    }

    private static void video(Store.Readable rd, Rec r) throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(rd.fd());
            String loc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (loc != null) {
                Matcher m = ISO6709.matcher(loc);
                if (m.find()) {
                    double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
                    if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0)) {
                        r.lat = lat;
                        r.lon = lon;
                    }
                }
            }
            String date = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null && date.matches("(19|20)\\d{6}T.*")) {
                r.date = date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
            }
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- caché en el box ----------

    private static File file(Context ctx, String user) {
        return new File(ctx.getFilesDir(), "lugares-" + user + ".txt");
    }

    private static Map<String, Rec> load(File f) {
        Map<String, Rec> m = new LinkedHashMap<String, Rec>();
        if (!f.isFile()) return m;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 7) continue;
                    Rec r = new Rec();
                    r.dir = p[0];
                    r.name = p[1];
                    r.size = Long.parseLong(p[2]);
                    r.mtime = Long.parseLong(p[3]);
                    if (!p[4].isEmpty()) {
                        r.lat = Double.parseDouble(p[4]);
                        r.lon = Double.parseDouble(p[5]);
                    }
                    r.date = p[6].isEmpty() ? null : p[6];
                    m.put(r.dir + "/" + r.name, r);
                }
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            m.clear();
        }
        return m;
    }

    private static void save(Context ctx, String user, State s) {
        List<Rec> list;
        synchronized (s) {
            list = new java.util.ArrayList<Rec>(s.recs.values());
        }
        File f = file(ctx, user), tmp = new File(f.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                for (Rec r : list) {
                    w.write(r.dir + "\t" + r.name + "\t" + r.size + "\t" + r.mtime + "\t"
                            + (Double.isNaN(r.lat) ? "\t" : r.lat + "\t" + r.lon) + "\t"
                            + (r.date == null ? "" : r.date) + "\n");
                }
            } finally {
                w.close();
            }
            tmp.renameTo(f);
        } catch (IOException ignored) {
        }
    }

    private static void str(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
    }
}
