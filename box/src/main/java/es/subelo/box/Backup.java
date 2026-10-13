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
 * Copia de seguridad en Amazon Fotos: las carpetas que cada usuario marca («☁ Hacer backup en
 * Amazon») se suben enteras, con sus subcarpetas, a «Súbelo Backup/<usuario>/…» de su Amazon
 * (la misma sesión que «Fotos al Echo»). Nunca se meten en un álbum, así que el Echo no las enseña.
 *
 * Es una copia, no un espejo: si se borra algo del disco, en Amazon se queda (por si fue sin querer
 * o se ha roto el disco). Si una foto cambia, se sube otra vez con otro nombre.
 */
final class Backup {
    private static final String TAG = "SubeloBackup";
    /** Carpeta de Amazon donde va todo; dentro, una por usuario. */
    static final String AMAZON_FOLDER = "Súbelo Backup";
    private static final long EVERY_MS = 30 * 60 * 1000L;
    private static final long KICK_DELAY_MS = 20 * 1000L;

    private static final class Status {
        volatile boolean running;
        volatile long lastRun, lastOk;
        volatile String lastError, current;
        /** Por carpeta marcada: {archivos, subidos, bytes, bytes subidos, ¿existe? (1/0)}. */
        final Map<String, long[]> counts = new HashMap<String, long[]>();
    }

    /** Archivo ya subido: tamaño e identificador en Amazon. */
    private static final class Entry {
        long size;
        String node;
    }

    /** Lo subido de un usuario: archivos (ruta relativa a su carpeta) y carpetas de Amazon ya creadas. */
    private static final class State {
        final Map<String, Entry> files = new LinkedHashMap<String, Entry>();
        final Map<String, String> dirs = new HashMap<String, String>();
    }

    private static final Map<String, Status> STATUS = new HashMap<String, Status>();
    private static final Set<String> KICKED = new HashSet<String>();
    private static final Object WAKE = new Object();
    private static Thread worker;

    private Backup() {}

    // ---------- carpetas marcadas ----------

    private static File listFile(Context c, String user) {
        return new File(c.getFilesDir(), "backup-" + user + ".txt");
    }

    private static File stateFile(Context c, String user) {
        return new File(c.getFilesDir(), "backup-" + user + "-subido.txt");
    }

    /** Las carpetas marcadas (relativas a la del usuario), en el orden en que se marcaron. */
    static synchronized List<String> folders(Context c, String user) {
        List<String> out = new ArrayList<String>();
        File f = listFile(c, user);
        if (!f.isFile()) return out;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = in.readLine()) != null) if (!l.isEmpty() && !out.contains(l)) out.add(l);
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static synchronized void saveFolders(Context c, String user, List<String> list) throws IOException {
        File f = listFile(c, user), tmp = new File(f.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (String s : list) w.write(s + "\n");
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar la lista de copias");
    }

    /** «on» si la carpeta está marcada, «inside» si lo está una que la contiene, null si no. */
    static String of(Context c, String user, String rel) {
        if (rel.isEmpty()) return null;
        for (String f : folders(c, user)) {
            if (rel.equals(f)) return "on";
            if (rel.startsWith(f + "/")) return "inside";
        }
        return null;
    }

    static void add(Context c, String user, String rel) throws IOException {
        if (!EchoSync.connected(c, user)) throw new IOException("Primero entra en Amazon desde la app del móvil");
        List<String> list = folders(c, user);
        if (of(c, user, rel) == null) {
            // si ya había subcarpetas suyas marcadas, ahora van dentro de esta
            for (String f : new ArrayList<String>(list)) if (f.startsWith(rel + "/")) list.remove(f);
            list.add(rel);
            saveFolders(c, user, list);
        }
        kick(c, user);
    }

    static void remove(Context c, String user, String rel) throws IOException {
        List<String> list = folders(c, user);
        if (list.remove(rel)) saveFolders(c, user, list);
        synchronized (STATUS) {
            status(user).counts.remove(rel);
        }
    }

    /** Una carpeta ha cambiado de nombre o de sitio: la marca la sigue. */
    static void moved(Context c, String user, String from, String to) {
        List<String> list = folders(c, user);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            String f = list.get(i);
            if (f.equals(from)) {
                list.set(i, to);
                changed = true;
            } else if (f.startsWith(from + "/")) {
                list.set(i, to + f.substring(from.length()));
                changed = true;
            }
        }
        if (!changed) return;
        try {
            saveFolders(c, user, list);
        } catch (IOException ignored) {
        }
        kick(c, user);
    }

    /** Se ha borrado del disco: deja de estar marcada (lo que ya está en Amazon se queda). */
    static void deleted(Context c, String user, String rel) {
        List<String> list = folders(c, user);
        boolean changed = false;
        for (String f : new ArrayList<String>(list)) {
            if (f.equals(rel) || f.startsWith(rel + "/")) {
                list.remove(f);
                changed = true;
            }
        }
        if (!changed) return;
        try {
            saveFolders(c, user, list);
        } catch (IOException ignored) {
        }
    }

    static JSONObject statusJson(Context c, String user) throws JSONException {
        Properties p = EchoSync.config(c, user);
        Status s = status(user);
        JSONObject o = new JSONObject();
        o.put("connected", p.getProperty("cookie") != null);
        o.put("expired", p.getProperty("expired") != null);
        o.put("tld", p.getProperty("tld", "es"));
        o.put("amazonFolder", AMAZON_FOLDER + "/" + user);
        o.put("running", s.running);
        o.put("current", s.current == null ? JSONObject.NULL : s.current);
        o.put("lastRun", s.lastRun);
        o.put("lastOk", s.lastOk);
        o.put("error", s.lastError == null ? JSONObject.NULL : s.lastError);
        JSONArray arr = new JSONArray();
        for (String f : folders(c, user)) {
            JSONObject x = new JSONObject();
            x.put("path", f);
            long[] n;
            synchronized (STATUS) {
                n = s.counts.get(f);
            }
            if (n != null) {
                x.put("files", n[0]);
                x.put("done", n[1]);
                x.put("bytes", n[2]);
                x.put("doneBytes", n[3]);
                x.put("missing", n[4] == 0);
            }
            arr.put(x);
        }
        o.put("folders", arr);
        return o;
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

    // ---------- trabajo en segundo plano ----------

    /** Arranca el repaso periódico (cada 30 minutos y poco después de cada cambio). */
    static synchronized void start(final Context c) {
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(KICK_DELAY_MS); // que el box acabe de arrancar
                } catch (InterruptedException e) {
                    return;
                }
                Set<String> now = new HashSet<String>();
                while (true) {
                    for (String user : Users.NAMES) {
                        if (!now.isEmpty() && !now.contains(user)) continue; // avisado: solo ese usuario
                        try {
                            sync(c, user);
                        } catch (Throwable t) {
                            Log.w(TAG, "sync " + user, t);
                            status(user).lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                        }
                    }
                    synchronized (WAKE) {
                        if (KICKED.isEmpty()) {
                            try {
                                WAKE.wait(EVERY_MS);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        boolean kicked = !KICKED.isEmpty();
                        now = new HashSet<String>(KICKED);
                        KICKED.clear();
                        if (!kicked) continue;
                    }
                    try {
                        Thread.sleep(KICK_DELAY_MS); // juntar los avisos de una subida de muchas fotos
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (WAKE) {
                        now.addAll(KICKED);
                        KICKED.clear();
                    }
                }
            }
        }, "subelo-backup");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.setDaemon(true);
        worker.start();
    }

    /** Algo ha cambiado para este usuario: repasa sus carpetas pronto. */
    static void kick(Context c, String user) {
        if (user == null) return;
        start(c);
        synchronized (WAKE) {
            KICKED.add(user);
            WAKE.notifyAll();
        }
    }

    /** Un archivo para subir: carpeta del disco y ruta relativa a la del usuario. */
    private static final class Item {
        Object dir;
        String dirRel, name;
        long size;

        String key() {
            return dirRel + "/" + name;
        }
    }

    private static void sync(Context c, String user) throws IOException {
        List<String> folders = folders(c, user);
        if (folders.isEmpty()) return;
        Properties p = EchoSync.config(c, user);
        final Status s = status(user);
        if (p.getProperty("cookie") == null) {
            s.lastError = "Súbelo no está conectado con Amazon: entra desde la app del móvil";
            return;
        }
        if (p.getProperty("expired") != null) {
            s.lastError = "La sesión de Amazon ha caducado: vuelve a entrar desde el móvil";
            return;
        }
        final Store st = Config.store(c);
        if (st == null || !st.available()) return; // sin disco no se toca nada
        s.running = true;
        s.lastRun = System.currentTimeMillis();
        State state = load(c, user);
        try {
            // primero se cuenta todo, para que la app enseñe cuánto falta
            Map<String, List<Item>> todo = new LinkedHashMap<String, List<Item>>();
            for (String f : folders) {
                List<Item> items = new ArrayList<Item>();
                Object d = st.dir(user + "/" + f, false);
                long[] n = new long[5];
                if (d != null) {
                    walk(st, user, d, f, items, 0);
                    n[4] = 1;
                }
                for (Item it : items) {
                    Entry e = state.files.get(it.key());
                    n[0]++;
                    n[2] += it.size;
                    if (e != null && e.size == it.size) {
                        n[1]++;
                        n[3] += it.size;
                    }
                }
                synchronized (STATUS) {
                    s.counts.put(f, n);
                }
                todo.put(f, items);
            }
            Amazon a = new Amazon(p.getProperty("cookie"), p.getProperty("tld", "es"));
            int sinceSave = 0, failsInRow = 0;
            String lastFileError = null;
            for (Map.Entry<String, List<Item>> fe : todo.entrySet()) {
                long[] n;
                synchronized (STATUS) {
                    n = s.counts.get(fe.getKey());
                }
                for (final Item it : fe.getValue()) {
                    Entry e = state.files.get(it.key());
                    if (e != null && e.size == it.size) continue;
                    if (!folders(c, user).contains(fe.getKey())) break; // la han desmarcado mientras tanto
                    s.current = it.key();
                    try {
                        String parent = amazonDir(a, p, state, user, it.dirRel);
                        String node = a.upload(parent, it.name, new Amazon.Opener() {
                            @Override
                            public InputStream open() throws IOException {
                                final Store.Readable rd = st.read(it.dir, it.name);
                                if (rd == null) throw new IOException("Ya no está " + it.name);
                                return new java.io.FilterInputStream(rd.open(0)) {
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
                        }, it.size);
                        Entry ne = new Entry();
                        ne.size = it.size;
                        ne.node = node;
                        state.files.put(it.key(), ne);
                        if (n != null) {
                            n[1]++;
                            n[3] += it.size;
                        }
                        failsInRow = 0;
                        if (++sinceSave >= 10) {
                            save(c, user, state);
                            sinceSave = 0;
                        }
                    } catch (Amazon.AmazonException ex) {
                        if (ex.code == 401) throw ex;
                        lastFileError = it.name + ": " + ex.getMessage();
                        if (++failsInRow >= 3) throw ex;
                    } catch (IOException ex) {
                        lastFileError = it.name + ": " + ex.getMessage();
                        if (++failsInRow >= 3) throw ex; // sin Internet o sin disco: ya se intentará luego
                    }
                }
            }
            save(c, user, state);
            s.lastError = lastFileError;
            s.lastOk = System.currentTimeMillis();
        } catch (Amazon.AmazonException e) {
            save(c, user, state);
            if (e.code == 401) {
                EchoSync.markExpired(c, user, e.getMessage());
            }
            s.lastError = e.getMessage();
        } catch (IOException e) {
            save(c, user, state);
            s.lastError = e.getMessage();
        } finally {
            s.current = null;
            s.running = false;
        }
    }

    /**
     * Todos los archivos de una carpeta y sus subcarpetas (sin los ocultos ni los a medio subir).
     * rel es la ruta dentro de la carpeta del usuario; en el disco va delante «usuario/».
     */
    private static void walk(Store st, String user, Object dir, String rel, List<Item> out, int depth) throws IOException {
        if (depth > 30) return;
        for (Store.FileInfo f : st.listFiles(dir)) {
            if (Store.hidden(f.name) || f.name.indexOf('\t') >= 0 || f.name.indexOf('\n') >= 0) continue;
            Item it = new Item();
            it.dir = dir;
            it.dirRel = rel;
            it.name = f.name;
            it.size = f.size;
            out.add(it);
        }
        for (String sub : st.listDirs(dir)) {
            if (Store.hidden(sub) || sub.indexOf('\t') >= 0 || sub.indexOf('\n') >= 0) continue;
            Object d = st.dir(user + "/" + rel + "/" + sub, false);
            if (d != null) walk(st, user, d, rel + "/" + sub, out, depth + 1);
        }
    }

    /** La carpeta de Amazon para una carpeta del disco (las crea si faltan). */
    private static String amazonDir(Amazon a, Properties p, State state, String user, String rel) throws IOException {
        String id = state.dirs.get(rel);
        if (id != null) return id;
        String parent;
        String name;
        int slash = rel.lastIndexOf('/');
        if (slash < 0) {
            String top = state.dirs.get("");
            if (top == null) {
                String root = p.getProperty("root");
                if (root == null) root = a.rootId();
                String backup = a.folder(root, AMAZON_FOLDER);
                top = a.folder(backup, user);
                state.dirs.put("", top);
            }
            parent = top;
            name = rel;
        } else {
            parent = amazonDir(a, p, state, user, rel.substring(0, slash));
            name = rel.substring(slash + 1);
        }
        id = a.folder(parent, name);
        state.dirs.put(rel, id);
        return id;
    }

    // ---------- lo ya subido ----------

    private static State load(Context c, String user) {
        State s = new State();
        File f = stateFile(c, user);
        if (!f.isFile()) return s;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] x = line.split("\t", -1);
                    if (x.length == 4 && x[0].equals("F")) {
                        Entry e = new Entry();
                        e.size = Long.parseLong(x[2]);
                        e.node = x[3];
                        s.files.put(x[1], e);
                    } else if (x.length == 3 && x[0].equals("D")) {
                        s.dirs.put(x[1], x[2]);
                    }
                }
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            return new State();
        }
        return s;
    }

    private static synchronized void save(Context c, String user, State s) throws IOException {
        File f = stateFile(c, user), tmp = new File(f.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (Map.Entry<String, String> d : s.dirs.entrySet()) w.write("D\t" + d.getKey() + "\t" + d.getValue() + "\n");
            for (Map.Entry<String, Entry> e : s.files.entrySet()) {
                w.write("F\t" + e.getKey() + "\t" + e.getValue().size + "\t" + e.getValue().node + "\n");
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar lo subido a Amazon");
    }
}
