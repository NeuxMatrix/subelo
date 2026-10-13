package es.subelo.box;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Puente con Amazon Photos para el Echo: lo que haya en la carpeta «Echo» de cada usuario se sube
 * a su Amazon Photos y se añade al álbum que eligió (el que enseña el Echo). Si quita una foto de
 * la carpeta, se quita del álbum (en Amazon Photos sigue estando).
 *
 * La sesión de Amazon (cookies de amazon.es) se guarda en la memoria interna del box, nunca en el
 * disco USB, y la API no la devuelve nunca.
 */
final class EchoSync {
    private static final String TAG = "SubeloEcho";
    /** Carpeta del usuario en el disco cuyo contenido va al álbum. */
    static final String FOLDER = "Echo";
    /** Carpeta de Amazon Drive donde se dejan las fotos subidas. */
    private static final String AMAZON_FOLDER = "Súbelo Echo";
    private static final long EVERY_MS = 5 * 60 * 1000L;

    private static final class Status {
        volatile boolean running;
        volatile long lastRun, lastOk;
        volatile String lastError;
        volatile int total, inAlbum, pending;
    }

    /** Foto ya subida: nombre, tamaño, identificador en Amazon y en qué álbum está. */
    private static final class Entry {
        String name, node, album;
        long size;
    }

    private static final Map<String, Status> STATUS = new HashMap<String, Status>();
    private static final Set<String> KICKED = new HashSet<String>();
    private static final Object WAKE = new Object();
    private static Thread worker;

    private EchoSync() {}

    // ---------- ajustes por usuario ----------

    private static File configFile(Context c, String user) {
        return new File(c.getFilesDir(), "amazon-" + user + ".properties");
    }

    private static File stateFile(Context c, String user) {
        return new File(c.getFilesDir(), "echo-" + user + ".txt");
    }

    static synchronized Properties config(Context c, String user) {
        Properties p = new Properties();
        File f = configFile(c, user);
        if (f.isFile()) {
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    private static synchronized void save(Context c, String user, Properties p) throws IOException {
        File f = configFile(c, user), tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            p.store(out, null);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar la conexión con Amazon");
    }

    static boolean connected(Context c, String user) {
        return config(c, user).getProperty("cookie") != null;
    }

    /** Guarda la sesión de Amazon tras comprobar que funciona. */
    static void connect(Context c, String user, String cookie, String tld) throws IOException {
        if (cookie == null || Amazon.cookieValue(cookie, "at-acb" + tld) == null && Amazon.cookieValue(cookie, "at-main") == null) {
            throw new IOException("Falta la sesión de Amazon: entra otra vez desde el móvil");
        }
        Amazon a = new Amazon(cookie, tld);
        String root = a.rootId();
        Properties p = config(c, user);
        p.setProperty("cookie", cookie);
        p.setProperty("tld", tld);
        p.setProperty("root", root);
        p.remove("expired");
        save(c, user, p);
        status(user).lastError = null;
        kick(c, user);
        Backup.kick(c, user); // las copias que esperaban a que se volviera a entrar
    }

    static void disconnect(Context c, String user) {
        synchronized (EchoSync.class) {
            configFile(c, user).delete();
            stateFile(c, user).delete();
        }
        Status s = status(user);
        s.lastError = null;
        s.inAlbum = 0;
        s.pending = 0;
    }

    static List<Amazon.Album> albums(Context c, String user) throws IOException {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
        try {
            return amazon(p).albums();
        } catch (Amazon.AmazonException e) {
            if (e.code == 401) expired(c, user, e.getMessage());
            throw e;
        }
    }

    static Amazon.Album albumFromLink(Context c, String user, String link) throws IOException {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
        return amazon(p).albumFromLink(link);
    }

    static void setAlbum(Context c, String user, String id, String name) throws IOException {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
        p.setProperty("album", id);
        p.setProperty("albumName", name == null || name.isEmpty() ? "Álbum" : name);
        save(c, user, p);
        kick(c, user);
    }

    static JSONObject statusJson(Context c, String user) throws JSONException {
        Properties p = config(c, user);
        Status s = status(user);
        JSONObject o = new JSONObject();
        o.put("connected", p.getProperty("cookie") != null);
        o.put("expired", p.getProperty("expired") != null);
        o.put("folder", FOLDER);
        if (p.getProperty("album") != null) {
            JSONObject a = new JSONObject();
            a.put("id", p.getProperty("album"));
            a.put("name", p.getProperty("albumName", "Álbum"));
            o.put("album", a);
        } else {
            o.put("album", JSONObject.NULL);
        }
        int in = 0;
        String album = p.getProperty("album");
        if (album != null) for (Entry e : load(c, user).values()) if (album.equals(e.album)) in++;
        o.put("running", s.running);
        o.put("total", s.lastRun == 0 ? -1 : s.total);
        o.put("inAlbum", in);
        o.put("pending", s.pending);
        o.put("lastRun", s.lastRun);
        o.put("lastOk", s.lastOk);
        o.put("error", s.lastError == null ? JSONObject.NULL : s.lastError);
        return o;
    }

    private static Amazon amazon(Properties p) {
        return new Amazon(p.getProperty("cookie"), p.getProperty("tld", "es"));
    }

    private static Status status(String user) {
        synchronized (STATUS) {
            Status s = STATUS.get(user);
            if (s == null) {
                s = new Status();
                STATUS.put(user, s);
            }
            return s;
        }
    }

    /** La sesión de Amazon ya no vale (lo usa también la copia de seguridad). */
    static void markExpired(Context c, String user, String msg) {
        expired(c, user, msg);
    }

    private static void expired(Context c, String user, String msg) {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) return;
        p.setProperty("expired", "1");
        try {
            save(c, user, p);
        } catch (IOException ignored) {
        }
        status(user).lastError = msg;
    }

    // ---------- trabajo en segundo plano ----------

    /** Arranca el repaso periódico (cada 5 minutos y cuando cambia algo). */
    static synchronized void start(final Context c) {
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Set<String> now;
                    synchronized (WAKE) {
                        if (KICKED.isEmpty()) {
                            try {
                                WAKE.wait(EVERY_MS);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        now = new HashSet<String>(KICKED);
                        KICKED.clear();
                    }
                    for (String user : Users.NAMES) {
                        if (!now.isEmpty() && !now.contains(user)) continue; // avisado: solo ese usuario
                        try {
                            sync(c, user);
                        } catch (Throwable t) {
                            Log.w(TAG, "sync " + user, t);
                            status(user).lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                        }
                    }
                }
            }
        }, "subelo-echo");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.setDaemon(true);
        worker.start();
    }

    /** Algo ha cambiado para este usuario: repasa su carpeta cuanto antes. */
    static void kick(Context c, String user) {
        if (user == null) return;
        start(c);
        synchronized (WAKE) {
            KICKED.add(user);
            WAKE.notifyAll();
        }
    }

    private static void sync(Context c, String user) throws IOException {
        Properties p = config(c, user);
        String album = p.getProperty("album");
        if (p.getProperty("cookie") == null || album == null || p.getProperty("expired") != null) return;
        Store st = Config.store(c);
        if (st == null || !st.available()) return;
        final Status s = status(user);
        s.running = true;
        s.lastRun = System.currentTimeMillis();
        try {
            final Object dir = st.dir(user + "/" + FOLDER, true);
            if (dir == null) return;
            List<Store.FileInfo> photos = new ArrayList<Store.FileInfo>();
            for (Store.FileInfo f : st.listFiles(dir)) {
                if (!f.name.startsWith(".") && Thumbs.isImage(f.name) && f.name.indexOf('\t') < 0 && f.name.indexOf('\n') < 0) {
                    photos.add(f);
                }
            }
            Map<String, Entry> state = load(c, user);
            Amazon a = amazon(p);
            List<String> toAdd = new ArrayList<String>(), toRemove = new ArrayList<String>();
            Set<String> present = new HashSet<String>();
            int pending = 0;
            for (Store.FileInfo f : photos) {
                Entry e = state.get(f.name);
                if (e == null || e.size != f.size || !album.equals(e.album)) pending++;
            }
            s.total = photos.size();
            s.pending = pending;
            String folder = p.getProperty("folder");
            try {
                for (final Store.FileInfo f : photos) {
                    present.add(f.name);
                    Entry e = state.get(f.name);
                    if (e != null && e.size == f.size) {
                        if (!album.equals(e.album)) toAdd.add(e.node); // ya subida; falta en este álbum
                        continue;
                    }
                    if (e != null && album.equals(e.album)) toRemove.add(e.node); // la foto cambió
                    if (folder == null) {
                        folder = a.folder(p.getProperty("root", a.rootId()), AMAZON_FOLDER);
                        p.setProperty("folder", folder);
                        save(c, user, p);
                    }
                    String node = a.upload(folder, f.name, new Amazon.Opener() {
                        @Override
                        public InputStream open() throws IOException {
                            final Store.Readable rd = st(c).read(dir, f.name);
                            if (rd == null) throw new IOException("Ya no está " + f.name);
                            final InputStream in = rd.open(0);
                            return new java.io.FilterInputStream(in) {
                                @Override
                                public void close() throws IOException {
                                    try {
                                        super.close();
                                    } finally {
                                        rd.close();
                                    }
                                }
                            };
                        }
                    }, f.size);
                    Entry n = new Entry();
                    n.name = f.name;
                    n.size = f.size;
                    n.node = node;
                    n.album = null;
                    state.put(f.name, n);
                    toAdd.add(node);
                    if (toAdd.size() >= 10) flush(c, user, a, album, state, toAdd, toRemove, s);
                }
                for (Entry e : new ArrayList<Entry>(state.values())) {
                    if (present.contains(e.name)) continue;
                    if (album.equals(e.album)) toRemove.add(e.node);
                    state.remove(e.name);
                }
                flush(c, user, a, album, state, toAdd, toRemove, s);
                s.lastError = null;
                s.lastOk = System.currentTimeMillis();
            } catch (Amazon.AmazonException e) {
                save(c, user, state);
                if (e.code == 401) expired(c, user, e.getMessage());
                else s.lastError = e.getMessage();
            } catch (IOException e) {
                save(c, user, state);
                s.lastError = e.getMessage();
            }
            int in = 0;
            for (Entry e : state.values()) if (album.equals(e.album)) in++;
            s.inAlbum = in;
        } finally {
            s.running = false;
        }
    }

    private static Store st(Context c) throws IOException {
        Store st = Config.store(c);
        if (st == null || !st.available()) throw new IOException("El disco no está disponible");
        return st;
    }

    /** Añade al álbum lo subido, quita lo que ya no está y apunta el progreso. */
    private static void flush(Context c, String user, Amazon a, String album, Map<String, Entry> state,
                              List<String> toAdd, List<String> toRemove, Status s) throws IOException {
        if (!toRemove.isEmpty()) {
            a.removeFromAlbum(album, toRemove);
            toRemove.clear();
        }
        if (!toAdd.isEmpty()) {
            a.addToAlbum(album, toAdd);
            Set<String> added = new HashSet<String>(toAdd);
            for (Entry e : state.values()) if (added.contains(e.node)) e.album = album;
            s.pending = Math.max(0, s.pending - toAdd.size());
            toAdd.clear();
        }
        save(c, user, state);
    }

    // ---------- lo ya subido ----------

    private static Map<String, Entry> load(Context c, String user) {
        Map<String, Entry> m = new LinkedHashMap<String, Entry>();
        File f = stateFile(c, user);
        if (!f.isFile()) return m;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] x = line.split("\t", -1);
                    if (x.length != 4) continue;
                    Entry e = new Entry();
                    e.name = x[0];
                    e.size = Long.parseLong(x[1]);
                    e.node = x[2];
                    e.album = x[3].isEmpty() ? null : x[3];
                    m.put(e.name, e);
                }
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            m.clear();
        }
        return m;
    }

    private static synchronized void save(Context c, String user, Map<String, Entry> state) throws IOException {
        File f = stateFile(c, user), tmp = new File(f.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (Entry e : state.values()) {
                w.write(e.name + "\t" + e.size + "\t" + e.node + "\t" + (e.album == null ? "" : e.album) + "\n");
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar lo enviado al Echo");
    }

    /** Lista de álbumes para la app. */
    static JSONArray albumsJson(List<Amazon.Album> list) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Amazon.Album al : list) {
            JSONObject o = new JSONObject();
            o.put("id", al.id);
            o.put("name", al.name);
            o.put("count", al.count);
            arr.put(o);
        }
        return arr;
    }
}
