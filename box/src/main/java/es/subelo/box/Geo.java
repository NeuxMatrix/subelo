package es.subelo.box;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Ubicaciones puestas a mano («📍 Ubicación»): dónde se hizo una foto o un vídeo que no lo guardaba,
 * o lo guardaba mal. No se toca el archivo: se apunta aquí (ruta en el disco → «lat,lon») y el mapa
 * lo usa en lugar del GPS de la foto. Si el archivo se mueve o cambia de nombre, la ubicación le
 * sigue; si se borra, desaparece.
 */
final class Geo {
    private static Properties props; // «Fer/Viajes/IMG_1.jpg» → «40.416800,-3.703800»

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

    /** Pone la ubicación a varios archivos (rutas en el disco). Devuelve cuántos. */
    static synchronized int set(Context c, Collection<String> stores, double lat, double lon) throws IOException {
        if (!valid(lat, lon)) throw new IllegalArgumentException("Ese sitio no existe");
        Properties p = load(c);
        String v = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        for (String s : stores) p.setProperty(s, v);
        if (!stores.isEmpty()) save(c);
        return stores.size();
    }

    /** Quita la ubicación puesta a mano (vuelve la del GPS de la foto, si tenía). Devuelve cuántos. */
    static synchronized int clear(Context c, Collection<String> stores) throws IOException {
        Properties p = load(c);
        int n = 0;
        for (String s : stores) if (p.remove(s) != null) n++;
        if (n > 0) save(c);
        return n;
    }

    /** {lat, lon} puesta a mano, o null. */
    static synchronized double[] get(Context c, String store) {
        return parse(load(c).getProperty(store));
    }

    /** Las de un usuario (ruta en el disco → {lat, lon}), para el mapa. */
    static synchronized Map<String, double[]> of(Context c, String user) {
        Map<String, double[]> out = new HashMap<String, double[]>();
        String prefix = user + "/";
        for (Map.Entry<Object, Object> e : load(c).entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!k.startsWith(prefix)) continue;
            double[] ll = parse(String.valueOf(e.getValue()));
            if (ll != null) out.put(k, ll);
        }
        return out;
    }

    private static double[] parse(String v) {
        if (v == null) return null;
        int comma = v.indexOf(',');
        if (comma < 0) return null;
        try {
            double lat = Double.parseDouble(v.substring(0, comma)), lon = Double.parseDouble(v.substring(comma + 1));
            return valid(lat, lon) ? new double[]{lat, lon} : null;
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
