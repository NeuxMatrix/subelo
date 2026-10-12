package es.subelo.box;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Carpetas compartidas entre Fer y Julss. Quien comparte elige si el otro solo puede verla o
 * también añadir fotos; borrar, mover y renombrar solo puede el dueño. Lo que te comparten aparece
 * en tu carpeta como una carpeta especial, {@link #VIRTUAL}, que no existe en el disco.
 */
final class Shares {
    static final String VIRTUAL = "👥 Compartido conmigo";
    static final String VIEW = "ver";
    static final String ADD = "subir";

    static final class Share {
        String owner, path, guest, mode;
        /** Nombre con el que lo ve el invitado: «Fer · Viajes». */
        String name;
    }

    /** A qué carpeta real corresponde una ruta pedida por un usuario y qué puede hacer en ella. */
    static final class Access {
        boolean own, virtualRoot;
        String storeRel; // ruta en el disco («Fer/Viajes/2024»); null en la carpeta especial
        String owner;
        String mode;

        boolean canAdd() {
            return own || ADD.equals(mode);
        }
    }

    private static Properties props; // «dueño|ruta|invitado» → modo

    private Shares() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "compartidas.properties");
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
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar lo compartido");
    }

    private static List<Share> all(Context c) {
        List<Share> out = new ArrayList<Share>();
        for (Map.Entry<Object, Object> e : load(c).entrySet()) {
            String[] k = String.valueOf(e.getKey()).split("\\|", -1);
            if (k.length != 3) continue;
            Share s = new Share();
            s.owner = k[0];
            s.path = k[1];
            s.guest = k[2];
            s.mode = String.valueOf(e.getValue());
            out.add(s);
        }
        Collections.sort(out, new Comparator<Share>() {
            @Override
            public int compare(Share a, Share b) {
                int r = a.owner.compareToIgnoreCase(b.owner);
                return r != 0 ? r : a.path.compareToIgnoreCase(b.path);
            }
        });
        return out;
    }

    static synchronized void set(Context c, String owner, String path, String guest, String mode) throws IOException {
        load(c).setProperty(owner + "|" + path + "|" + guest, ADD.equals(mode) ? ADD : VIEW);
        save(c);
    }

    static synchronized void remove(Context c, String owner, String path, String guest) throws IOException {
        if (load(c).remove(owner + "|" + path + "|" + guest) != null) save(c);
    }

    /** Lo que han compartido con este usuario, con nombres únicos para mostrar. */
    static synchronized List<Share> incoming(Context c, String guest) {
        List<Share> out = new ArrayList<Share>();
        Set<String> used = new HashSet<String>();
        for (Share s : all(c)) {
            if (!s.guest.equals(guest)) continue;
            int slash = s.path.lastIndexOf('/');
            String base = HttpServer.cleanName(s.owner + " · " + (slash >= 0 ? s.path.substring(slash + 1) : s.path));
            String name = base;
            for (int i = 2; used.contains(name.toLowerCase()); i++) name = base + " (" + i + ")";
            used.add(name.toLowerCase());
            s.name = name;
            out.add(s);
        }
        return out;
    }

    /** Con quién comparte el dueño esta carpeta exacta: invitado → modo. */
    static synchronized Map<String, String> outgoing(Context c, String owner, String path) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Share s : all(c)) if (s.owner.equals(owner) && s.path.equals(path)) m.put(s.guest, s.mode);
        return m;
    }

    /** Todo lo que comparte el dueño. */
    static synchronized List<Share> outgoing(Context c, String owner) {
        List<Share> out = new ArrayList<Share>();
        for (Share s : all(c)) if (s.owner.equals(owner)) out.add(s);
        return out;
    }

    static boolean isVirtual(String rel) {
        return rel.equals(VIRTUAL) || rel.startsWith(VIRTUAL + "/");
    }

    /** null si la ruta apunta a algo compartido que ya no existe. */
    static Access resolve(Context c, String user, String rel) {
        Access a = new Access();
        if (rel.equals(VIRTUAL)) {
            a.virtualRoot = true;
            return a;
        }
        if (!rel.startsWith(VIRTUAL + "/")) {
            a.own = true;
            a.owner = user;
            a.storeRel = rel.isEmpty() ? user : user + "/" + rel;
            return a;
        }
        String rest = rel.substring(VIRTUAL.length() + 1);
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash + 1);
        for (Share s : incoming(c, user)) {
            if (s.name.equals(first)) {
                a.owner = s.owner;
                a.mode = s.mode;
                a.storeRel = s.owner + "/" + s.path + (tail.isEmpty() ? "" : "/" + tail);
                return a;
            }
        }
        return null;
    }

    /** El dueño ha movido o renombrado «from» a «to»: lo compartido dentro le sigue. */
    static synchronized void moved(Context c, String owner, String from, String to) throws IOException {
        boolean changed = false;
        for (Share s : all(c)) {
            if (!s.owner.equals(owner)) continue;
            String np = null;
            if (s.path.equals(from)) np = to;
            else if (s.path.startsWith(from + "/")) np = to + s.path.substring(from.length());
            if (np == null) continue;
            props.remove(s.owner + "|" + s.path + "|" + s.guest);
            props.setProperty(s.owner + "|" + np + "|" + s.guest, s.mode);
            changed = true;
        }
        if (changed) save(c);
    }

    /** El dueño ha borrado «path»: deja de compartirse lo que hubiera dentro. */
    static synchronized void deleted(Context c, String owner, String path) throws IOException {
        boolean changed = false;
        for (Share s : all(c)) {
            if (s.owner.equals(owner) && (s.path.equals(path) || s.path.startsWith(path + "/"))) {
                props.remove(s.owner + "|" + s.path + "|" + s.guest);
                changed = true;
            }
        }
        if (changed) save(c);
    }
}
