package es.subelo.box;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Hasta la 2.29 había «☁ Copias en Amazon» y «Traer de Google Fotos». Ya no existen: al arrancar
 * se borra lo que dejaron en la memoria interna del box (listas de carpetas, lo subido, el cliente
 * de Google y el historial de cuentas de Amazon). Las fotos del disco y lo que ya esté en Amazon
 * no se tocan, ni la conexión con Amazon del Echo.
 */
final class Leftovers {
    private static final String TAG = "SubeloLeftovers";

    private Leftovers() {}

    static void clean(Context c) {
        File dir = c.getFilesDir();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            boolean old = n.startsWith("backup-") && (n.endsWith(".txt") || n.endsWith(".txt.tmp"))
                    || n.equals("google.properties") || n.equals("google.properties.tmp")
                    || n.equals("amazon-cuentas.txt");
            if (old && f.isFile() && f.delete()) Log.i(TAG, "borrado " + n);
        }
    }
}
