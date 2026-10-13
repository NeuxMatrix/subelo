package es.subelo.movil;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Cliente del servidor Súbelo que corre en el box. Todo va por HTTPS.
 * El box usa un certificado propio: al emparejar se memoriza su huella (SHA-256) y a partir de ahí
 * solo se habla con un servidor que tenga exactamente ese certificado.
 */
final class BoxClient {

    /** Error devuelto por el box (sesión caducada, disco no disponible...). */
    static final class BoxException extends IOException {
        final int code;

        BoxException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final class RemoteFile {
        final String dir;
        final String name;
        final long size;
        final long mtime;
        /** En tus favoritos. */
        boolean fav;
        /** En la carpeta de favoritos: de qué carpeta es («» = la principal, «Julss: Viajes» = de otro). */
        String from;
        /** En la carpeta de favoritos: si es tuyo (lo de otro no se puede cambiar). */
        boolean own = true;

        RemoteFile(String dir, String name, long size, long mtime) {
            this.dir = dir;
            this.name = name;
            this.size = size;
            this.mtime = mtime;
        }
    }

    static final class Album {
        String name;
        int media, folders;
        RemoteFile cover;
        boolean virtual, shared;
        String owner, mode;
    }

    /** Carpeta especial con lo que el otro usuario te ha compartido (no existe en el disco). */
    static final String SHARED_WITH_ME = "👥 Compartido conmigo";

    /** Carpeta especial con tus favoritos: no copia nada, cada foto sigue en su carpeta. */
    static final String FAVORITES = "⭐ Favoritos";

    static final class Listing {
        final List<String> folders = new ArrayList<String>();
        final List<RemoteFile> files = new ArrayList<RemoteFile>();
        /** «own» (tuya), «ver», «subir» (compartida contigo), «virtual» (lo compartido) o «fav» (favoritos). */
        String access = "own";
        String owner;
        /** Con quién compartes esta carpeta: usuario → «ver» / «subir». */
        final java.util.Map<String, String> sharedWith = new java.util.LinkedHashMap<String, String>();

        boolean own() {
            return "own".equals(access);
        }

        boolean canAdd() {
            return own() || "subir".equals(access);
        }

        boolean fav() {
            return "fav".equals(access);
        }
    }

    interface Progress {
        void onProgress(long sent, long total);
    }

    /** El certificado del box no es el que se memorizó al emparejar. */
    static final class WrongBoxException extends IOException {
        WrongBoxException() {
            super("Este box no es el que emparejaste (su código ha cambiado). Si lo has reinstalado o "
                    + "es otro box, pulsa «Buscar el box» y vuelve a emparejar.");
        }
    }

    final String host;
    final int port;
    private final String base;
    private final String token;
    private final String expectedFp;
    private volatile String seenFp;
    private final SSLSocketFactory ssl;

    /** expectedFp == null: primera conexión (emparejamiento), se acepta y se anota la huella. */
    BoxClient(String host, int port, String token, String expectedFp) {
        this.host = host;
        this.port = port;
        this.base = "https://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        this.token = token == null ? "" : token;
        this.expectedFp = expectedFp;
        this.ssl = pinnedFactory();
    }

    static BoxClient fromPrefs(Context c) {
        String h = Prefs.host(c);
        return h == null ? null : new BoxClient(h, Prefs.port(c), Prefs.token(c), Prefs.certFp(c));
    }

    /** Huella del certificado que presentó el box en la última conexión. */
    String fingerprint() {
        return seenFp;
    }

    static String shortCode(String fp) {
        if (fp == null || fp.length() < 8) return "????-????";
        String s = fp.toUpperCase(Locale.US);
        return s.substring(0, 4) + "-" + s.substring(4, 8);
    }

    private SSLSocketFactory pinnedFactory() {
        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("no se usa");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Sin certificado");
                String fp;
                try {
                    byte[] d = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder sb = new StringBuilder();
                    for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
                    fp = sb.toString();
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
                seenFp = fp;
                if (expectedFp != null && !expectedFp.equalsIgnoreCase(fp)) {
                    throw new CertificateException("WRONG_BOX");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final HostnameVerifier ANY_HOST = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true; // la identidad la garantiza la huella del certificado, no el nombre
        }
    };

    private static IOException translate(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("WRONG_BOX")) return new WrongBoxException();
        }
        return e;
    }

    /**
     * Devuelve un cliente que responde y con una sesión válida. Si la IP guardada ya no vale
     * (el router le dio otra al box), lo vuelve a buscar en la red.
     */
    static BoxClient connect(Context c) throws IOException {
        BoxClient bc = fromPrefs(c);
        if (bc != null) {
            try {
                bc.checkAuth(bc.info());
                if (Prefs.certFp(c) == null && bc.fingerprint() != null) Prefs.setCertFp(c, bc.fingerprint());
                return bc;
            } catch (BoxException e) {
                throw e;
            } catch (WrongBoxException e) {
                // otra máquina en esa IP: buscamos el nuestro por la red
            } catch (IOException ignored) {
                // IP antigua o box apagado: probamos a buscarlo
            }
        }
        List<Discovery.Found> found = Discovery.discover(c, 3000);
        Discovery.Found pick = null;
        String name = Prefs.boxName(c);
        for (Discovery.Found f : found) {
            if (f.name.equals(name)) pick = f;
        }
        if (pick == null && found.size() == 1) pick = found.get(0);
        if (pick == null) {
            throw new IOException(bc == null
                    ? "Todavía no has elegido el box. Pulsa «Buscar el box»."
                    : "No encuentro el box en la red. ¿Estás en el Wi-Fi de casa y el box está encendido?");
        }
        bc = new BoxClient(pick.host, pick.port, Prefs.token(c), Prefs.certFp(c));
        bc.checkAuth(bc.info()); // si no es nuestro box, falla aquí por la huella
        Prefs.setBox(c, pick.name, pick.host, pick.port);
        if (Prefs.certFp(c) == null && bc.fingerprint() != null) Prefs.setCertFp(c, bc.fingerprint());
        return bc;
    }

    void checkAuth(JSONObject info) throws BoxException {
        if (info.isNull("user")) throw new BoxException(401, "Tienes que entrar con tu usuario");
    }

    /** Los dos usuarios del box y si ya tienen contraseña. */
    JSONObject users() throws IOException {
        return call("GET", "/api/users", 4000);
    }

    /** Entra (o, la primera vez, fija la contraseña). Devuelve {user, token, created}. */
    JSONObject login(String user, String password) throws IOException {
        HttpURLConnection c = open("POST", "/api/login", 20000);
        try {
            byte[] body = ("user=" + enc(user) + "&password=" + enc(password) + "&client=app").getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            return readJson(c);
        } catch (BoxException e) {
            throw e;
        } catch (IOException e) {
            throw translate(e);
        } finally {
            c.disconnect();
        }
    }

    void logout() throws IOException {
        call("POST", "/api/logout", 4000);
    }

    JSONObject info() throws IOException {
        return call("GET", "/api/info", 4000);
    }

    List<String> folders(String path) throws IOException {
        JSONObject o = call("GET", "/api/folders?path=" + enc(path), 15000);
        List<String> out = new ArrayList<String>();
        JSONArray a = o.optJSONArray("folders");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    Listing list(String path) throws IOException {
        JSONObject o = call("GET", "/api/list?path=" + enc(path), 30000);
        Listing l = new Listing();
        JSONArray a = o.optJSONArray("folders");
        if (a != null) for (int i = 0; i < a.length(); i++) l.folders.add(a.optString(i));
        l.access = o.optString("access", "own");
        l.owner = o.optString("owner", null);
        JSONObject sw = o.optJSONObject("sharedWith");
        if (sw != null) {
            for (java.util.Iterator<String> it = sw.keys(); it.hasNext(); ) {
                String k = it.next();
                l.sharedWith.put(k, sw.optString(k));
            }
        }
        JSONArray f = o.optJSONArray("files");
        if (f != null) {
            for (int i = 0; i < f.length(); i++) {
                JSONObject x = f.optJSONObject(i);
                RemoteFile rf = new RemoteFile(path, x.optString("name"), x.optLong("size", -1), x.optLong("mtime", 0));
                rf.fav = x.optBoolean("fav");
                rf.own = x.optBoolean("own", true);
                if (x.has("from")) rf.from = x.optString("from");
                l.files.add(rf);
            }
        }
        return l;
    }

    /** Subcarpetas con su portada y cuántas fotos tienen. */
    List<Album> albums(String path) throws IOException {
        JSONObject o = call("GET", "/api/albums?path=" + enc(path), 30000);
        List<Album> out = new ArrayList<Album>();
        JSONArray a = o.optJSONArray("albums");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            Album al = new Album();
            al.name = x.optString("name");
            al.media = x.optInt("media");
            al.folders = x.optInt("folders");
            al.virtual = x.optBoolean("virtual");
            al.shared = x.optJSONObject("sharedWith") != null && x.optJSONObject("sharedWith").length() > 0;
            al.owner = x.optString("owner", null);
            al.mode = x.optString("mode", null);
            JSONObject c = x.optJSONObject("cover");
            if (c != null) al.cover = new RemoteFile(c.optString("path"), c.optString("name"), c.optLong("size", -1), 0);
            out.add(al);
        }
        return out;
    }

    /** Borra ficheros o carpetas (con todo su contenido) de una carpeta. */
    void delete(String path, List<String> names) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        for (String n : names) f.add(new String[]{"name", n});
        form("/api/delete", f);
    }

    /** Mueve ficheros o carpetas de «path» a «to». */
    void move(String path, List<String> names, String to) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"to", to});
        for (String n : names) f.add(new String[]{"name", n});
        form("/api/move", f);
    }

    /** Marca (on) o quita de favoritos. En la carpeta de favoritos solo se puede quitar. */
    void fav(String path, List<String> names, boolean on) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"on", on ? "1" : "0"});
        for (String n : names) f.add(new String[]{"name", n});
        form("/api/fav", f);
    }

    /** Dónde se hizo: {lat, lon, manual} (sin lat si no se sabe). */
    JSONObject geoOf(String path, String name) throws IOException {
        return call("GET", "/api/geo?path=" + enc(path) + "&name=" + enc(name), 15000);
    }

    /**
     * Pone a mano dónde se hicieron fotos o carpetas enteras (el archivo no se toca: lo guarda el box para
     * el mapa). con: el otro usuario, para que salga también en su mapa, o null.
     */
    void geo(String path, List<String> names, double lat, double lon, String con) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"lat", String.valueOf(lat)});
        f.add(new String[]{"lon", String.valueOf(lon)});
        if (con != null && !con.isEmpty()) f.add(new String[]{"con", con});
        for (String n : names) f.add(new String[]{"name", n});
        form("/api/geo", f);
    }

    /** Quita la ubicación puesta a mano (vuelve la del GPS de la foto, si tenía). */
    void geoClear(String path, List<String> names) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"clear", "1"});
        for (String n : names) f.add(new String[]{"name", n});
        form("/api/geo", f);
    }

    void rename(String path, String name, String newName) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"name", name});
        f.add(new String[]{"new", newName});
        form("/api/rename", f);
    }

    private JSONObject form(String path, List<String[]> fields) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String[] kv : fields) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[0])).append('=').append(enc(kv[1]));
        }
        byte[] body = sb.toString().getBytes("UTF-8");
        HttpURLConnection c = open("POST", path, 120000);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            return readJson(c);
        } catch (BoxException e) {
            throw e;
        } catch (IOException e) {
            throw translate(e);
        } finally {
            c.disconnect();
        }
    }

    /** Comparte una carpeta tuya con el otro usuario («ver» o «subir»). */
    void share(String path, String with, String mode) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"with", with});
        f.add(new String[]{"mode", mode});
        form("/api/share", f);
    }

    void unshare(String path, String with) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        f.add(new String[]{"with", with});
        form("/api/unshare", f);
    }

    /** Los otros usuarios del box (con quién se puede compartir). */
    List<String> others() throws IOException {
        JSONObject o = call("GET", "/api/shares", 30000);
        List<String> out = new ArrayList<String>();
        JSONArray a = o.optJSONArray("others");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** JSON con las fotos que tienen ubicación (para el mapa). */
    String places() throws IOException {
        return call("GET", "/api/places", 60000).toString();
    }

    // ---------- Echo: el box sube la carpeta «Echo» a un álbum de Amazon Photos ----------

    JSONObject amazon() throws IOException {
        return newFeature("/api/amazon");
    }

    /** Estado de algo que el box tiene desde hace poco: un 404 quiere decir que el box no está actualizado. */
    private JSONObject newFeature(String path) throws IOException {
        try {
            return call("GET", path, 15000);
        } catch (BoxException e) {
            if (e.code != 404) throw e;
            throw new BoxException(404, "El box tiene una versión antigua. En la tele abre «Súbelo Servidor», "
                    + "pulsa «Buscar actualizaciones» y acepta la instalación.");
        }
    }

    /** Pasa al box la sesión de Amazon (cookies de amazon.es); devuelve el estado y los álbumes. */
    JSONObject amazonConnect(String cookie, String tld) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"cookie", cookie});
        f.add(new String[]{"tld", tld});
        return form("/api/amazon/connect", f);
    }

    JSONObject amazonAlbums() throws IOException {
        return call("GET", "/api/amazon/albums", 60000);
    }

    JSONObject amazonAlbum(String id, String title) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"id", id});
        f.add(new String[]{"title", title});
        return form("/api/amazon/album", f);
    }

    JSONObject amazonAlbumLink(String link) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"link", link});
        return form("/api/amazon/album", f);
    }

    JSONObject amazonSync() throws IOException {
        return form("/api/amazon/sync", new ArrayList<String[]>());
    }

    JSONObject amazonDisconnect() throws IOException {
        return form("/api/amazon/disconnect", new ArrayList<String[]>());
    }

    /** Copia fotos de una carpeta a la carpeta «Echo» (el box las añade al álbum). */
    JSONObject echo(String path, List<String> names) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"path", path});
        for (String n : names) f.add(new String[]{"name", n});
        return form("/api/echo", f);
    }

    // ---------- traer de Google Fotos: el box descarga lo que se elige en el selector de Google ----------

    JSONObject google() throws IOException {
        return newFeature("/api/google");
    }

    JSONObject googleSetup(String clientId, String secret) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"client", clientId});
        f.add(new String[]{"secret", secret});
        return form("/api/google/setup", f);
    }

    JSONObject googleReset() throws IOException {
        return form("/api/google/reset", new ArrayList<String[]>());
    }

    /** Pasa al box el código que dio Google (con su verificador PKCE); devuelve el estado con «pickerUri». */
    JSONObject googleStart(String code, String verifier, String redirect, String path) throws IOException {
        List<String[]> f = new ArrayList<String[]>();
        f.add(new String[]{"code", code});
        f.add(new String[]{"verifier", verifier});
        f.add(new String[]{"redirect", redirect});
        f.add(new String[]{"path", path});
        return form("/api/google/start", f);
    }

    JSONObject googleCancel() throws IOException {
        return form("/api/google/cancel", new ArrayList<String[]>());
    }

    JSONObject googleClear() throws IOException {
        return form("/api/google/clear", new ArrayList<String[]>());
    }

    /** Miniatura JPEG o null si el box no puede generarla. */
    byte[] thumb(String dir, String name) throws IOException {
        HttpURLConnection c = open("GET", "/api/thumb?path=" + enc(dir) + "&name=" + enc(name), 30000);
        try {
            if (c.getResponseCode() != 200) return null;
            return readBytes(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Abre la descarga de un fichero (con Range opcional) para el reproductor de vídeo. */
    HttpURLConnection openFile(String dir, String name, String range) throws IOException {
        HttpURLConnection c = open("GET", "/api/file?path=" + enc(dir) + "&name=" + enc(name), 60000);
        if (range != null) c.setRequestProperty("Range", range);
        try {
            c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            throw translate(e);
        }
        return c;
    }

    /** Descarga un fichero del box y lo escribe en out. */
    void download(String dir, String name, OutputStream out, Progress progress, AtomicBoolean cancel) throws IOException {
        HttpURLConnection c = open("GET", "/api/file?path=" + enc(dir) + "&name=" + enc(name), 60000);
        try {
            int code = c.getResponseCode();
            if (code != 200) readJson(c);
            long total = -1;
            try {
                total = Long.parseLong(c.getHeaderField("Content-Length"));
            } catch (Exception ignored) {
            }
            InputStream in = c.getInputStream();
            byte[] buf = new byte[128 * 1024];
            long got = 0, last = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancel != null && cancel.get()) throw new IOException("Cancelado");
                out.write(buf, 0, r);
                got += r;
                if (progress != null && got - last > 256 * 1024) {
                    progress.onProgress(got, total);
                    last = got;
                }
            }
            in.close();
            if (total >= 0 && got != total) throw new IOException("Descarga incompleta");
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) != -1) b.write(buf, 0, r);
        in.close();
        return b.toByteArray();
    }

    void mkdir(String path) throws IOException {
        call("POST", "/api/mkdir?path=" + enc(path), 15000);
    }

    /** Tamaño del fichero en el box o -1 si no está. */
    long exists(String dir, String name) throws IOException {
        JSONObject o = call("GET", "/api/exists?path=" + enc(dir) + "&name=" + enc(name), 15000);
        return o.optBoolean("exists") ? o.optLong("size", -1) : -1;
    }

    JSONObject upload(String dir, String name, long size, long mtime, InputStream in,
                      Progress progress, AtomicBoolean cancel) throws IOException {
        String q = "/api/upload?path=" + enc(dir) + "&name=" + enc(name) + "&mtime=" + mtime
                + (size >= 0 ? "&size=" + size : "");
        HttpURLConnection c = open("PUT", q, 120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        if (size >= 0) c.setFixedLengthStreamingMode(size);
        else c.setChunkedStreamingMode(64 * 1024);
        try {
            OutputStream os = c.getOutputStream();
            byte[] buf = new byte[128 * 1024];
            long sent = 0;
            long lastReport = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancel != null && cancel.get()) throw new IOException("Cancelado");
                os.write(buf, 0, r);
                sent += r;
                if (progress != null && sent - lastReport > 256 * 1024) {
                    progress.onProgress(sent, size);
                    lastReport = sent;
                }
            }
            os.close();
            return readJson(c);
        } catch (BoxException e) {
            throw e;
        } catch (IOException e) {
            throw translate(e);
        } finally {
            c.disconnect();
        }
    }

    private JSONObject call(String method, String pathAndQuery, int readTimeout) throws IOException {
        HttpURLConnection c = open(method, pathAndQuery, readTimeout);
        try {
            if (method.equals("POST")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            return readJson(c);
        } catch (BoxException e) {
            throw e;
        } catch (IOException e) {
            throw translate(e);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String method, String pathAndQuery, int readTimeout) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(base + pathAndQuery).openConnection();
        c.setSSLSocketFactory(ssl);
        c.setHostnameVerifier(ANY_HOST);
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        if (!token.isEmpty()) c.setRequestProperty("X-Subelo-Token", token);
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) b.write(buf, 0, r);
            in.close();
            body = b.toString("UTF-8");
        }
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (JSONException e) {
            if (code >= 400) throw new BoxException(code, "Error " + code + " del box");
            throw new IOException("Respuesta no válida del box");
        }
        // «No encontrado» solo lo dice el box cuando no conoce lo que se le pide: es más antiguo que el móvil
        if (code == 404 && "No encontrado".equals(o.optString("error"))) {
            throw new BoxException(404, "El box tiene una versión antigua y aún no sabe hacer esto. En la tele abre "
                    + "«Súbelo Servidor», pulsa «Buscar actualizaciones» e instala la nueva.");
        }
        if (code >= 400) throw new BoxException(code, o.optString("error", "Error " + code + " del box"));
        return o;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
