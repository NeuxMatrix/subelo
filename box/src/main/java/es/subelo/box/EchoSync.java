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
    /** Sesiones ya descifradas, {cookieEnc, cookie} por usuario: el almacén seguro es lento. */
    private static final Map<String, String[]> OPENED = new HashMap<String, String[]>();
    /** Cuántas veces se ha conectado o desconectado Amazon (por usuario, y en total). */
    private static final Map<String, Integer> GEN = new HashMap<String, Integer>();
    private static int genAll;
    private static boolean seeded;

    /** La cuenta de Amazon ha cambiado a mitad de un repaso: se deja y el siguiente empieza con la nueva. */
    static final class AccountChanged extends IOException {
        AccountChanged() {
            super("La cuenta de Amazon ha cambiado");
        }
    }

    private EchoSync() {}

    /** Cambia cada vez que se conecta o se quita una cuenta de Amazon: de ese usuario, o con null de cualquiera. */
    static synchronized int gen(String user) {
        if (user == null) return genAll;
        Integer g = GEN.get(user);
        return g == null ? 0 : g;
    }

    private static synchronized void bump(String user) {
        GEN.put(user, gen(user) + 1);
        genAll++;
    }

    // ---------- ajustes por usuario ----------

    private static File configFile(Context c, String user) {
        return new File(c.getFilesDir(), "amazon-" + user + ".properties");
    }

    private static File stateFile(Context c, String user) {
        return new File(c.getFilesDir(), "echo-" + user + ".txt");
    }

    /** Las cuentas de Amazon que ha usado cada usuario (no se borra al desconectar). */
    private static File historyFile(Context c) {
        return new File(c.getFilesDir(), "amazon-cuentas.txt");
    }

    /** Los ajustes tal cual están en el archivo, sin descifrar la sesión. */
    private static synchronized Properties raw(Context c, String user) {
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

    /** ¿Hay una sesión guardada (aunque no se pueda descifrar)? */
    private static boolean hasSession(Properties raw) {
        return raw.getProperty("cookie") != null || raw.getProperty("cookieEnc") != null;
    }

    static synchronized Properties config(Context c, String user) {
        Properties p = raw(c, user);
        String enc = p.getProperty("cookieEnc");
        if (enc != null) {
            // la sesión va cifrada con la llave del box; si no se puede abrir, toca volver a entrar
            String[] known = OPENED.get(user);
            String cookie = known != null && known[0].equals(enc) ? known[1] : Secret.open(p.getProperty("cookieIv"), enc);
            p.remove("cookieEnc");
            p.remove("cookieIv");
            if (cookie != null) {
                p.setProperty("cookie", cookie);
                OPENED.put(user, new String[]{enc, cookie});
            } else {
                p.setProperty("unreadable", "1");
            }
        } else if (p.getProperty("cookie") != null && Secret.works()) {
            try {
                save(c, user, p); // de las versiones de antes: se cifra ya
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    private static synchronized void save(Context c, String user, Properties p) throws IOException {
        File f = configFile(c, user), tmp = new File(f.getPath() + ".tmp");
        Properties disk = new Properties();
        disk.putAll(p);
        disk.remove("unreadable");
        String cookie = p.getProperty("cookie");
        if (cookie != null) {
            String[] sealed = Secret.works() ? Secret.seal(cookie) : null;
            if (sealed != null) {
                disk.remove("cookie");
                disk.setProperty("cookieIv", sealed[0]);
                disk.setProperty("cookieEnc", sealed[1]);
            }
        }
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            disk.store(out, null);
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
        String[] root = a.root();
        synchronized (EchoSync.class) {
            Properties p = config(c, user);
            String oldRoot = p.getProperty("root");
            if (oldRoot != null && !oldRoot.equals(root[0])) {
                // otra cuenta de Amazon: el álbum y la carpeta de la anterior no valen aquí
                p.remove("album");
                p.remove("albumName");
                p.remove("folder");
                stateFile(c, user).delete();
            }
            p.setProperty("cookie", cookie);
            p.setProperty("tld", tld);
            p.setProperty("root", root[0]);
            if (root[1] != null) p.setProperty("owner", root[1]);
            else p.remove("owner");
            p.remove("expired");
            save(c, user, p);
            bump(user); // lo que esté subiendo con la sesión de antes, que pare
            seed(c);
            remember(c, user, root);
            // si otro dijo «ya no puede entrar» de esta cuenta, ya no vale: acaba de entrar
            for (String other : Users.NAMES) {
                if (other.equals(user)) continue;
                Properties q = raw(c, other);
                if (root[0].equals(q.getProperty("trusted"))) {
                    q.remove("trusted");
                    save(c, other, q);
                }
            }
        }
        status(user).lastError = null;
        kick(c, user);
        // las copias que esperaban a que se volviera a entrar, y las del otro (puede que ya no compartan cuenta)
        for (String u : Users.NAMES) Backup.kick(c, u);
    }

    /**
     * Las conexiones de antes de la 2.22 no guardaban de qué cuenta de Amazon eran: se averigua
     * con la sesión y se apunta (sin eso no se sabría si Fer y Julss comparten cuenta).
     */
    static void fillAccount(Context c, String user, Properties p, Amazon a) throws IOException {
        if (p.getProperty("root") != null) return;
        String[] root = a.root();
        synchronized (EchoSync.class) {
            Properties q = config(c, user);
            if (!p.getProperty("cookie", "").equals(q.getProperty("cookie"))) throw new AccountChanged();
            q.setProperty("root", root[0]);
            if (root[1] != null) q.setProperty("owner", root[1]);
            save(c, user, q);
            remember(c, user, root);
        }
        p.setProperty("root", root[0]);
        if (root[1] != null) p.setProperty("owner", root[1]);
    }

    /**
     * La primera vez en cada arranque: apunta en el historial las cuentas ya conectadas (las de
     * antes de la 2.22 no se apuntaban), para que «antes» las tenga en cuenta.
     */
    private static synchronized void seed(Context c) {
        if (seeded) return;
        seeded = true;
        for (String u : Users.NAMES) {
            Properties p = raw(c, u);
            if (p.getProperty("root") != null) remember(c, u, new String[]{p.getProperty("root"), p.getProperty("owner")});
        }
    }

    /** Apunta que este usuario ha entrado con esta cuenta de Amazon. */
    private static synchronized void remember(Context c, String user, String[] root) {
        for (String[] h : history(c)) if (h[0].equals(user) && h[1].equals(root[0])) return;
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(historyFile(c), true), "UTF-8");
            try {
                w.write(user + "\t" + root[0] + "\t" + (root[1] == null ? "" : root[1]) + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static synchronized List<String[]> history(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        File f = historyFile(c);
        if (!f.isFile()) return out;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    String[] x = l.split("\t", -1);
                    if (x.length == 3 && !x[0].isEmpty() && !x[1].isEmpty()) out.add(x);
                }
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * Si el otro usuario de Súbelo tiene conectada esta misma cuenta de Amazon («now»), o entró con
     * ella alguna vez («before»): {usuario, "now" o "before"}, o null. Amazon Fotos no tiene perfiles:
     * quien entra en una cuenta lo ve todo, así que la copia de seguridad no se hace. Lo de «antes»
     * vale si el dueño dice que el otro ya no sabe entrar (trust()).
     */
    static String[] shared(Context c, String user) {
        seed(c);
        // sin descifrar: si la sesión del otro no se pudiera abrir, seguiría contando como conectada
        Properties mine = raw(c, user);
        String root = mine.getProperty("root"), owner = mine.getProperty("owner");
        if (!hasSession(mine) || root == null) return null;
        for (String other : Users.NAMES) {
            if (other.equals(user)) continue;
            Properties p = raw(c, other);
            if (hasSession(p) && same(root, owner, p.getProperty("root"), p.getProperty("owner"))) {
                return new String[]{other, "now"};
            }
        }
        if (root.equals(mine.getProperty("trusted"))) return null;
        for (String[] h : history(c)) {
            if (h[0].equals(user)) continue;
            if (same(root, owner, h[1], h[2].isEmpty() ? null : h[2])) return new String[]{h[0], "before"};
        }
        return null;
    }

    private static boolean same(String root, String owner, String root2, String owner2) {
        return root.equals(root2) || owner != null && owner.equals(owner2);
    }

    /** El otro usuario con quien se comparte (o se compartió) la cuenta de Amazon, o null. */
    static String sameAccountAs(Context c, String user) {
        String[] s = shared(c, user);
        return s == null ? null : s[0];
    }

    /**
     * El dueño de la cuenta dice que el otro ya no sabe entrar en ella (ha cambiado la contraseña de
     * Amazon, o nunca la supo): vuelven las copias. Si el otro la tiene conectada ahora, no sirve.
     */
    static void trust(Context c, String user) throws IOException {
        synchronized (EchoSync.class) {
            Properties p = config(c, user);
            String root = p.getProperty("root");
            if (p.getProperty("cookie") == null || root == null) throw new IOException("Primero entra en Amazon");
            String[] sh = shared(c, user);
            if (sh != null && sh[1].equals("now")) {
                throw new IOException(sh[0] + " tiene conectada ahora esta misma cuenta de Amazon: primero tiene que quitarla");
            }
            p.setProperty("trusted", root);
            save(c, user, p);
        }
        Backup.kick(c, user);
    }

    static void disconnect(Context c, String user) {
        synchronized (EchoSync.class) {
            seed(c);
            Properties p = raw(c, user);
            if (p.getProperty("root") != null) remember(c, user, new String[]{p.getProperty("root"), p.getProperty("owner")});
            configFile(c, user).delete();
            stateFile(c, user).delete();
            OPENED.remove(user);
            bump(user);
        }
        Status s = status(user);
        s.lastError = null;
        s.inAlbum = 0;
        s.pending = 0;
        for (String u : Users.NAMES) Backup.kick(c, u); // el otro puede que ya no comparta cuenta
    }

    /**
     * Olvida solo la sesión de Amazon (al cambiar la contraseña de Súbelo desde la tele): quien ponga
     * la nueva no hereda la cuenta, pero si vuelve a entrar con la misma, el álbum del Echo sigue.
     */
    static void forgetSession(Context c, String user) {
        synchronized (EchoSync.class) {
            Properties p = raw(c, user);
            if (p.isEmpty()) return;
            p.remove("cookie");
            p.remove("cookieEnc");
            p.remove("cookieIv");
            p.remove("expired");
            OPENED.remove(user);
            try {
                save(c, user, p);
            } catch (IOException e) {
                configFile(c, user).delete();
            }
            bump(user);
        }
        status(user).lastError = null;
    }

    static List<Amazon.Album> albums(Context c, String user) throws IOException {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
        try {
            return amazon(p).albums();
        } catch (Amazon.AmazonException e) {
            if (e.code == 401) expired(c, user, p.getProperty("cookie"), e.getMessage());
            throw e;
        }
    }

    static Amazon.Album albumFromLink(Context c, String user, String link) throws IOException {
        Properties p = config(c, user);
        if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
        return amazon(p).albumFromLink(link);
    }

    static void setAlbum(Context c, String user, String id, String name) throws IOException {
        synchronized (EchoSync.class) {
            Properties p = config(c, user);
            if (p.getProperty("cookie") == null) throw new IOException("Primero conecta Amazon Photos");
            p.setProperty("album", id);
            p.setProperty("albumName", name == null || name.isEmpty() ? "Álbum" : name);
            save(c, user, p);
        }
        kick(c, user);
    }

    static JSONObject statusJson(Context c, String user) throws JSONException {
        Properties p = config(c, user);
        Status s = status(user);
        JSONObject o = new JSONObject();
        o.put("connected", p.getProperty("cookie") != null);
        o.put("expired", p.getProperty("expired") != null);
        String[] same = shared(c, user);
        o.put("sameAs", same == null ? JSONObject.NULL : same[0]);
        o.put("sameBefore", same != null && same[1].equals("before"));
        o.put("encrypted", Secret.works());
        o.put("folder", FOLDER);
        if (p.getProperty("unreadable") != null && s.lastError == null) {
            s.lastError = "No se ha podido leer la sesión de Amazon guardada en el box: vuelve a entrar";
        }
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

    /** La sesión de Amazon «cookie» ya no vale (lo usa también la copia de seguridad). */
    static void markExpired(Context c, String user, String cookie, String msg) {
        expired(c, user, cookie, msg);
    }

    private static void expired(Context c, String user, String cookie, String msg) {
        synchronized (EchoSync.class) {
            Properties p = config(c, user);
            // si mientras tanto ha entrado otra vez (o con otra cuenta), la nueva sesión no ha caducado
            if (p.getProperty("cookie") == null || !p.getProperty("cookie").equals(cookie)) return;
            p.setProperty("expired", "1");
            try {
                save(c, user, p);
            } catch (IOException ignored) {
            }
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
        final int gen = gen(user);
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
            String cookie = p.getProperty("cookie");
            try {
                fillAccount(c, user, p, a);
                for (final Store.FileInfo f : photos) {
                    if (gen(user) != gen) throw new AccountChanged();
                    present.add(f.name);
                    Entry e = state.get(f.name);
                    if (e != null && e.size == f.size) {
                        if (!album.equals(e.album)) toAdd.add(e.node); // ya subida; falta en este álbum
                        continue;
                    }
                    if (e != null && album.equals(e.album)) toRemove.add(e.node); // la foto cambió
                    if (folder == null) {
                        folder = a.folder(p.getProperty("root"), AMAZON_FOLDER);
                        setFolder(c, user, gen, folder);
                    }
                    Amazon.Uploaded up = a.uploadNode(folder, f.name, new Amazon.Opener() {
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
                    if (!up.inFolder) {
                        // ya estaba en Amazon en otra carpeta (la copia de seguridad, que va oculta, o la
                        // del móvil): se usa esa, pero sin ocultar, que es para que salga en el Echo
                        try {
                            a.setHidden(up.id, false);
                        } catch (Amazon.AmazonException ex) {
                            if (ex.code == 401) throw ex;
                        }
                    }
                    Entry n = new Entry();
                    n.name = f.name;
                    n.size = f.size;
                    n.node = up.id;
                    n.album = null;
                    state.put(f.name, n);
                    toAdd.add(up.id);
                    if (toAdd.size() >= 10) flush(c, user, gen, a, album, state, toAdd, toRemove, s);
                }
                for (Entry e : new ArrayList<Entry>(state.values())) {
                    if (present.contains(e.name)) continue;
                    if (album.equals(e.album)) toRemove.add(e.node);
                    state.remove(e.name);
                }
                flush(c, user, gen, a, album, state, toAdd, toRemove, s);
                s.lastError = null;
                s.lastOk = System.currentTimeMillis();
            } catch (AccountChanged e) {
                // lo de la sesión de antes ya no vale: ni se guarda ni es un error
            } catch (Amazon.AmazonException e) {
                saveQuietly(c, user, gen, state);
                if (e.code == 401) expired(c, user, cookie, e.getMessage());
                else s.lastError = e.getMessage();
            } catch (IOException e) {
                saveQuietly(c, user, gen, state);
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

    /** Apunta la carpeta de Amazon, si la sesión sigue siendo la misma. */
    private static synchronized void setFolder(Context c, String user, int gen, String folder) throws IOException {
        if (gen(user) != gen) throw new AccountChanged();
        Properties q = config(c, user);
        q.setProperty("folder", folder);
        save(c, user, q);
    }

    /** Añade al álbum lo subido, quita lo que ya no está y apunta el progreso. */
    private static void flush(Context c, String user, int gen, Amazon a, String album, Map<String, Entry> state,
                              List<String> toAdd, List<String> toRemove, Status s) throws IOException {
        if (gen(user) != gen) throw new AccountChanged();
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
        save(c, user, gen, state);
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

    private static void saveQuietly(Context c, String user, int gen, Map<String, Entry> state) {
        try {
            save(c, user, gen, state);
        } catch (IOException ignored) {
        }
    }

    /** Guarda lo enviado al Echo, salvo que la cuenta de Amazon haya cambiado desde «gen». */
    private static synchronized void save(Context c, String user, int gen, Map<String, Entry> state) throws IOException {
        if (gen(user) != gen) throw new AccountChanged();
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
