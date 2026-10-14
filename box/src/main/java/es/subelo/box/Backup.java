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
 * (la misma sesión que «Fotos al Echo»). Nunca se meten en un álbum y cada foto se oculta en
 * Amazon Fotos nada más subirla: así no sale en la línea de tiempo, ni en los recuerdos ni en el Echo
 * (los recuerdos y Alexa tiran de toda la biblioteca, no solo de los álbumes).
 *
 * Amazon Fotos no tiene perfiles: quien entra con una cuenta lo ve todo. Por eso, si Fer y Julss
 * tienen conectada la misma cuenta de Amazon, no se hace la copia (cada uno vería la del otro).
 *
 * Es una copia, no un espejo: si se borra algo del disco, en Amazon se queda (por si fue sin querer
 * o se ha roto el disco). Si una foto cambia, se sube otra vez con otro nombre. Lo que sí se borra
 * de Amazon es lo de una carpeta que se deja de copiar («Dejar de hacer copia», o borrarla desde
 * «☁ Copias en Amazon» en el móvil): va a la papelera de Amazon Fotos, que lo guarda 90 días.
 */
final class Backup {
    private static final String TAG = "SubeloBackup";
    /** Carpeta de Amazon donde va todo; dentro, una por usuario. */
    static final String AMAZON_FOLDER = "Súbelo Backup";
    private static final long EVERY_MS = 30 * 60 * 1000L;
    private static final long KICK_DELAY_MS = 20 * 1000L;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** Lo que falta borrar en una cuenta de Amazon a la que ya no se vuelve se deja de intentar al mes. */
    private static final long GIVE_UP_MS = 30 * DAY_MS;
    /** Cuándo se miró por última vez, por usuario, que siguen en Amazon todas las carpetas de la copia. */
    private static final Map<String, Long> FULL_CHECK = new HashMap<String, Long>();

    private static final class Status {
        volatile boolean running;
        volatile long lastRun, lastOk;
        volatile String lastError, current, hideError, removeError;
        /** Por carpeta marcada: {archivos, subidos, bytes, bytes subidos, ¿existe? (1/0)}. */
        final Map<String, long[]> counts = new HashMap<String, long[]>();
    }

    /** Archivo ya subido: tamaño, identificador en Amazon y si ya está oculto. */
    private static final class Entry {
        long size;
        String node;
        /**
         * «h» oculto; «n» no se oculta (no es foto ni vídeo; si lo es, es que hasta la 2.22 Amazon la
         * dejó en otra carpeta: se vuelve a subir, ver done()); «d» Amazon no ha querido subir otra
         * copia y la dejó en otra carpeta (no se toca); «e» Amazon ya la tenía aquí y la usa el Echo
         * (se ve, y no se borra); «x» no se ha podido (ya no está, o Amazon no deja); «», «?», «1»,
         * «2» falta (ver pending()).
         */
        String hide = "";
    }

    /**
     * Lo subido de un usuario: archivos (ruta relativa a su carpeta) y carpetas de Amazon ya creadas,
     * de la cuenta de Amazon «root». Si se conecta otra cuenta, se empieza de cero.
     */
    private static final class State {
        String root;
        /** Amazon no deja ocultar: no se sube nada hasta que deje. */
        boolean broken;
        final Map<String, Entry> files = new LinkedHashMap<String, Entry>();
        final Map<String, String> dirs = new HashMap<String, String>();

        boolean pendingHide() {
            for (Entry e : files.values()) if (pending(e.hide)) return true;
            return false;
        }
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

    /** Carpetas que se han dejado de copiar y falta borrar de Amazon: «ruta\tcuenta de Amazon\tcuándo». */
    private static File removalsFile(Context c, String user) {
        return new File(c.getFilesDir(), "backup-" + user + "-quitar.txt");
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
        String[] same = EchoSync.shared(c, user);
        if (same != null) throw new IOException(sameAccountMessage(user, same[0], same[1].equals("before")));
        String root = EchoSync.config(c, user).getProperty("root", "");
        synchronized (Backup.class) {
            List<String> list = folders(c, user);
            if (of(c, user, rel) == null) {
                // si ya había subcarpetas suyas marcadas, ahora van dentro de esta
                for (String f : new ArrayList<String>(list)) if (f.startsWith(rel + "/")) list.remove(f);
                list.add(rel);
                saveFolders(c, user, list);
            }
            // si se iba a borrar de Amazon, ya no (lo de otra cuenta de Amazon sí: esa copia ya no vale)
            List<String[]> rm = removals(c, user), keep = new ArrayList<String[]>();
            for (String[] r : rm) {
                boolean here = r[1].isEmpty() || r[1].equals(root);
                if (!here || !r[0].equals(rel) && !r[0].startsWith(rel + "/")) keep.add(r);
            }
            if (keep.size() != rm.size()) saveRemovals(c, user, keep);
        }
        kick(c, user);
    }

    /**
     * Deja de copiar la carpeta (y las marcadas dentro de ella) y borra de Amazon lo que se copió:
     * lo lleva a la papelera de Amazon Fotos el repaso, en segundo plano. Vale también para una que
     * ya no está marcada (borrada del disco, o que se dejó de copiar antes de la 2.23): se borra lo
     * que haya en Amazon en «Súbelo Backup/usuario/ruta». No vale para una carpeta que está dentro
     * de otra marcada (se copia entera).
     */
    static void remove(Context c, String user, String rel) throws IOException {
        remove(c, user, rel, null, true);
    }

    /**
     * node: la carpeta de Amazon que se quiere borrar (desde «☁ Copias en Amazon»), por si en Amazon
     * no se llama igual que en el disco. trash: borrar también de Amazon (la app de la 2.22 no lo
     * pide: para ella «Dejar de hacer copia» solo dejaba de copiar, y así sigue).
     */
    static void remove(Context c, String user, String rel, String node, boolean trash) throws IOException {
        State saved = load(c, user);
        if (node != null) {
            for (Map.Entry<String, String> d : saved.dirs.entrySet()) {
                if (node.equals(d.getValue()) && !d.getKey().isEmpty()) rel = d.getKey();
            }
        }
        // la cuenta de Amazon donde está la copia: la conectada, o si ya no hay, la de lo subido
        String root = EchoSync.config(c, user).getProperty("root");
        if (root == null) root = saved.root;
        if (root == null) root = EchoSync.lastRoot(c, user);
        if (root == null) root = "";
        List<String> gone = new ArrayList<String>();
        synchronized (Backup.class) {
            List<String> list = folders(c, user);
            for (String f : list) {
                if (rel.startsWith(f + "/")) {
                    throw new IOException("Está dentro de «" + f + "», que tiene copia entera: para borrarla de Amazon, "
                            + "deja de copiar «" + f + "».");
                }
            }
            for (String f : list) if (f.equals(rel) || f.startsWith(rel + "/")) gone.add(f);
            if (!gone.isEmpty()) {
                list.removeAll(gone);
                saveFolders(c, user, list);
            }
            if (trash) {
                List<String[]> rm = removals(c, user), keep = new ArrayList<String[]>();
                for (String[] r : rm) if (!r[1].equals(root) || !r[0].equals(rel) && !r[0].startsWith(rel + "/")) keep.add(r);
                keep.add(new String[]{rel, root, String.valueOf(System.currentTimeMillis())});
                saveRemovals(c, user, keep);
            }
        }
        synchronized (STATUS) {
            for (String f : gone) status(user).counts.remove(f);
        }
        kick(c, user);
    }

    /** Lo que falta borrar de Amazon: {ruta, cuenta de Amazon («» si no se sabía), cuándo se pidió}. */
    static synchronized List<String[]> removals(Context c, String user) {
        List<String[]> out = new ArrayList<String[]>();
        File f = removalsFile(c, user);
        if (!f.isFile()) return out;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    String[] x = l.split("\t", -1);
                    if (x.length == 3 && !x[0].isEmpty()) out.add(x);
                }
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static synchronized void saveRemovals(Context c, String user, List<String[]> list) throws IOException {
        File f = removalsFile(c, user), tmp = new File(f.getPath() + ".tmp");
        if (list.isEmpty()) {
            if (f.exists() && !f.delete()) throw new IOException("No se pudo guardar lo que falta borrar de Amazon");
            return;
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (String[] r : list) w.write(r[0] + "\t" + r[1] + "\t" + r[2] + "\n");
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar lo que falta borrar de Amazon");
    }

    /** Ya está borrada de Amazon (o no hacía falta): se quita de la lista. */
    private static synchronized void removalDone(Context c, String user, String[] done) throws IOException {
        List<String[]> rm = removals(c, user), keep = new ArrayList<String[]>();
        for (String[] r : rm) if (!r[0].equals(done[0]) || !r[1].equals(done[1])) keep.add(r);
        if (keep.size() != rm.size()) saveRemovals(c, user, keep);
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

    /** Se ha borrado del disco: deja de estar marcada (lo que ya está en Amazon se queda: es la copia). */
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

    private static String sameAccountMessage(String user, String other, boolean before) {
        if (before) {
            return other + " entró en Súbelo con esta misma cuenta de Amazon, así que sabe entrar en ella y vería "
                    + "tus copias (Amazon Fotos no tiene perfiles). Si la cuenta es tuya, cámbiale la contraseña en "
                    + "Amazon y pulsa «" + other + " ya no puede entrar». Si no, entra con tu propia cuenta de Amazon.";
        }
        return "Tu cuenta de Amazon es la misma que tiene conectada " + other + ", y Amazon Fotos no tiene "
                + "perfiles: cada uno vería las fotos del otro. Para hacer copias, cada uno tiene que entrar con su "
                + "propia cuenta de Amazon (pulsa «Desconectar» y entra con la tuya). Lo que ya se haya copiado sigue "
                + "en esa cuenta, en «" + AMAZON_FOLDER + "/" + user + "»: si no quieres que " + other + " lo vea, "
                + "deja de copiar esas carpetas («Dejar de hacer copia») y se borra de Amazon.";
    }

    static JSONObject statusJson(Context c, String user) throws JSONException {
        Properties p = EchoSync.config(c, user);
        Status s = status(user);
        JSONObject o = new JSONObject();
        o.put("connected", p.getProperty("cookie") != null);
        o.put("expired", p.getProperty("expired") != null);
        String[] same = EchoSync.shared(c, user);
        o.put("sameAs", same == null ? JSONObject.NULL : same[0]);
        o.put("sameBefore", same != null && same[1].equals("before"));
        o.put("hideError", s.hideError == null ? JSONObject.NULL : s.hideError);
        o.put("tld", p.getProperty("tld", "es"));
        o.put("amazonFolder", AMAZON_FOLDER + "/" + user);
        o.put("running", s.running);
        o.put("current", s.current == null ? JSONObject.NULL : s.current);
        o.put("lastRun", s.lastRun);
        o.put("lastOk", s.lastOk);
        String err = s.lastError == null ? s.removeError : s.removeError == null ? s.lastError : s.lastError + "\n" + s.removeError;
        o.put("error", err == null ? JSONObject.NULL : err);
        JSONArray rm = new JSONArray();
        for (String[] r : removals(c, user)) rm.put(r[0]);
        o.put("removing", rm);
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
        final int gen = EchoSync.gen(null); // antes de leer la cuenta: si cambia después, se nota
        List<String> folders = folders(c, user);
        final Status s = status(user);
        Properties p = EchoSync.config(c, user);
        String cookie = p.getProperty("cookie");
        State state = load(c, user);
        boolean removals = !removals(c, user).isEmpty();
        if (folders.isEmpty() && !state.pendingHide() && !removals) return;
        if (cookie == null) {
            if (!folders.isEmpty() || removals) s.lastError = "Súbelo no está conectado con Amazon: entra desde la app del móvil";
            return;
        }
        if (p.getProperty("expired") != null) {
            s.lastError = "La sesión de Amazon ha caducado: vuelve a entrar desde el móvil";
            return;
        }
        s.running = true;
        s.lastRun = System.currentTimeMillis();
        Amazon a = new Amazon(cookie, p.getProperty("tld", "es"));
        Hider hider = new Hider(s, EchoSync.nodes(c));
        try {
            EchoSync.fillAccount(c, user, p, a);
            String root = p.getProperty("root");
            if (state.root != null ? !state.root.equals(root) : !mine(a, p, state)) {
                state = new State(); // lo apuntado es de otra cuenta de Amazon: en esta no hay nada
            }
            state.root = root;
            save(c, user, state);
            // lo que se ha dejado de copiar se borra de Amazon antes que nada (aunque sea cuenta compartida)
            removePending(c, user, a, p, state, s, gen);
            // lo que falte por ocultar va antes que nada, y aunque luego no se pueda copiar
            // (cuenta compartida, ninguna carpeta marcada): así se ve lo menos posible
            hidePending(c, user, a, state, hider);
            hider.settle(a, state);
            save(c, user, state);
            if (state.broken) {
                s.lastError = HIDE_STOPPED; // no se sube nada más hasta que Amazon deje ocultar
                return;
            }
            String[] same = EchoSync.shared(c, user);
            if (same != null) {
                s.lastError = sameAccountMessage(user, same[0], same[1].equals("before"));
                return;
            }
            if (folders.isEmpty()) {
                s.lastError = null;
                if (!hider.anyFailed) s.hideError = null;
                return;
            }
            final Store st = Config.store(c);
            if (st == null || !st.available()) return; // sin disco no se toca nada
            checkDirs(a, state, folders, user);
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
                    if (done(e, it)) {
                        n[1]++;
                        n[3] += it.size;
                    }
                }
                synchronized (STATUS) {
                    s.counts.put(f, n);
                }
                todo.put(f, items);
            }
            int sinceSave = 0, failsInRow = 0;
            String lastFileError = null;
            for (Map.Entry<String, List<Item>> fe : todo.entrySet()) {
                long[] n;
                synchronized (STATUS) {
                    n = s.counts.get(fe.getKey());
                }
                for (final Item it : fe.getValue()) {
                    Entry e = state.files.get(it.key());
                    if (done(e, it)) continue;
                    if (!folders(c, user).contains(fe.getKey())) break; // la han desmarcado mientras tanto
                    // alguien ha conectado o quitado una cuenta de Amazon: se para y se vuelve a mirar todo
                    if (EchoSync.gen(null) != gen) throw new EchoSync.AccountChanged();
                    s.current = it.key();
                    try {
                        String parent = amazonDir(a, p, state, user, it.dirRel);
                        Amazon.Uploaded up = a.uploadNode(parent, it.name, new Amazon.Opener() {
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
                        }, it.size, true);
                        Entry ne = new Entry();
                        ne.size = it.size;
                        ne.node = up.id;
                        ne.hide = up.inFolder ? "" : "d";
                        state.files.put(it.key(), ne);
                        hider.hide(a, state, it.key(), ne);
                        if (n != null) {
                            n[1]++;
                            n[3] += it.size;
                        }
                        failsInRow = 0;
                        if (++sinceSave >= 10) {
                            save(c, user, state);
                            sinceSave = 0;
                        }
                    } catch (HideStopped ex) {
                        throw ex;
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
            hider.settle(a, state);
            save(c, user, state);
            s.lastError = state.broken ? HIDE_STOPPED : lastFileError;
            if (!hider.anyFailed) s.hideError = null;
            s.lastOk = System.currentTimeMillis();
        } catch (EchoSync.AccountChanged e) {
            save(c, user, state);
            kick(c, user);
        } catch (HideStopped e) {
            try {
                hider.settle(a, state);
            } catch (IOException ignored) {
            }
            save(c, user, state);
            s.lastError = e.getMessage();
            try {
                a.rootId(); // ¿o es que la sesión ya no vale?
            } catch (Amazon.AmazonException ex) {
                if (ex.code == 401) {
                    EchoSync.markExpired(c, user, cookie, ex.getMessage());
                    s.lastError = ex.getMessage();
                }
            } catch (IOException ignored) {
            }
        } catch (Amazon.AmazonException e) {
            save(c, user, state);
            if (e.code == 401) {
                EchoSync.markExpired(c, user, cookie, e.getMessage());
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
     * Lo apuntado por la 2.19 no dice de qué cuenta de Amazon es: es de esta si con esta sesión se
     * ve la carpeta de copias que se creó entonces (y es del mismo dueño).
     */
    private static boolean mine(Amazon a, Properties p, State state) throws IOException {
        if (state.files.isEmpty() && state.dirs.isEmpty()) return true;
        String top = state.dirs.get("");
        if (top == null) return false;
        JSONObject n = a.nodeIfMine(top);
        if (n == null) return false;
        String owner = p.getProperty("owner"), nodeOwner = n.optString("ownerId", "");
        return owner == null || nodeOwner.isEmpty() || owner.equals(nodeOwner);
    }

    /**
     * ¿Ya está en Amazon? Si hasta la 2.22 Amazon dijo que ya la tenía en otra carpeta («n» siendo
     * foto o vídeo), no: se sube otra vez pidiendo una copia propia, para que esté en la carpeta.
     */
    private static boolean done(Entry e, Item it) {
        // «e»: la usa el Echo (ver Hider): cuenta como copiada
        return e != null && e.size == it.size && !(e.hide.equals("n") && (Thumbs.isImage(it.name) || Thumbs.isVideo(it.name)));
    }

    /**
     * Si alguien ha borrado en la web de Amazon Fotos la carpeta de una marcada (o «Súbelo Backup»
     * entera), lo de dentro se vuelve a subir: sigue marcada, así que tiene que estar en Amazon.
     */
    private static void checkDirs(Amazon a, State state, List<String> folders, String user) throws IOException {
        // las carpetas marcadas, en cada repaso; sus subcarpetas, una vez al día
        long now = System.currentTimeMillis();
        boolean all;
        synchronized (STATUS) {
            Long last = FULL_CHECK.get(user);
            all = last == null || now - last > DAY_MS;
            if (all) FULL_CHECK.put(user, now);
        }
        List<String> check = new ArrayList<String>();
        for (String d : state.dirs.keySet()) if (d.isEmpty() || (all ? covered(folders, d) : folders.contains(d))) check.add(d);
        java.util.Collections.sort(check); // antes la de fuera que las de dentro
        for (String f : check) {
            String id = state.dirs.get(f);
            if (id == null) continue; // ya olvidada con la de fuera
            JSONObject n = a.nodeIfMine(id);
            if (n != null && !"TRASH".equals(n.optString("status"))) continue;
            if (f.isEmpty()) {
                state.dirs.clear();
                state.files.clear();
                return;
            }
            forget(state, f, new ArrayList<String>());
        }
    }

    // ---------- borrar de Amazon lo que se deja de copiar ----------

    /** ¿«path» es una carpeta marcada o está dentro de una? */
    private static boolean covered(List<String> marked, String path) {
        for (String m : marked) if (path.equals(m) || path.startsWith(m + "/")) return true;
        return false;
    }

    /** ¿Hay alguna carpeta marcada dentro de «path»? («» es la carpeta del usuario: las tiene todas) */
    private static boolean holdsMarked(List<String> marked, String path) {
        for (String m : marked) if (path.isEmpty() || m.startsWith(path + "/")) return true;
        return false;
    }

    private static void removePending(Context c, String user, Amazon a, Properties p, State state, Status s, int gen)
            throws IOException {
        String err = null;
        for (String[] r : removals(c, user)) {
            if (EchoSync.gen(null) != gen) throw new EchoSync.AccountChanged();
            String rel = r[0];
            List<String> marked = folders(c, user);
            boolean own = r[1].isEmpty() || r[1].equals(state.root);
            if (own && covered(marked, rel)) { // la han vuelto a marcar
                removalDone(c, user, r);
                continue;
            }
            Amazon x = a;
            Properties xp = p;
            State xs = state;
            if (!own) {
                // la copia se quedó en otra cuenta de Amazon (p. ej. la que compartía con el otro y se
                // ha cambiado a la suya): se borra con la sesión de quien la siga teniendo conectada
                String[] other = EchoSync.sessionFor(c, r[1], user);
                if (other == null) {
                    long since;
                    try {
                        since = Long.parseLong(r[2]);
                    } catch (NumberFormatException e) {
                        since = 0;
                    }
                    if (System.currentTimeMillis() - since > GIVE_UP_MS) {
                        removalDone(c, user, r); // ni se vuelve a esa cuenta: se deja estar
                    } else {
                        err = "«" + rel + "» sigue copiada en la otra cuenta de Amazon con la que entraste. Para borrarla, "
                                + "vuelve a entrar con esa cuenta un momento.";
                    }
                    continue;
                }
                x = new Amazon(other[1], other[2]);
                xp = new Properties();
                xp.setProperty("root", r[1]);
                xs = new State();
                xs.root = r[1];
                marked = new ArrayList<String>(); // lo marcado ahora es de la otra cuenta
            }
            s.current = "Borrando de Amazon «" + rel + "»";
            try {
                if (!trashFolder(c, user, x, xp, xs, own, r, marked, gen)) continue;
            } catch (EchoSync.AccountChanged e) {
                throw e;
            } catch (Amazon.AmazonException e) {
                if (e.code == 401 && own) throw e;
                err = "No he podido borrar «" + rel + "» de Amazon (" + e.getMessage() + "). Lo vuelvo a intentar en el siguiente repaso.";
                continue;
            } catch (IOException e) {
                err = "No he podido borrar «" + rel + "» de Amazon (" + e.getMessage() + "). Lo vuelvo a intentar en el siguiente repaso.";
                continue;
            }
            removalDone(c, user, r);
        }
        s.removeError = err;
        s.current = null;
    }

    /** ¿Sigue haciendo falta? (puede que la hayan vuelto a marcar mientras se miraba qué hay) */
    private static boolean stillWanted(Context c, String user, String[] r, boolean own) {
        if (own && covered(folders(c, user), r[0])) return false;
        for (String[] x : removals(c, user)) if (x[0].equals(r[0]) && x[1].equals(r[1])) return true;
        return false;
    }

    /** Una carpeta de Amazon, con lo que hay dentro. */
    private static final class Dir {
        String id;
        final List<String> files = new ArrayList<String>();
        final List<Dir> subs = new ArrayList<Dir>();
        /** Dentro hay algo que no se puede borrar: esta carpeta no se puede llevar entera. */
        boolean keeps;
        /** Amazon no ha dejado ver todo lo de dentro (más de 9800): hace falta otra vuelta. */
        boolean capped;
    }

    /**
     * Lleva a la papelera de Amazon lo que hay en «Súbelo Backup/usuario/rel», menos lo que siga
     * marcado y lo que use el Echo. Primero los archivos y luego las carpetas (por si Amazon no se
     * llevara lo de dentro). Devuelve false si al final ya no hacía falta.
     */
    private static boolean trashFolder(Context c, String user, Amazon a, Properties p, State state, boolean own, String[] r,
                                       List<String> marked, int gen) throws IOException {
        String rel = r[0];
        String id = findDir(a, p, state, user, rel);
        if (id == null) { // en Amazon no hay nada
            if (own) {
                forget(state, rel, marked);
                save(c, user, state);
            }
            return true;
        }
        // lo que el estado dice que es de carpetas aún marcadas no se toca nunca (aunque se llame igual)
        Set<String> keep = new HashSet<String>(EchoSync.nodes(c));
        for (Map.Entry<String, String> d : state.dirs.entrySet()) {
            if (covered(marked, d.getKey()) || holdsMarked(marked, d.getKey())) keep.add(d.getValue());
        }
        for (Map.Entry<String, Entry> f : state.files.entrySet()) {
            if (covered(marked, dirOf(f.getKey()))) keep.add(f.getValue().node);
        }
        List<String> dirs = new ArrayList<String>();
        String lastFile = null;
        for (int round = 0; round < 5; round++) {
            Dir top = scan(a, id, rel, marked, keep, 0);
            List<String> files = new ArrayList<String>();
            dirs = new ArrayList<String>();
            plan(top, files, dirs);
            if (round == 0) {
                if (!stillWanted(c, user, r, own)) return false;
                if (own) {
                    // se olvida antes de borrar nada: si se queda a medias y la vuelven a marcar, se sube de nuevo
                    forget(state, rel, marked);
                    save(c, user, state);
                }
            }
            if (EchoSync.gen(null) != gen) throw new EchoSync.AccountChanged();
            if (!files.isEmpty()) {
                a.trash(files);
                lastFile = files.get(files.size() - 1);
            }
            if (!top.capped) break;
        }
        a.trash(dirs);
        // Amazon no dice qué ha hecho: se mira que de verdad estén en la papelera
        List<String> check = new ArrayList<String>(dirs);
        if (lastFile != null) check.add(lastFile);
        for (String x : check) {
            JSONObject n = a.nodeIfMine(x);
            if (n != null && !"TRASH".equals(n.optString("status"))) throw new IOException("Amazon no lo ha llevado a la papelera");
        }
        return true;
    }

    private static Dir scan(Amazon a, String id, String path, List<String> marked, Set<String> keep, int depth) throws IOException {
        Dir d = new Dir();
        d.id = id;
        d.keeps = keep.contains(id) || holdsMarked(marked, path);
        if (depth > 30) {
            d.keeps = true; // tan adentro no se mira: no se toca
            return d;
        }
        List<JSONObject> kids = a.children(id);
        d.capped = kids.size() >= 9800;
        for (JSONObject ch : kids) {
            String cid = ch.optString("id");
            JSONArray parents = ch.optJSONArray("parents");
            // por si Amazon no hiciera caso del filtro: solo lo que de verdad está en esta carpeta
            if (parents != null && parents.length() > 0 && !Amazon.hasParent(ch, id)) continue;
            if ("FOLDER".equals(ch.optString("kind"))) {
                String sub = path + "/" + ch.optString("name");
                if (keep.contains(cid) || covered(marked, sub)) {
                    d.keeps = true;
                    continue;
                }
                Dir x = scan(a, cid, sub, marked, keep, depth + 1);
                if (x.keeps) d.keeps = true;
                if (x.capped) d.capped = true;
                d.subs.add(x);
            } else if (keep.contains(cid) || (parents != null && parents.length() > 1)) {
                d.keeps = true; // la usa el Echo, sigue marcada, o también está en otro sitio: no se toca
            } else {
                d.files.add(cid);
            }
        }
        return d;
    }

    /** Qué se lleva a la papelera: las carpetas enteras que se puede, y todos los archivos. */
    private static void plan(Dir d, List<String> files, List<String> dirs) {
        if (!d.keeps) dirs.add(d.id);
        addFiles(d, files, dirs, !d.keeps);
    }

    private static void addFiles(Dir d, List<String> files, List<String> dirs, boolean whole) {
        files.addAll(d.files);
        for (Dir x : d.subs) {
            if (!whole && !x.keeps) dirs.add(x.id);
            addFiles(x, files, dirs, whole || !x.keeps);
        }
    }

    /** La carpeta de Amazon de «rel» si está (no la crea). */
    private static String findDir(Amazon a, Properties p, State state, String user, String rel) throws IOException {
        String id = state.dirs.get(rel);
        if (id != null) {
            JSONObject n = a.nodeIfMine(id);
            if (n != null && !"TRASH".equals(n.optString("status"))) return id;
        }
        // si no, por nombre desde «Súbelo Backup/usuario»
        String root = p.getProperty("root");
        if (root == null) root = a.rootId();
        String cur = childFolder(a, root, AMAZON_FOLDER);
        if (cur != null) cur = childFolder(a, cur, user);
        for (String part : rel.split("/")) {
            if (cur == null) return null;
            cur = childFolder(a, cur, part);
        }
        return cur;
    }

    private static String childFolder(Amazon a, String parent, String name) throws IOException {
        for (JSONObject ch : a.children(parent)) {
            if ("FOLDER".equals(ch.optString("kind")) && name.equals(ch.optString("name"))) return ch.optString("id");
        }
        return null;
    }

    /** Se olvida lo apuntado de «rel» (ya no está en Amazon), menos lo que siga marcado. */
    private static void forget(State state, String rel, List<String> marked) {
        for (java.util.Iterator<Map.Entry<String, Entry>> it = state.files.entrySet().iterator(); it.hasNext(); ) {
            String dir = dirOf(it.next().getKey());
            if ((dir.equals(rel) || dir.startsWith(rel + "/")) && !covered(marked, dir)) it.remove();
        }
        for (java.util.Iterator<String> it = state.dirs.keySet().iterator(); it.hasNext(); ) {
            String dir = it.next();
            if ((dir.equals(rel) || dir.startsWith(rel + "/")) && !covered(marked, dir) && !holdsMarked(marked, dir)) it.remove();
        }
    }

    private static String dirOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? "" : key.substring(0, slash);
    }

    /** Oculta lo que falte, por orden: lo recién subido, lo de antes de la 2.22 y lo que ya falló. */
    private static void hidePending(Context c, String user, Amazon a, State state, Hider hider) throws IOException {
        List<String> keys = new ArrayList<String>();
        for (String want : new String[]{"", "?", "1", "2"}) {
            for (Map.Entry<String, Entry> en : state.files.entrySet()) if (en.getValue().hide.equals(want)) keys.add(en.getKey());
        }
        int changed = 0;
        for (String k : keys) {
            Entry e = state.files.get(k);
            String before = e.hide;
            hider.hide(a, state, k, e);
            if (!before.equals(e.hide) && ++changed >= 50) {
                save(c, user, state);
                changed = 0;
            }
        }
        if (changed > 0) save(c, user, state);
    }

    private static final String HIDE_STOPPED = "He parado las copias porque Amazon no deja ocultar las fotos. "
            + "Lo vuelvo a intentar en el siguiente repaso.";

    /** Amazon no deja ocultar las fotos: se para el repaso, para no subir más fotos que se vean. */
    private static final class HideStopped extends IOException {
        HideStopped(String msg) {
            super(msg);
        }
    }

    /**
     * Oculta en Amazon lo subido (solo fotos y vídeos: lo demás no sale en la línea de tiempo, ni en
     * los recuerdos ni en el Echo). Si fallan 3 seguidas se para el repaso. Al final (settle) se mira
     * si es cosa de esas fotos (otras sí se han podido ocultar: se reintentan en 3 repasos y luego se
     * dejan) o de Amazon (no se ha podido ninguna: se paran las copias hasta que deje).
     */
    private static final class Hider {
        private final Status s;
        /** Las fotos que usa el Echo: esas se tienen que ver. */
        private final Set<String> echo;
        private int failsInRow, ok;
        private final List<Entry> failed = new ArrayList<Entry>();
        boolean anyFailed;

        Hider(Status s, Set<String> echo) {
            this.s = s;
            this.echo = echo;
        }

        void hide(Amazon a, State st, String key, Entry e) throws IOException {
            if (!pending(e.hide)) return;
            String name = key.substring(key.lastIndexOf('/') + 1);
            if (!Thumbs.isImage(name) && !Thumbs.isVideo(name)) {
                e.hide = "n";
                return;
            }
            try {
                if (e.hide.equals("?")) {
                    // subida por la 2.19: si Amazon dijo que ya la tenía, puede ser una foto de otra
                    // carpeta (la del Echo, la que sube el móvil…), y esa no se toca
                    JSONObject n = a.nodeIfMine(e.node);
                    int slash = key.lastIndexOf('/');
                    String dir = slash < 0 ? null : st.dirs.get(key.substring(0, slash));
                    if (n == null) {
                        e.hide = "x"; // ya no está en Amazon
                        return;
                    }
                    if (dir == null || !Amazon.hasParent(n, dir)) {
                        e.hide = "n";
                        return;
                    }
                    e.hide = "";
                }
                if (echo.contains(e.node)) {
                    // es de la copia, pero el Echo usa esta misma (Amazon ya la tenía): se tiene que ver
                    e.hide = "e";
                    return;
                }
                a.hide(e.node);
                e.hide = "h";
                ok++;
                failsInRow = 0;
            } catch (Amazon.AmazonException ex) {
                if (ex.code == 401) throw ex;
                if (ex.code == 404 && gone(a, e)) {
                    e.hide = "x"; // ya no está en Amazon
                    return;
                }
                failed(e, ex.getMessage());
            } catch (IOException ex) {
                if (ex instanceof HideStopped) throw ex;
                failed(e, ex.getMessage());
            }
        }

        /** ¿De verdad ya no está? (un 404 al ocultar también podría ser que Amazon ha cambiado) */
        private static boolean gone(Amazon a, Entry e) throws IOException {
            try {
                return a.nodeIfMine(e.node) == null;
            } catch (Amazon.AmazonException ex) {
                if (ex.code == 401) throw ex;
                return false;
            } catch (IOException ex) {
                return false;
            }
        }

        private void failed(Entry e, String why) throws HideStopped {
            anyFailed = true;
            failed.add(e);
            s.hideError = "Amazon no deja ocultar algunas fotos de la copia (" + why + "): podrían salir en el Echo o en los recuerdos";
            if (++failsInRow >= 3) throw new HideStopped(HIDE_STOPPED);
        }

        /** Decide si los fallos eran de esas fotos o de Amazon (ver la clase). */
        void settle(Amazon a, State st) throws IOException {
            if (failed.isEmpty()) {
                if (ok > 0) st.broken = false;
                return;
            }
            boolean works = (ok > 0 && failsInRow < 3) || probe(a, st, echo);
            if (works) {
                for (Entry e : failed) {
                    if (!e.hide.equals("?")) e.hide = e.hide.equals("1") ? "2" : e.hide.equals("2") ? "x" : "1";
                }
                st.broken = false;
            } else {
                st.broken = true;
            }
            failed.clear();
            failsInRow = 0;
        }

        /** ¿Deja Amazon ocultar? Se prueba con una ya oculta (no cambia nada). */
        private static boolean probe(Amazon a, State st, Set<String> echo) throws IOException {
            for (Entry e : st.files.values()) {
                if (!e.hide.equals("h") || echo.contains(e.node)) continue;
                try {
                    a.hide(e.node);
                    return true;
                } catch (Amazon.AmazonException ex) {
                    if (ex.code == 401) throw ex;
                    return false;
                } catch (IOException ex) {
                    return false;
                }
            }
            return false;
        }
    }

    /** ¿Falta ocultarla? «» recién subida, «?» de la 2.19 (sin comprobar), «1»/«2» intentos fallidos. */
    private static boolean pending(String hide) {
        return hide.isEmpty() || hide.equals("?") || hide.equals("1") || hide.equals("2");
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
                    if ((x.length == 4 || x.length == 5) && x[0].equals("F")) {
                        Entry e = new Entry();
                        e.size = Long.parseLong(x[2]);
                        e.node = x[3];
                        e.hide = x.length == 5 ? x[4] : "?"; // de la 2.19: sin comprobar de qué carpeta es
                        s.files.put(x[1], e);
                    } else if (x.length == 3 && x[0].equals("D")) {
                        s.dirs.put(x[1], x[2]);
                    } else if (x.length == 2 && x[0].equals("R")) {
                        s.root = x[1];
                    } else if (x.length == 1 && x[0].equals("B")) {
                        s.broken = true;
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
            if (s.root != null) w.write("R\t" + s.root + "\n");
            if (s.broken) w.write("B\n");
            for (Map.Entry<String, String> d : s.dirs.entrySet()) w.write("D\t" + d.getKey() + "\t" + d.getValue() + "\n");
            for (Map.Entry<String, Entry> e : s.files.entrySet()) {
                Entry x = e.getValue();
                w.write("F\t" + e.getKey() + "\t" + x.size + "\t" + x.node + "\t" + x.hide + "\n");
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar lo subido a Amazon");
    }
}
