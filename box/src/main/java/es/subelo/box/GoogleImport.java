package es.subelo.box;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Traer fotos de Google Fotos con el selector oficial de Google (Photos Picker API): el usuario
 * elige en Google Fotos qué fotos quiere y el box las descarga a la carpeta que dijo.
 *
 * El móvil consigue el permiso de Google (en el navegador) y le pasa al box el código; el box lo
 * canjea con el «cliente OAuth» que el usuario creó en Google Cloud (guardado en la memoria interna
 * del box). El permiso solo sirve para las fotos elegidas y solo vive en memoria mientras dura la
 * copia: no se guarda en ningún sitio.
 */
final class GoogleImport {
    private static final String TAG = "SubeloGoogle";
    static final String SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";
    /** Las direcciones de las fotos caducan a los 60 minutos: se piden otra vez antes. */
    private static final long RELIST_MS = 45 * 60 * 1000L;

    /** Una importación: de elegir en Google Fotos a tener las fotos en el disco. */
    static final class Job {
        final String user, owner, destRel, dest;
        volatile String state = "picking"; // picking, downloading, done, cancelled, error
        volatile String pickerUri, sessionId, current, error;
        volatile int total, done, copied, already, failed;
        volatile boolean cancel;
        final long started = System.currentTimeMillis();
        volatile long finished;
        String token, refresh, client, secret;
        long tokenUntil;
        Thread thread;

        Job(String user, String owner, String destRel, String dest) {
            this.user = user;
            this.owner = owner;
            this.destRel = destRel;
            this.dest = dest;
        }

        boolean active() {
            return state.equals("picking") || state.equals("downloading");
        }
    }

    static final class GoogleException extends IOException {
        final int code;

        GoogleException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static final Map<String, Job> JOBS = new HashMap<String, Job>();

    private GoogleImport() {}

    private static String tokenUrl() {
        return System.getProperty("subelo.google.token", "https://oauth2.googleapis.com/token"); // propiedad: pruebas
    }

    private static String picker() {
        return System.getProperty("subelo.google.picker", "https://photospicker.googleapis.com/v1");
    }

    // ---------- cliente OAuth (uno para todo el box) ----------

    private static File configFile(Context c) {
        return new File(c.getFilesDir(), "google.properties");
    }

    static synchronized Properties config(Context c) {
        Properties p = new Properties();
        File f = configFile(c);
        if (f.isFile()) {
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "config", e);
            }
        }
        return p;
    }

    static synchronized void setup(Context c, String clientId, String secret) throws IOException {
        clientId = clientId == null ? "" : clientId.trim();
        secret = secret == null ? "" : secret.trim();
        if (!clientId.matches("[0-9A-Za-z._-]{5,200}\\.apps\\.googleusercontent\\.com")) {
            throw new GoogleException(400, "El ID de cliente acaba en «.apps.googleusercontent.com»: cópialo entero");
        }
        if (!secret.matches("[0-9A-Za-z_-]{8,200}")) {
            throw new GoogleException(400, "Falta el secreto de cliente (empieza por «GOCSPX-»)");
        }
        Properties p = new Properties();
        p.setProperty("client", clientId);
        p.setProperty("secret", secret);
        File f = configFile(c), tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            p.store(out, "Cliente OAuth de Google Fotos (Picker API)");
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar la configuración de Google");
    }

    static synchronized void reset(Context c) {
        //noinspection ResultOfMethodCallIgnored
        configFile(c).delete();
    }

    // ---------- estado ----------

    static JSONObject statusJson(Context c, String user) throws JSONException {
        Properties p = config(c);
        JSONObject o = new JSONObject();
        String client = p.getProperty("client");
        o.put("configured", client != null && p.getProperty("secret") != null);
        o.put("clientId", client == null ? JSONObject.NULL : client);
        o.put("scope", SCOPE);
        Job j;
        synchronized (JOBS) {
            j = JOBS.get(user);
        }
        if (j == null) {
            o.put("job", JSONObject.NULL);
            return o;
        }
        JSONObject jo = new JSONObject();
        jo.put("state", j.state);
        jo.put("dest", j.dest);
        jo.put("pickerUri", j.state.equals("picking") && j.pickerUri != null ? j.pickerUri : JSONObject.NULL);
        jo.put("total", j.total);
        jo.put("done", j.done);
        jo.put("copied", j.copied);
        jo.put("already", j.already);
        jo.put("failed", j.failed);
        jo.put("current", j.current == null ? JSONObject.NULL : j.current);
        jo.put("error", j.error == null ? JSONObject.NULL : j.error);
        jo.put("started", j.started);
        jo.put("finished", j.finished);
        o.put("job", jo);
        return o;
    }

    // ---------- empezar, cancelar ----------

    /**
     * Canjea el código que consiguió el móvil, abre una sesión del selector de Google Fotos y deja
     * al box esperando a que el usuario elija. Devuelve la importación (con la dirección del selector).
     */
    static Job start(final Context c, String user, String owner, String destRel, String dest,
                     String code, String verifier, String redirect) throws IOException, JSONException {
        Properties p = config(c);
        String client = p.getProperty("client"), secret = p.getProperty("secret");
        if (client == null || secret == null) throw new GoogleException(409, "Falta configurar Google en Súbelo");
        cancel(user);

        Map<String, String> f = new HashMap<String, String>();
        f.put("code", code);
        f.put("client_id", client);
        f.put("client_secret", secret);
        f.put("redirect_uri", redirect);
        f.put("grant_type", "authorization_code");
        f.put("code_verifier", verifier);
        JSONObject t = tokenCall(f);
        String scope = t.optString("scope", SCOPE);
        if (!scope.contains(SCOPE)) throw new GoogleException(403, "No diste permiso para elegir fotos de Google Fotos");

        final Job j = new Job(user, owner, destRel, dest);
        j.client = client;
        j.secret = secret;
        setToken(j, t);
        JSONObject s = call(j, "POST", picker() + "/sessions", "{}");
        j.sessionId = s.optString("id", null);
        j.pickerUri = s.optString("pickerUri", null);
        if (j.sessionId == null || j.pickerUri == null) throw new GoogleException(502, "Google no ha abierto el selector de fotos");
        final JSONObject first = s;
        synchronized (JOBS) {
            JOBS.put(user, j);
        }
        j.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work(c, j, first);
                } catch (Throwable e) {
                    Log.w(TAG, "import " + j.user, e);
                    if (j.active()) {
                        j.error = e.getMessage() == null ? e.toString() : e.getMessage();
                        j.state = "error";
                    }
                } finally {
                    if (j.state.equals("downloading") || j.state.equals("picking")) j.state = j.cancel ? "cancelled" : "error";
                    j.finished = System.currentTimeMillis();
                    j.current = null;
                    closeSession(j);
                }
            }
        }, "subelo-google");
        j.thread.setDaemon(true);
        j.thread.start();
        return j;
    }

    /** Para la importación en curso (lo ya copiado se queda). */
    static void cancel(String user) {
        Job j;
        synchronized (JOBS) {
            j = JOBS.get(user);
        }
        if (j == null || !j.active()) return;
        j.cancel = true;
        synchronized (j) {
            j.notifyAll();
        }
    }

    /** Olvida una importación terminada (para que el móvil no la vuelva a enseñar). */
    static void clear(String user) {
        synchronized (JOBS) {
            Job j = JOBS.get(user);
            if (j != null && !j.active()) JOBS.remove(user);
        }
    }

    // ---------- el trabajo ----------

    private static void work(Context c, Job j, JSONObject session) throws IOException, JSONException {
        // 1. esperar a que elija en Google Fotos
        long interval = duration(session.optJSONObject("pollingConfig"), "pollInterval", 3000);
        long deadline = System.currentTimeMillis() + duration(session.optJSONObject("pollingConfig"), "timeoutIn", 30 * 60 * 1000L);
        while (!session.optBoolean("mediaItemsSet")) {
            if (j.cancel) return;
            if (System.currentTimeMillis() > deadline) {
                j.error = "No has elegido las fotos a tiempo. Vuelve a empezar.";
                j.state = "error";
                return;
            }
            pause(j, Math.max(1000, Math.min(interval, 30000)));
            if (j.cancel) return;
            session = call(j, "GET", picker() + "/sessions/" + enc(j.sessionId), null);
            JSONObject pc = session.optJSONObject("pollingConfig");
            if (pc != null) {
                interval = duration(pc, "pollInterval", interval);
                if (pc.has("timeoutIn")) deadline = System.currentTimeMillis() + duration(pc, "timeoutIn", 0);
            }
        }
        // 2. qué ha elegido
        j.state = "downloading";
        List<JSONObject> items = list(j);
        long listedAt = System.currentTimeMillis();
        j.total = items.size();
        Store st = Config.store(c);
        if (st == null || !st.available()) throw new IOException("El disco USB no está disponible");
        Object dir = st.dir(j.destRel, true);
        if (dir == null) throw new IOException("No se pudo crear la carpeta " + j.dest);
        // 3. descargar una a una
        byte[] buf = new byte[256 * 1024];
        for (int i = 0; i < items.size() && !j.cancel; i++) {
            JSONObject it = items.get(i);
            if (System.currentTimeMillis() - listedAt > RELIST_MS) { // direcciones a punto de caducar
                Map<String, JSONObject> fresh = new HashMap<String, JSONObject>();
                for (JSONObject x : list(j)) fresh.put(x.optString("id"), x);
                listedAt = System.currentTimeMillis();
                JSONObject x = fresh.get(it.optString("id"));
                if (x != null) it = x;
            }
            String name = fileName(it);
            j.current = name;
            try {
                if (fetch(st, dir, j, it, name, buf)) j.copied++;
                else j.already++;
            } catch (GoogleException e) {
                if (e.code == 401) throw new IOException("Google ha retirado el permiso a mitad. Vuelve a elegir las que faltan: las ya copiadas se saltan.");
                j.failed++;
                if (j.error == null) j.error = name + ": " + e.getMessage();
            } catch (IOException e) {
                if (j.cancel) break;
                if (!st.available()) throw e;
                j.failed++;
                if (j.error == null) j.error = name + ": " + e.getMessage();
            }
            j.done = i + 1;
        }
        j.current = null;
        if (j.cancel) return;
        j.state = "done";
        if (j.destRel.equals(j.owner + "/" + EchoSync.FOLDER)) EchoSync.kick(c, j.owner);
    }

    /** Descarga una foto o vídeo a la carpeta. false si ya estaba (mismo nombre y tamaño). */
    private static boolean fetch(Store st, Object dir, Job j, JSONObject it, String name, byte[] buf) throws IOException {
        JSONObject mf = it.optJSONObject("mediaFile");
        String base = mf == null ? null : mf.optString("baseUrl", null);
        if (base == null) throw new GoogleException(502, "Google no da la dirección del archivo");
        boolean video = "VIDEO".equals(it.optString("type"));
        if (video) {
            JSONObject md = mf.optJSONObject("mediaFileMetadata");
            JSONObject vm = md == null ? null : md.optJSONObject("videoMetadata");
            String ps = vm == null ? null : vm.optString("processingStatus", null);
            if (ps != null && !ps.equals("READY")) throw new GoogleException(409, "Google aún está preparando el vídeo");
        }
        String url = base + (video ? "=dv" : "=d");
        HttpURLConnection con = open("GET", url, j);
        try {
            int code = con.getResponseCode();
            if (code == 401) { // permiso caducado: se renueva una vez
                con.disconnect();
                refresh(j);
                con = open("GET", url, j);
                code = con.getResponseCode();
            }
            if (code != 200) throw new GoogleException(code, "Google respondió " + code);
            long expected = con.getContentLength();
            Store.Upload up = st.begin(dir, name);
            boolean ok = false;
            try {
                OutputStream os = up.out();
                InputStream in = con.getInputStream();
                long n = 0;
                int r;
                try {
                    while ((r = in.read(buf)) != -1) {
                        if (j.cancel) throw new IOException("Cancelado");
                        os.write(buf, 0, r);
                        n += r;
                    }
                } finally {
                    in.close();
                }
                os.close();
                if (expected >= 0 && n != expected) throw new IOException("Descarga incompleta");
                Store.Committed cm = up.commit(createTime(it.optString("createTime", null)), n);
                ok = true;
                return !cm.duplicate;
            } finally {
                if (!ok) up.abort();
            }
        } finally {
            con.disconnect();
        }
    }

    private static List<JSONObject> list(Job j) throws IOException, JSONException {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String page = null;
        do {
            String url = picker() + "/mediaItems?sessionId=" + enc(j.sessionId) + "&pageSize=100"
                    + (page == null ? "" : "&pageToken=" + enc(page));
            JSONObject o = call(j, "GET", url, null);
            JSONArray a = o.optJSONArray("mediaItems");
            if (a != null) for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.getJSONObject(i));
            page = o.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && !j.cancel);
        return out;
    }

    /** Nombre del archivo en el disco: el de Google, limpio; si no hay, uno a partir del id. */
    static String fileName(JSONObject it) {
        JSONObject mf = it.optJSONObject("mediaFile");
        String n = mf == null ? null : HttpServer.cleanName(mf.optString("filename", ""));
        if (n != null && n.length() <= 200) return n;
        String mime = mf == null ? "" : mf.optString("mimeType", "");
        String ext = mime.equals("image/png") ? ".png" : mime.equals("image/heic") ? ".heic" : mime.equals("image/gif") ? ".gif"
                : mime.equals("image/webp") ? ".webp" : mime.startsWith("video/") ? (mime.equals("video/quicktime") ? ".mov" : ".mp4") : ".jpg";
        String id = it.optString("id", "foto").replaceAll("[^A-Za-z0-9_-]", "");
        if (id.length() > 40) id = id.substring(id.length() - 40);
        return "Google_" + id + ext;
    }

    /** «2024-07-11T18:22:33.123Z» → milisegundos (0 si no se entiende). */
    static long createTime(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long t = f.parse(s.substring(0, 19)).getTime();
            int i = 19;
            if (i < s.length() && s.charAt(i) == '.') {
                int k = i + 1;
                while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                String frac = (s.substring(i + 1, k) + "000").substring(0, 3);
                t += Integer.parseInt(frac);
                i = k;
            }
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-') && s.length() >= i + 6) {
                int mins = Integer.parseInt(s.substring(i + 1, i + 3)) * 60 + Integer.parseInt(s.substring(i + 4, i + 6));
                t -= (s.charAt(i) == '+' ? 1 : -1) * mins * 60000L;
            }
            return t;
        } catch (ParseException | NumberFormatException e) {
            return 0;
        }
    }

    /** «3.5s» → 3500 ms. */
    static long duration(JSONObject o, String key, long def) {
        String s = o == null ? null : o.optString(key, null);
        if (s == null || !s.endsWith("s")) return def;
        try {
            return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void pause(Job j, long ms) {
        synchronized (j) {
            try {
                j.wait(ms);
            } catch (InterruptedException ignored) {
                j.cancel = true;
            }
        }
    }

    /** Cierra la sesión del selector en Google y olvida el permiso. */
    private static void closeSession(Job j) {
        try {
            if (j.sessionId != null && j.token != null) call(j, "DELETE", picker() + "/sessions/" + enc(j.sessionId), null);
        } catch (Exception e) {
            Log.w(TAG, "delete session", e);
        }
        j.token = null;
        j.refresh = null;
        j.secret = null;
    }

    // ---------- HTTP con Google ----------

    private static void setToken(Job j, JSONObject t) throws GoogleException {
        String at = t.optString("access_token", null);
        if (at == null) throw new GoogleException(502, "Google no ha dado permiso");
        j.token = at;
        if (t.has("refresh_token")) j.refresh = t.optString("refresh_token");
        j.tokenUntil = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L;
    }

    private static void refresh(Job j) throws IOException {
        if (j.refresh == null) throw new GoogleException(401, "El permiso de Google ha caducado");
        Map<String, String> f = new HashMap<String, String>();
        f.put("client_id", j.client);
        f.put("client_secret", j.secret);
        f.put("refresh_token", j.refresh);
        f.put("grant_type", "refresh_token");
        try {
            setToken(j, tokenCall(f));
        } catch (JSONException e) {
            throw new GoogleException(502, "Google respondió algo raro al renovar el permiso");
        }
    }

    private static JSONObject tokenCall(Map<String, String> fields) throws IOException, JSONException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        HttpURLConnection con = (HttpURLConnection) new URL(tokenUrl()).openConnection();
        try {
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = sb.toString().getBytes("UTF-8");
            con.setFixedLengthStreamingMode(body.length);
            OutputStream os = con.getOutputStream();
            os.write(body);
            os.close();
            int code = con.getResponseCode();
            String txt = read(code >= 400 ? con.getErrorStream() : con.getInputStream());
            JSONObject o = txt.isEmpty() ? new JSONObject() : new JSONObject(txt);
            if (code != 200) {
                String err = o.optString("error", "");
                String msg = err.equals("invalid_client") || err.equals("unauthorized_client")
                        ? "Google no reconoce el cliente de Súbelo: revisa el ID y el secreto"
                        : err.equals("invalid_grant") ? "El permiso de Google ya no vale: vuelve a empezar"
                        : err.equals("redirect_uri_mismatch") ? "El cliente de Google tiene que ser de tipo «Aplicación de escritorio»"
                        : "Google no ha dado permiso (" + (err.isEmpty() ? code : err) + ")";
                throw new GoogleException(code == 401 ? 403 : code, msg);
            }
            return o;
        } finally {
            con.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String url, Job j) throws IOException {
        if (j.refresh != null && System.currentTimeMillis() > j.tokenUntil - 120000) {
            refresh(j);
        }
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        con.setRequestMethod(method);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("Authorization", "Bearer " + j.token);
        return con;
    }

    private static JSONObject call(Job j, String method, String url, String json) throws IOException, JSONException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection con = open(method, url, j);
            try {
                if (json != null) {
                    byte[] body = json.getBytes("UTF-8");
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    con.setFixedLengthStreamingMode(body.length);
                    OutputStream os = con.getOutputStream();
                    os.write(body);
                    os.close();
                }
                int code = con.getResponseCode();
                if (code == 401 && attempt == 0 && j.refresh != null) {
                    refresh(j);
                    continue;
                }
                String txt = read(code >= 400 ? con.getErrorStream() : con.getInputStream());
                if (code < 200 || code >= 300) {
                    String msg = null;
                    try {
                        JSONObject e = new JSONObject(txt).optJSONObject("error");
                        if (e != null) msg = e.optString("message", null);
                    } catch (JSONException ignored) {
                    }
                    if (code == 403 && msg != null && msg.contains("has not been used")) {
                        msg = "Falta habilitar «Google Photos Picker API» en tu proyecto de Google Cloud";
                    }
                    throw new GoogleException(code, msg == null ? "Google respondió " + code : msg);
                }
                return txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
            } finally {
                con.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) != -1) bo.write(b, 0, r);
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
