package es.subelo.box;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Los dos usuarios de Súbelo. Cada uno pone su contraseña la primera vez que entra y solo ve
 * su carpeta del disco. Las contraseñas se guardan cifradas (PBKDF2 con sal), nunca en claro.
 * Las sesiones del móvil se guardan para que sobrevivan a un reinicio del box. Las del navegador
 * solo viven en memoria: caducan tras {@link #WEB_IDLE_MS} sin usarse, a las {@link #WEB_MAX_MS} como
 * mucho, al cerrar el navegador o al reiniciarse el box, y entonces la web vuelve a pedir la contraseña.
 */
final class Users {
    static final String[] NAMES = {"Fer", "Julss"};
    static final int MIN_LENGTH = 4;
    static final long WEB_IDLE_MS = 30L * 60 * 1000L;
    static final long WEB_MAX_MS = 12L * 60 * 60 * 1000L;
    static final long APP_MS = 365L * 24 * 60 * 60 * 1000L;
    private static final int ITERATIONS = 20000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Properties users, sessions;

    /** Sesión de la web (en memoria): usuario, cuándo empezó y cuándo se usó por última vez. */
    private static final class Web {
        final String user;
        final long started = System.currentTimeMillis();
        long used = started;

        Web(String user) {
            this.user = user;
        }
    }

    private static final Map<String, Web> WEB = new java.util.HashMap<String, Web>();

    private Users() {}

    /** Nombre bien escrito («fer» → «Fer»), o null si no es uno de los dos usuarios. */
    static String canonical(String name) {
        if (name == null) return null;
        for (String n : NAMES) if (n.equalsIgnoreCase(name.trim())) return n;
        return null;
    }

    static synchronized boolean hasPassword(Context c, String user) {
        return load(c).getProperty(user + ".hash") != null;
    }

    /** Primera vez: fija la contraseña. Devuelve false si ya tenía una. */
    static synchronized boolean setFirst(Context c, String user, String password) throws IOException {
        Properties p = load(c);
        if (p.getProperty(user + ".hash") != null) return false;
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        p.setProperty(user + ".salt", hex(salt));
        p.setProperty(user + ".hash", hex(pbkdf2(password, salt)));
        save(c, p, "usuarios.properties");
        return true;
    }

    static boolean check(Context c, String user, String password) {
        String salt, hash;
        synchronized (Users.class) {
            Properties p = load(c);
            salt = p.getProperty(user + ".salt");
            hash = p.getProperty(user + ".hash");
        }
        if (salt == null || hash == null) return false;
        return MessageDigest.isEqual(unhex(hash), pbkdf2(password, unhex(salt)));
    }

    /** Desde la tele: borra la contraseña (se pondrá otra al entrar) y cierra todas sus sesiones. */
    static synchronized void reset(Context c, String user) throws IOException {
        Properties p = load(c);
        p.remove(user + ".hash");
        p.remove(user + ".salt");
        save(c, p, "usuarios.properties");
        Properties s = loadSessions(c);
        for (Iterator<Map.Entry<Object, Object>> it = s.entrySet().iterator(); it.hasNext(); ) {
            if (String.valueOf(it.next().getValue()).startsWith(user + "|")) it.remove();
        }
        save(c, s, "sesiones.properties");
        for (Iterator<Web> it = WEB.values().iterator(); it.hasNext(); ) {
            if (it.next().user.equals(user)) it.remove();
        }
    }

    // ---------- sesiones ----------

    static synchronized String newWebSession(String user) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String token = hex(b);
        long now = System.currentTimeMillis();
        for (Iterator<Web> it = WEB.values().iterator(); it.hasNext(); ) {
            Web w = it.next();
            if (now - w.used > WEB_IDLE_MS || now - w.started > WEB_MAX_MS) it.remove();
        }
        WEB.put(key(token), new Web(user));
        return token;
    }

    /** Usuario de una sesión de la web (y la da por usada), o null si no vale o ha caducado. */
    static synchronized String webUserFor(Context c, String token) {
        if (token == null || token.isEmpty() || token.length() > 100) return null;
        String k = key(token);
        Web w = WEB.get(k);
        if (w == null) return null;
        long now = System.currentTimeMillis();
        if (now - w.used > WEB_IDLE_MS || now - w.started > WEB_MAX_MS || !hasPassword(c, w.user)) {
            WEB.remove(k);
            return null;
        }
        w.used = now;
        return w.user;
    }

    static synchronized void endWebSession(String token) {
        if (token != null && !token.isEmpty()) WEB.remove(key(token));
    }

    /**
     * Hasta la 2.15 la web guardaba sesiones de 30 días en el mismo fichero que el móvil: se borran
     * (las del móvil duran un año, así que caducan mucho más tarde).
     */
    static synchronized void forgetOldWebSessions(Context c) {
        Properties s = loadSessions(c);
        long limit = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000L;
        boolean changed = false;
        for (Iterator<Map.Entry<Object, Object>> it = s.entrySet().iterator(); it.hasNext(); ) {
            if (expiry(String.valueOf(it.next().getValue())) < limit) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            try {
                save(c, s, "sesiones.properties");
            } catch (IOException ignored) {
            }
        }
    }

    static synchronized String newSession(Context c, String user, long ms) throws IOException {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String token = hex(b);
        Properties s = loadSessions(c);
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Object, Object>> it = s.entrySet().iterator(); it.hasNext(); ) {
            if (expiry(String.valueOf(it.next().getValue())) < now) it.remove();
        }
        s.setProperty(key(token), user + "|" + (now + ms));
        save(c, s, "sesiones.properties");
        return token;
    }

    /** Usuario dueño de la sesión, o null si no vale o ha caducado. */
    static synchronized String userFor(Context c, String token) {
        if (token == null || token.isEmpty() || token.length() > 100) return null;
        String v = loadSessions(c).getProperty(key(token));
        if (v == null || expiry(v) < System.currentTimeMillis()) return null;
        String user = v.substring(0, v.indexOf('|'));
        return hasPassword(c, user) ? user : null;
    }

    static synchronized void endSession(Context c, String token) throws IOException {
        if (token == null) return;
        Properties s = loadSessions(c);
        if (s.remove(key(token)) != null) save(c, s, "sesiones.properties");
    }

    // ---------- utilidades ----------

    private static long expiry(String v) {
        try {
            return Long.parseLong(v.substring(v.indexOf('|') + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** En disco solo se guarda el hash del token, no el token. */
    private static String key(String token) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties load(Context c) {
        if (users == null) users = read(new File(c.getFilesDir(), "usuarios.properties"));
        return users;
    }

    private static Properties loadSessions(Context c) {
        if (sessions == null) sessions = read(new File(c.getFilesDir(), "sesiones.properties"));
        return sessions;
    }

    private static Properties read(File f) {
        Properties p = new Properties();
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

    private static void save(Context c, Properties p, String name) throws IOException {
        File f = new File(c.getFilesDir(), name);
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            p.store(out, null);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar " + name);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
