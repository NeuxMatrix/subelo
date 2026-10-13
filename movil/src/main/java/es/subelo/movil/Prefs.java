package es.subelo.movil;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes guardados en el móvil. */
final class Prefs {
    static final int DEFAULT_PORT = 8765;

    private Prefs() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("subelo", Context.MODE_PRIVATE);
    }

    static String host(Context c) { return p(c).getString("host", null); }
    static int port(Context c) { return p(c).getInt("port", DEFAULT_PORT); }
    static String boxName(Context c) { return p(c).getString("boxName", null); }
    /** Usuario con el que se ha entrado («Fer» o «Julss») y su sesión en el box. */
    static String user(Context c) { return p(c).getString("user", null); }
    static String token(Context c) { return p(c).getString("token", ""); }
    static boolean loggedIn(Context c) { return user(c) != null && !token(c).isEmpty(); }
    static void setLogin(Context c, String user, String token) {
        p(c).edit().putString("user", user).putString("token", token).remove("pin").apply();
    }
    static void logout(Context c) {
        p(c).edit().remove("user").remove("token").apply();
        AmazonSession.forgetPassword(c); // se vuelve a guardar al entrar con la contraseña
    }

    /** Nuevo emparejamiento: se olvida la huella anterior. */
    static void pairNew(Context c, String name, String host, int port) {
        p(c).edit().putString("boxName", name).putString("host", host).putInt("port", port).remove("certFp").apply();
    }

    static void setBox(Context c, String name, String host, int port) {
        p(c).edit().putString("boxName", name).putString("host", host).putInt("port", port).apply();
    }

    /** Huella del certificado del box emparejado (null = aún sin emparejar). */
    static String certFp(Context c) { return p(c).getString("certFp", null); }
    static void setCertFp(Context c, String fp) { p(c).edit().putString("certFp", fp).apply(); }

    /** Carpeta de destino dentro del disco (relativa, "" = carpeta principal). */
    static String dest(Context c) { return p(c).getString("dest", ""); }
    static void setDest(Context c, String d) { p(c).edit().putString("dest", d).apply(); }

    static boolean auto(Context c) { return p(c).getBoolean("auto", false); }
    static void setAuto(Context c, boolean v) { p(c).edit().putBoolean("auto", v).apply(); }

    static boolean onlyCamera(Context c) { return p(c).getBoolean("onlyCamera", true); }
    static void setOnlyCamera(Context c, boolean v) { p(c).edit().putBoolean("onlyCamera", v).apply(); }

    static boolean videos(Context c) { return p(c).getBoolean("videos", true); }
    static void setVideos(Context c, boolean v) { p(c).edit().putBoolean("videos", v).apply(); }

    static boolean organize(Context c) { return p(c).getBoolean("organize", false); }
    static void setOrganize(Context c, boolean v) { p(c).edit().putBoolean("organize", v).apply(); }

    /** Marca de por dónde va la subida automática (fecha de alta en segundos + id de MediaStore). */
    static long wmDate(Context c) { return p(c).getLong("wmDate", 0); }
    static long wmId(Context c) { return p(c).getLong("wmId", 0); }
    static void setWatermark(Context c, long date, long id) {
        p(c).edit().putLong("wmDate", date).putLong("wmId", id).apply();
    }

    static String lastAuto(Context c) { return p(c).getString("lastAuto", ""); }
    static void setLastAuto(Context c, String s) { p(c).edit().putString("lastAuto", s).apply(); }
}
