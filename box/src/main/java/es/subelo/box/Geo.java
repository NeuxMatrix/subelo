package es.subelo.box;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Ubicaciones puestas a mano («📍 Ubicación»): dónde se hizo una foto o un vídeo que no lo guardaba,
 * o lo guardaba mal, y para quién es en el mapa. No se toca el archivo: se apunta aquí.
 * <ul>
 * <li>A un archivo («Fer/Viajes/IMG_1.jpg»): manda sobre el GPS de la foto.</li>
 * <li>A una carpeta («Fer/Viajes/Roma/», con barra al final): las fotos de dentro (y de sus
 * subcarpetas) que no tienen GPS van a ese sitio; las que lo tienen se quedan donde están.</li>
 * </ul>
 * Cada una dice también con quién se comparte en el mapa («con Julss»): entonces sale también en el
 * mapa del otro. Una carpeta «con Julss» comparte todo lo de dentro (con o sin GPS, y aunque alguna foto
 * tenga su propia ubicación «solo yo»). Si el archivo o la carpeta se mueve o cambia de nombre, la
 * ubicación le sigue; si se borra, desaparece.
 */
final class Geo {
    /**
     * Ruta especial para pedir (solo ver) las fotos que el otro ha ubicado «con» contigo:
     * «🗺 Mapa común/Fer/Viajes/Roma» + name. No se lista: solo miniatura, archivo y ubicación.
     */
    static final String VIRTUAL = "🗺 Mapa común";

    /** Una ubicación puesta a mano. */
    static final class Entry {
        final double lat, lon;
        /** Con quién se comparte en el mapa (vacío: solo el dueño). */
        final Set<String> with;

        Entry(double lat, double lon, Set<String> with) {
            this.lat = lat;
            this.lon = lon;
            this.with = with;
        }
    }

    private static Properties props; // ruta → «40.416800,-3.703800» o «40.416800,-3.703800|Julss»

    private Geo() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "ubicaciones.properties");
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
        if (!tmp.renameTo(f)) throw new IOException("No se pudieron guardar las ubicaciones");
    }

    static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    /**
     * Pone la ubicación a varios archivos o carpetas (rutas en el disco; las carpetas acaban en «/»),
     * compartida en el mapa con «with» (puede estar vacío). Devuelve cuántos.
     */
    static synchronized int set(Context c, Collection<String> keys, double lat, double lon, Collection<String> with)
            throws IOException {
        if (!valid(lat, lon)) throw new IllegalArgumentException("Ese sitio no existe");
        Properties p = load(c);
        StringBuilder v = new StringBuilder(String.format(Locale.US, "%.6f,%.6f", lat, lon));
        if (with != null && !with.isEmpty()) {
            v.append('|');
            boolean first = true;
            for (String w : with) {
                if (!first) v.append(',');
                first = false;
                v.append(w);
            }
        }
        for (String k : keys) p.setProperty(k, v.toString());
        if (!keys.isEmpty()) save(c);
        return keys.size();
    }

    /** Quita la ubicación puesta a mano (vuelve la del GPS de la foto, si tenía). Devuelve cuántos. */
    static synchronized int clear(Context c, Collection<String> keys) throws IOException {
        Properties p = load(c);
        int n = 0;
        for (String k : keys) if (p.remove(k) != null) n++;
        if (n > 0) save(c);
        return n;
    }

    /** La puesta a mano a ese archivo o carpeta, o null. */
    static synchronized Entry get(Context c, String key) {
        return parse(load(c).getProperty(key));
    }

    /** Todas (ruta → ubicación), para el mapa: una copia, que se puede leer sin bloquear. */
    static synchronized Map<String, Entry> all(Context c) {
        Map<String, Entry> out = new HashMap<String, Entry>();
        for (Map.Entry<Object, Object> e : load(c).entrySet()) {
            Entry en = parse(String.valueOf(e.getValue()));
            if (en != null) out.put(String.valueOf(e.getKey()), en);
        }
        return out;
    }

    /** La carpeta más cercana a un archivo («Fer/A/B/x.jpg» → «Fer/A/B/», luego «Fer/A/»…) con ubicación, o null. */
    static Entry folderOf(Map<String, Entry> all, String store) {
        for (int i = store.lastIndexOf('/'); i > 0; i = store.lastIndexOf('/', i - 1)) {
            Entry e = all.get(store.substring(0, i + 1));
            if (e != null) return e;
        }
        return null;
    }

    /** Con quién sale en el mapa: los de su propia ubicación y los de las carpetas que la contienen. */
    static Set<String> sharedWith(Map<String, Entry> all, String store) {
        Set<String> w = new LinkedHashSet<String>();
        Entry e = all.get(store);
        if (e != null) w.addAll(e.with);
        w.addAll(inherited(all, store));
        return w;
    }

    /** Con quién la comparten las carpetas que la contienen (sin contar su propia ubicación). */
    static Set<String> inherited(Map<String, Entry> all, String store) {
        Set<String> w = new LinkedHashSet<String>();
        String s = store.endsWith("/") ? store.substring(0, store.length() - 1) : store;
        for (int i = s.lastIndexOf('/'); i > 0; i = s.lastIndexOf('/', i - 1)) {
            Entry f = all.get(s.substring(0, i + 1));
            if (f != null) w.addAll(f.with);
        }
        return w;
    }

    /** ¿Sale ese archivo (ruta en el disco) en el mapa de «user» porque su dueño se lo ha compartido? */
    static boolean onMapOf(Context c, String store, String user) {
        return sharedWith(all(c), store).contains(user);
    }

    private static Entry parse(String v) {
        if (v == null) return null;
        String with = "";
        int bar = v.indexOf('|');
        if (bar >= 0) {
            with = v.substring(bar + 1);
            v = v.substring(0, bar);
        }
        int comma = v.indexOf(',');
        if (comma < 0) return null;
        try {
            double lat = Double.parseDouble(v.substring(0, comma)), lon = Double.parseDouble(v.substring(comma + 1));
            if (!valid(lat, lon)) return null;
            Set<String> w = new LinkedHashSet<String>();
            for (String u : with.split(",")) if (!u.trim().isEmpty()) w.add(u.trim());
            return new Entry(lat, lon, Collections.unmodifiableSet(w));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Se ha movido o renombrado «from» (archivo o carpeta, rutas en el disco): las ubicaciones le siguen. */
    static synchronized void moved(Context c, String from, String to) throws IOException {
        Properties p = load(c);
        List<String> change = new ArrayList<String>();
        for (Object o : p.keySet()) {
            String k = String.valueOf(o);
            if (k.equals(from) || k.startsWith(from + "/")) change.add(k);
        }
        for (String k : change) {
            String v = p.getProperty(k);
            p.remove(k);
            p.setProperty(to + k.substring(from.length()), v);
        }
        if (!change.isEmpty()) save(c);
    }

    /** Se ha borrado «store» (archivo o carpeta). */
    static synchronized void deleted(Context c, String store) throws IOException {
        Properties p = load(c);
        List<Object> gone = new ArrayList<Object>();
        for (Object o : p.keySet()) {
            String k = String.valueOf(o);
            if (k.equals(store) || k.startsWith(store + "/")) gone.add(o);
        }
        for (Object o : gone) p.remove(o);
        if (!gone.isEmpty()) save(c);
    }
}
