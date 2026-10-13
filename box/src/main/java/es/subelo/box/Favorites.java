package es.subelo.box;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Favoritos: cada usuario marca fotos y vídeos (suyos o de carpetas que le comparten) y los ve juntos
 * en una carpeta especial, {@link #VIRTUAL}, que no existe en el disco. No se copia nada: solo se
 * apunta a dónde está cada archivo. Si el dueño lo mueve o le cambia el nombre, el favorito le
 * sigue; si lo borra, o deja de compartir su carpeta, desaparece.
 */
final class Favorites {
    static final String VIRTUAL = "⭐ Favoritos";

    static final class Fav {
        /** Ruta en el disco: «Fer/Viajes/Roma/IMG_1.jpg». */
        String store;
        /** Carpeta en el disco («Fer/Viajes/Roma») y nombre del archivo. */
        String dir, name;
        long added;
        /** Nombre en la carpeta de favoritos (único: si dos se llaman igual, «IMG_1 (2).jpg»). */
        String shown;
    }

    private static Properties props; // «usuario|ruta en el disco» → cuándo se marcó

    private Favorites() {}

    static boolean isVirtual(String rel) {
        return rel.equals(VIRTUAL) || rel.startsWith(VIRTUAL + "/");
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "favoritos.properties");
    }

    private static Properties load(Context c) {
        if (props == null) {
            props = new Properties();
            File f = file(c);
            if (f.isFile()) {
                try {
                    FileInputStream in = new FileInputStream(f);
                    try {
                        props.load(in);
                    } finally {
                        in.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return props;
    }

    private static void save(Context c) throws IOException {
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            props.store(out, null);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudieron guardar los favoritos");
    }

    /** Marca o desmarca varios archivos (rutas en el disco). Devuelve cuántos han cambiado. */
    static synchronized int set(Context c, String user, Collection<String> stores, boolean on) throws IOException {
        Properties p = load(c);
        int changed = 0;
        long now = System.currentTimeMillis();
        for (String s : stores) {
            String k = user + "|" + s;
            if (on && p.getProperty(k) == null) {
                p.setProperty(k, String.valueOf(now++)); // +1: que se queden en el orden en que se marcaron
                changed++;
            } else if (!on && p.remove(k) != null) {
                changed++;
            }
        }
        if (changed > 0) save(c);
        return changed;
    }

    /** Rutas en el disco de los favoritos de un usuario (para marcar ★ al listar una carpeta). */
    static synchronized Set<String> of(Context c, String user) {
        Set<String> out = new HashSet<String>();
        String prefix = user + "|";
        for (Object k : load(c).keySet()) {
            String s = String.valueOf(k);
            if (s.startsWith(prefix)) out.add(s.substring(prefix.length()));
        }
        return out;
    }

    /**
     * Los favoritos que el usuario aún puede ver (suyos o de carpetas que le comparten), los últimos
     * marcados primero y con nombres únicos.
     */
    static synchronized List<Fav> list(Context c, String user) {
        List<Shares.Share> in = Shares.incoming(c, user);
        List<Fav> out = new ArrayList<Fav>();
        String prefix = user + "|";
        for (Map.Entry<Object, Object> e : load(c).entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!k.startsWith(prefix)) continue;
            String store = k.substring(prefix.length());
            int slash = store.lastIndexOf('/');
            if (slash <= 0 || !readable(store, user, in)) continue;
            Fav f = new Fav();
            f.store = store;
            f.dir = store.substring(0, slash);
            f.name = store.substring(slash + 1);
            try {
                f.added = Long.parseLong(String.valueOf(e.getValue()));
            } catch (NumberFormatException ex) {
                f.added = 0;
            }
            out.add(f);
        }
        // los nombres se reparten del más antiguo al más nuevo: así marcar otro no cambia los que ya había
        Collections.sort(out, new Comparator<Fav>() {
            @Override
            public int compare(Fav a, Fav b) {
                return a.added != b.added ? Long.compare(a.added, b.added) : a.store.compareTo(b.store);
            }
        });
        Set<String> used = new HashSet<String>();
        for (Fav f : out) {
            String shown = f.name;
            int dot = f.name.lastIndexOf('.');
            String base = dot > 0 ? f.name.substring(0, dot) : f.name, ext = dot > 0 ? f.name.substring(dot) : "";
            for (int i = 2; used.contains(shown.toLowerCase()); i++) shown = base + " (" + i + ")" + ext;
            used.add(shown.toLowerCase());
            f.shown = shown;
        }
        Collections.reverse(out); // los últimos marcados, primero
        return out;
    }

    /** ¿Puede el usuario ver ese archivo? Lo suyo siempre; lo de otro, si está en algo que le comparten. */
    private static boolean readable(String store, String user, List<Shares.Share> in) {
        if (store.startsWith(user + "/")) return true;
        for (Shares.Share s : in) {
            if (store.startsWith(s.owner + "/" + s.path + "/")) return true;
        }
        return false;
    }

    /** Se ha movido o renombrado «from» (archivo o carpeta, rutas en el disco): los favoritos le siguen. */
    static synchronized void moved(Context c, String from, String to) throws IOException {
        Properties p = load(c);
        List<String[]> change = new ArrayList<String[]>();
        for (Object o : p.keySet()) {
            String k = String.valueOf(o);
            int bar = k.indexOf('|');
            if (bar < 0) continue;
            String store = k.substring(bar + 1);
            if (store.equals(from) || store.startsWith(from + "/")) {
                change.add(new String[]{k, k.substring(0, bar + 1) + to + store.substring(from.length())});
            }
        }
        for (String[] ch : change) {
            String v = p.getProperty(ch[0]);
            p.remove(ch[0]);
            p.setProperty(ch[1], v);
        }
        if (!change.isEmpty()) save(c);
    }

    /** Se ha borrado «store» (archivo o carpeta): fuera de los favoritos de todos. */
    static synchronized void deleted(Context c, String store) throws IOException {
        Properties p = load(c);
        List<Object> gone = new ArrayList<Object>();
        for (Object o : p.keySet()) {
            String k = String.valueOf(o);
            String s = k.substring(k.indexOf('|') + 1);
            if (s.equals(store) || s.startsWith(store + "/")) gone.add(o);
        }
        for (Object o : gone) p.remove(o);
        if (!gone.isEmpty()) save(c);
    }
}
