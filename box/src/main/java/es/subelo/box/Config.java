package es.subelo.box;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

/** Ajustes del servidor guardados en el box. */
final class Config {
    static final int PORT = 8765;
    /** La web para el ordenador, con un certificado de la autoridad del box (ver Tls). */
    static final int WEB_PORT = 8766;

    private static Store cached;
    private static String cachedKey;

    private Config() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("subelo", Context.MODE_PRIVATE);
    }

    static boolean autostart(Context c) {
        return prefs(c).getBoolean("autostart", true);
    }

    static void setAutostart(Context c, boolean on) {
        prefs(c).edit().putBoolean("autostart", on).apply();
    }

    /** mode: "saf" (carpeta elegida con el selector de Android) o "file" (ruta directa). */
    static synchronized void setStore(Context c, String mode, String value) {
        prefs(c).edit().putString("mode", mode).putString("value", value).apply();
        cached = null;
        cachedKey = null;
    }

    static synchronized Store store(Context c) {
        String mode = prefs(c).getString("mode", null);
        String value = prefs(c).getString("value", null);
        if (mode == null || value == null) return null;
        String key = mode + "|" + value;
        if (key.equals(cachedKey) && cached != null) return cached;
        Store s = "saf".equals(mode)
                ? new SafStore(c.getApplicationContext(), Uri.parse(value))
                : new FileStore(new File(value));
        cached = s;
        cachedKey = key;
        return s;
    }
}
