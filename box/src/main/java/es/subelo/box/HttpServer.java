package es.subelo.box;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.lang.reflect.Method;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Servidor HTTP mínimo para la red de casa.
 *
 * GET  /                     página web para subir desde cualquier navegador
 * GET  /api/info             estado del box (sin sesión; dice con qué usuario se ha entrado, si se ha entrado)
 * GET  /api/users            los dos usuarios y si ya tienen contraseña (sin sesión)
 * GET  /api/folders?path=    subcarpetas de una carpeta
 * POST /api/mkdir?path=      crea una carpeta
 * GET  /api/exists?path=&name=   ¿existe ya el fichero? devuelve su tamaño
 * PUT  /api/upload?path=&name=&size=&mtime=   cuerpo = contenido del fichero
 * GET  /api/list?path=       subcarpetas y ficheros de una carpeta
 * GET  /api/file?path=&name=[&download=1]   descarga (admite Range, para ver vídeos)
 * GET  /api/thumb?path=&name=   miniatura JPEG
 * GET|POST /api/zip?path=[&name=...]   descarga varios ficheros (o la carpeta entera) en un .zip
 *
 * POST /api/login           user=&password=[&client=app]: entra (la primera vez, fija la contraseña).
 *                            La web recibe una cookie de sesión (solo en memoria: caduca a los 30 min sin usarse,
 *                            a las 12 h o al cerrar el navegador); la app, un token para la cabecera X-Subelo-Token.
 * POST /api/logout           cierra la sesión
 * POST /api/password         password=&newPassword=[&phones=1]: cambia la tuya (cierra tus otras sesiones de la web;
 *                            con phones=1, también las del móvil)
 * GET  /api/box              modelo, versión, disco y si hay una versión nueva del box (para los ajustes de la web)
 * GET  /subelo-certificado.crt   la autoridad del box, para instalarla en el ordenador (sin sesión: es pública)
 * POST /api/fav              path=&name=…&on=1|0: marca o quita favoritos. Se ven en la carpeta especial
 *                            «⭐ Favoritos» (path=⭐ Favoritos), que solo apunta a los archivos: no los copia,
 *                            y ahí «borrar» solo los quita de favoritos.
 * GET  /api/geo              path=&name=: dónde se hizo ({lat, lon, manual}; sin lat si no se sabe)
 * POST /api/geo              path=&name=…&lat=&lon= (o clear=1): pone a mano dónde se hizo (solo lo tuyo). No
 *                            toca el archivo: se guarda en el box y el mapa la usa en lugar del GPS de la foto.
 * POST /api/echo             path=&name=…: copia esas fotos a la carpeta «Echo» (va al álbum de Amazon Photos)
 * GET  /api/amazon           estado del puente con Amazon Photos (EchoSync)
 * POST /api/amazon/connect   cookie=&tld=: guarda la sesión de Amazon (entrada desde el móvil); devuelve álbumes
 * GET  /api/amazon/albums    álbumes de Amazon Photos
 * POST /api/amazon/album     id=&title= o link=: el álbum del Echo
 * POST /api/amazon/sync      repasar ya;  POST /api/amazon/disconnect: olvidar la sesión de Amazon
 * GET  /api/google           traer de Google Fotos (GoogleImport): ¿configurado?, clientId y la importación en curso
 * POST /api/google/setup     client=&secret=: el cliente OAuth de Google Cloud;  POST /api/google/reset: olvidarlo
 * POST /api/google/start     code=&verifier=&redirect=&path=: canjea el permiso y abre el selector de Google Fotos
 * POST /api/google/cancel    parar;  POST /api/google/clear: olvidar la importación terminada
 * POST /api/google/auth      path=: (web) dirección de Google para dar permiso;  POST /api/google/code url=: la de vuelta
 *
 * Todo va cifrado con HTTPS (certificado propio del box, ver Tls.java). Si alguien escribe la
 * dirección con http://, se le redirige a https://. En Config.WEB_PORT se sirve lo mismo con un
 * certificado de la autoridad del box, que se puede instalar en el ordenador para usar la web como app.
 * Cada usuario solo ve su carpeta del disco (Fer/ o Julss/): las rutas de la API son relativas a ella.
 * Tras 5 contraseñas incorrectas desde un aparato, ese aparato queda bloqueado 5 minutos.
 */
final class HttpServer {
    private static final String TAG = "SubeloHttp";
    private static final String ISO = "ISO-8859-1";
    private static final String UTF8 = "UTF-8";

    private final Context ctx;
    private final int port;
    private ServerSocket server, webServer;
    private SSLSocketFactory sslSockets; // != null: puerto mixto (https + redirección desde http)
    private volatile SSLSocketFactory webSockets; // puerto del ordenador (certificado de la autoridad del box)
    private volatile long webChecked;
    private Method wrapTls; // SSLSocketFactory.createSocket(Socket, InputStream, boolean), Android 7+
    private ExecutorService pool;
    private volatile boolean running;

    private static final long LOCK_MS = 5 * 60 * 1000L;
    private final Map<String, long[]> fails = new HashMap<String, long[]>(); // ip -> {fallos, bloqueadoHasta}
    private final ArrayDeque<Long> recentFails = new ArrayDeque<Long>();

    HttpServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    synchronized void start() throws IOException {
        if (running) return;
        Users.forgetOldWebSessions(ctx);
        EchoSync.start(ctx);
        SSLContext tls = Tls.context(ctx.getFilesDir());
        try {
            wrapTls = SSLSocketFactory.class.getMethod("createSocket", Socket.class, InputStream.class, boolean.class);
        } catch (NoSuchMethodException e) {
            wrapTls = null;
        }
        if (wrapTls != null) {
            // socket normal: miramos el primer byte para distinguir https de http
            sslSockets = tls.getSocketFactory();
            server = new ServerSocket();
        } else {
            server = tls.getServerSocketFactory().createServerSocket();
        }
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        pool = Executors.newFixedThreadPool(16);
        running = true;
        startLoop(server, false);
        if (wrapTls != null) {
            // el ordenador: otro puerto con un certificado que se puede instalar (para usarlo como app)
            try {
                webSockets = Tls.webContext(ctx.getFilesDir()).getSocketFactory();
                webChecked = System.currentTimeMillis();
                webServer = new ServerSocket();
                webServer.setReuseAddress(true);
                webServer.bind(new InetSocketAddress(port + (Config.WEB_PORT - Config.PORT)));
                startLoop(webServer, true);
            } catch (IOException e) {
                Log.w(TAG, "puerto del ordenador", e);
                webServer = null;
            }
        }
    }

    private void startLoop(final ServerSocket ss, final boolean web) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(ss, web);
            }
        }, web ? "subelo-accept-web" : "subelo-accept").start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (webServer != null) webServer.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop(ServerSocket ss, final boolean web) {
        while (running) {
            try {
                final Socket s = ss.accept();
                s.setSoTimeout(120000);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(s, web);
                    }
                });
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "accept", e);
            }
        }
    }

    /** Para el puerto del ordenador: si el box ha cambiado de IP, firma otro certificado (como mucho cada 30 s). */
    private SSLSocketFactory webSockets() throws IOException {
        long now = System.currentTimeMillis();
        if (now - webChecked > 30000) {
            webChecked = now;
            webSockets = Tls.webContext(ctx.getFilesDir()).getSocketFactory();
        }
        return webSockets;
    }

    private void handle(Socket raw, boolean web) {
        if (sslSockets == null) {
            serve(raw);
            return;
        }
        Socket tls;
        try {
            InputStream in = raw.getInputStream();
            int first = in.read();
            if (first < 0) {
                raw.close();
                return;
            }
            if (first != 0x16) { // no es un saludo TLS: alguien ha escrito http://
                redirectToHttps(first, in, raw.getOutputStream());
                raw.close();
                return;
            }
            tls = (Socket) wrapTls.invoke(web ? webSockets() : sslSockets, raw, new ByteArrayInputStream(new byte[]{(byte) first}), true);
        } catch (Exception e) {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
            return;
        }
        serve(tls);
    }

    private static void redirectToHttps(int first, InputStream in, OutputStream out) throws IOException {
        InputStream all = new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte) first}), in);
        String host = null, h;
        readLine(all);
        int n = 0;
        while ((h = readLine(all)) != null && !h.isEmpty() && n++ < 100) {
            if (h.toLowerCase(Locale.US).startsWith("host:")) host = h.substring(5).trim();
        }
        if (host == null || !host.matches("[A-Za-z0-9.:\\[\\]-]{1,100}")) {
            String u = Net.mainUrl();
            host = u == null ? "" : u.replace("https://", "");
        }
        String loc = "https://" + host + "/";
        byte[] body = ("<a href=\"" + loc + "\">" + loc + "</a>").getBytes(UTF8);
        out.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: " + loc + "\r\nContent-Type: text/html\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(ISO));
        out.write(body);
        out.flush();
    }

    private void serve(Socket s) {
        String ip = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        try {
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                error(out, 400, "Petición no válida");
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];
            Map<String, String> headers = new HashMap<String, String>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.US), h.substring(i + 1).trim());
            }
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = parseQuery(query);
            if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(ISO));
                out.flush();
            }
            InputStream body;
            long contentLength = -1;
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = new ChunkedInputStream(in);
            } else {
                contentLength = parseLong(headers.get("content-length"), 0);
                body = new LimitedInputStream(in, contentLength);
            }
            try {
                route(ip, method, path, params, headers, body, contentLength, out);
            } catch (IOException e) {
                Log.w(TAG, "error en " + path, e);
                try {
                    error(out, 500, e.getMessage() != null ? e.getMessage() : "Error de entrada/salida");
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "handle", e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(String ip, String method, String path, Map<String, String> params, Map<String, String> headers,
                       InputStream body, long contentLength, OutputStream out) throws IOException, JSONException {
        if (path.equals("/") || path.equals("/index.html")) {
            send(out, 200, "text/html; charset=utf-8", readAsset("index.html"));
            return;
        }
        if (path.equals("/mapa.html")) {
            send(out, 200, "text/html; charset=utf-8", readAsset("mapa.html"));
            return;
        }
        if (path.equals("/manifest.webmanifest")) { // para instalar la web como app en el ordenador
            send(out, 200, "application/manifest+json; charset=utf-8", ("{\"name\":\"Súbelo\",\"short_name\":\"Súbelo\","
                    + "\"start_url\":\"/\",\"scope\":\"/\",\"display\":\"standalone\",\"background_color\":\"#111111\","
                    + "\"theme_color\":\"#111111\",\"icons\":[{\"src\":\"/static/logo.png\",\"sizes\":\"256x256\","
                    + "\"type\":\"image/png\",\"purpose\":\"any\"}]}").getBytes(UTF8));
            return;
        }
        if (path.equals("/subelo-certificado.crt")) { // la autoridad del box, para instalarla en el ordenador
            byte[] der = Tls.caCert(ctx.getFilesDir());
            sendHead(out, 200, "application/x-x509-ca-cert", der.length,
                    "Content-Disposition: attachment; filename=\"Subelo-certificado.crt\"\r\nCache-Control: no-store\r\n");
            out.write(der);
            out.flush();
            return;
        }
        if (path.startsWith("/static/")) {
            String n = path.substring(8);
            String asset = n.equals("logo.png") ? "logo.png"
                    : n.matches("[A-Za-z-]+\\.ttf") ? "fonts/" + n
                    : n.matches("(leaflet|markercluster)\\.(js|css)") ? "mapa/" + n : null;
            byte[] data = null;
            if (asset != null) {
                try {
                    data = readAsset(asset);
                } catch (IOException ignored) {
                }
            }
            if (data == null) {
                error(out, 404, "No encontrado");
                return;
            }
            sendHead(out, 200, asset.endsWith(".png") ? "image/png" : asset.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : asset.endsWith(".css") ? "text/css; charset=utf-8" : "font/ttf", data.length,
                    "Cache-Control: max-age=2592000\r\n");
            out.write(data);
            out.flush();
            return;
        }
        if (!path.startsWith("/api/")) {
            error(out, 404, "No encontrado");
            return;
        }
        List<String> names = new ArrayList<String>();
        String password = null, newPassword = null;
        params.remove("password"); // nunca en la URL
        params.remove("newPassword");
        String ctype = headers.get("content-type");
        if (method.equals("POST") && ctype != null && ctype.startsWith("application/x-www-form-urlencoded")) {
            String form = new String(readAll(body, 4 * 1024 * 1024), UTF8);
            for (String kv : form.split("&")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                String k = URLDecoder.decode(kv.substring(0, i), UTF8);
                String v = URLDecoder.decode(kv.substring(i + 1), UTF8);
                if (k.equals("name")) names.add(v);
                else if (k.equals("password")) password = v;
                else if (k.equals("newPassword")) newPassword = v;
                else params.put(k, v);
            }
        } else if (params.containsKey("name")) {
            names.add(params.get("name"));
        }
        // el móvil manda su token en una cabecera; la web, una cookie de sesión que solo vive en memoria
        String token = headers.get("x-subelo-token");
        String webToken = token == null ? cookie(headers.get("cookie"), "subelo_s") : null;
        String user = token != null ? Users.userFor(ctx, token) : Users.webUserFor(ctx, webToken);

        if (path.equals("/api/users")) {
            JSONArray arr = new JSONArray();
            for (String n : Users.NAMES) {
                JSONObject u = new JSONObject();
                u.put("name", n);
                u.put("hasPassword", Users.hasPassword(ctx, n));
                arr.put(u);
            }
            JSONObject o = new JSONObject();
            o.put("users", arr);
            o.put("minLength", Users.MIN_LENGTH);
            json(out, 200, o);
            return;
        }
        if (path.equals("/api/login")) {
            login(ip, method, params, password, out);
            return;
        }
        if (path.equals("/api/logout")) {
            Users.endSession(ctx, token);
            Users.endWebSession(webToken);
            byte[] b = "{\"ok\":true}".getBytes(UTF8);
            sendHead(out, 200, "application/json; charset=utf-8", b.length,
                    "Set-Cookie: subelo_s=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict\r\n");
            out.write(b);
            out.flush();
            return;
        }
        Store st = Config.store(ctx);
        boolean available = st != null && st.available();

        if (path.equals("/api/info")) {
            JSONObject o = new JSONObject();
            o.put("app", "subelo");
            o.put("version", 1);
            o.put("name", "Súbelo en " + Build.MODEL);
            o.put("user", user == null ? JSONObject.NULL : user);
            o.put("storage", st == null ? JSONObject.NULL : st.describe());
            o.put("available", available);
            o.put("free", available ? st.free() : -1);
            o.put("total", available ? st.total() : -1);
            json(out, 200, o);
            return;
        }
        if (user == null) {
            error(out, 401, "Tienes que entrar con tu usuario");
            return;
        }
        if (path.equals("/api/password")) {
            changePassword(ip, method, user, password, newPassword, webToken, "1".equals(params.get("phones")), out);
            return;
        }
        if (path.equals("/api/box")) {
            box(st, available, out);
            return;
        }
        if (path.equals("/api/amazon") || path.startsWith("/api/amazon/")) {
            amazon(method, path, user, params, out);
            return;
        }
        if (path.equals("/api/google") || path.startsWith("/api/google/")) {
            google(method, path, user, params, available ? st : null, out);
            return;
        }
        if (!available) {
            error(out, 503, st == null
                    ? "El box no tiene ningún disco configurado. Ábrelo en la tele y elige la carpeta del disco USB."
                    : "El disco USB no está disponible. ¿Está conectado al box?");
            return;
        }
        // cada usuario trabaja dentro de su carpeta: «Fer/...» o «Julss/...»
        String rel = cleanPath(params.get("path"));
        if (path.equals("/api/shares") || path.equals("/api/share") || path.equals("/api/unshare")) {
            shares(st, method, path, user, rel, params, out);
            return;
        }
        // los favoritos: otra carpeta especial, que solo apunta a fotos de otras carpetas
        if (Favorites.isVirtual(rel)) {
            favorites(st, method, path, user, rel, names, params, headers, out);
            return;
        }
        // lo compartido contigo vive bajo una carpeta especial que apunta a la del otro usuario
        Shares.Access acc = Shares.resolve(ctx, user, rel);
        if (acc == null) {
            error(out, 404, "Esa carpeta ya no está compartida contigo");
            return;
        }
        if (acc.virtualRoot) {
            if (path.equals("/api/folders") || path.equals("/api/list") || path.equals("/api/albums")) {
                virtualRoot(st, path, user, out);
            } else {
                error(out, 403, "Aquí solo están las carpetas que te han compartido: entra en una de ellas.");
            }
            return;
        }
        boolean reading = path.equals("/api/folders") || path.equals("/api/list") || path.equals("/api/albums")
                || path.equals("/api/file") || path.equals("/api/thumb") || path.equals("/api/zip")
                || path.equals("/api/exists") || path.equals("/api/places") || path.equals("/api/echo")
                || path.equals("/api/fav");
        boolean adding = path.equals("/api/mkdir") || path.equals("/api/upload");
        if (!acc.own && !reading && !(adding && acc.canAdd())) {
            error(out, 403, adding ? "Esta carpeta es de " + acc.owner + " y solo te deja verla."
                    : "Esta carpeta es de " + acc.owner + ": solo " + acc.owner + " puede borrar, mover o cambiar nombres.");
            return;
        }
        String dirRel = acc.storeRel;

        if (path.equals("/api/folders")) {
            Object d = st.dir(dirRel, rel.isEmpty());
            if (d == null) {
                error(out, 404, "La carpeta no existe");
                return;
            }
            List<String> dirs = st.listDirs(d);
            JSONArray arr = new JSONArray();
            if (acc.own && rel.isEmpty() && !Shares.incoming(ctx, user).isEmpty()) arr.put(Shares.VIRTUAL);
            for (String n : dirs) arr.put(n);
            JSONObject o = new JSONObject();
            o.put("path", rel);
            o.put("folders", arr);
            access(o, acc, user, rel);
            json(out, 200, o);
        } else if (path.equals("/api/mkdir")) {
            if (rel.isEmpty()) {
                error(out, 400, "Falta el nombre de la carpeta");
                return;
            }
            st.dir(dirRel, true);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("path", rel);
            json(out, 200, o);
        } else if (path.equals("/api/exists")) {
            String name = cleanName(params.get("name"));
            if (name == null) {
                error(out, 400, "Falta el nombre del fichero");
                return;
            }
            Object d = st.dir(dirRel, false);
            long size = d == null ? -1 : st.fileSize(d, name);
            JSONObject o = new JSONObject();
            o.put("exists", size >= 0);
            o.put("size", size);
            json(out, 200, o);
        } else if (path.equals("/api/upload") && (method.equals("PUT") || method.equals("POST"))) {
            upload(st, dirRel, params, body, contentLength, out);
            if (dirRel.equals(acc.owner + "/" + EchoSync.FOLDER)) EchoSync.kick(ctx, acc.owner);
        } else if (path.equals("/api/echo")) {
            if (!method.equals("POST")) {
                error(out, 405, "Usa POST");
                return;
            }
            echoCopy(st, dirRel, user, names, out);
        } else if (path.equals("/api/list")) {
            Object d = st.dir(dirRel, rel.isEmpty());
            if (d == null) {
                error(out, 404, "La carpeta no existe");
                return;
            }
            JSONArray dirs = new JSONArray();
            if (acc.own && rel.isEmpty()) dirs.put(Favorites.VIRTUAL);
            if (acc.own && rel.isEmpty() && !Shares.incoming(ctx, user).isEmpty()) dirs.put(Shares.VIRTUAL);
            for (String n : st.listDirs(d)) dirs.put(n);
            JSONArray files = new JSONArray();
            Set<String> favs = Favorites.of(ctx, user);
            for (Store.FileInfo f : st.listFiles(d)) {
                JSONObject fo = new JSONObject();
                fo.put("name", f.name);
                fo.put("size", f.size);
                fo.put("mtime", f.mtime);
                if (favs.contains(dirRel + "/" + f.name)) fo.put("fav", true);
                files.put(fo);
            }
            JSONObject o = new JSONObject();
            o.put("path", rel);
            o.put("folders", dirs);
            o.put("files", files);
            access(o, acc, user, rel);
            json(out, 200, o);
        } else if (path.equals("/api/file")) {
            download(st, dirRel, params, headers, method.equals("HEAD"), out);
        } else if (path.equals("/api/thumb")) {
            thumb(st, dirRel, cleanName(params.get("name")), out);
        } else if (path.equals("/api/fav")) {
            if (!method.equals("POST")) {
                error(out, 405, "Usa POST");
                return;
            }
            Object d = st.dir(dirRel, false);
            List<String> stores = new ArrayList<String>();
            for (String n : names) {
                if (d != null && existingName(n) && st.fileSize(d, n) >= 0) stores.add(dirRel + "/" + n);
            }
            if (stores.isEmpty()) {
                error(out, 400, "No has elegido ninguna foto");
                return;
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("changed", Favorites.set(ctx, user, stores, !"0".equals(params.get("on"))));
            json(out, 200, o);
        } else if (path.equals("/api/geo")) {
            Object d = st.dir(dirRel, false);
            List<String> stores = new ArrayList<String>();
            for (String n : names) {
                if (d != null && existingName(n) && st.fileSize(d, n) >= 0) stores.add(dirRel + "/" + n);
            }
            if (stores.isEmpty()) {
                error(out, 400, "No has elegido ninguna foto");
                return;
            }
            if (method.equals("GET")) {
                json(out, 200, geoInfo(user, stores.get(0)));
            } else {
                geoSet(stores, params, out);
            }
        } else if (path.equals("/api/albums")) {
            Object d = st.dir(dirRel, rel.isEmpty());
            if (d == null) {
                error(out, 404, "La carpeta no existe");
                return;
            }
            JSONArray arr = new JSONArray();
            if (acc.own && rel.isEmpty()) arr.put(favAlbum(st, user));
            if (acc.own && rel.isEmpty()) {
                List<Shares.Share> in = Shares.incoming(ctx, user);
                if (!in.isEmpty()) {
                    JSONObject a = new JSONObject();
                    a.put("name", Shares.VIRTUAL);
                    a.put("media", 0);
                    a.put("folders", in.size());
                    a.put("virtual", true);
                    for (Shares.Share sh : in) {
                        JSONObject x = album(st, sh.owner + "/" + sh.path, Shares.VIRTUAL + "/" + sh.name, sh.name);
                        if (x != null && x.has("cover")) {
                            a.put("cover", x.get("cover"));
                            break;
                        }
                    }
                    arr.put(a);
                }
            }
            for (String n : st.listDirs(d)) {
                JSONObject a = album(st, dirRel + "/" + n, rel.isEmpty() ? n : rel + "/" + n, n);
                if (a == null) continue;
                if (acc.own) {
                    Map<String, String> with = Shares.outgoing(ctx, user, rel.isEmpty() ? n : rel + "/" + n);
                    if (!with.isEmpty()) a.put("sharedWith", new JSONObject(with));
                }
                arr.put(a);
            }
            JSONObject o = new JSONObject();
            o.put("path", rel);
            o.put("albums", arr);
            json(out, 200, o);
        } else if (path.equals("/api/places")) {
            send(out, 200, "application/json; charset=utf-8", Places.json(ctx, st, user).getBytes(UTF8));
        } else if (path.equals("/api/delete") || path.equals("/api/move") || path.equals("/api/rename")) {
            if (!acc.own) {
                error(out, 403, "Solo " + acc.owner + " puede cambiar esta carpeta.");
                return;
            }
            if (!method.equals("POST")) {
                error(out, 405, "Usa POST");
                return;
            }
            Object d = st.dir(dirRel, false);
            if (d == null) {
                error(out, 404, "La carpeta no existe");
                return;
            }
            for (String n : names) {
                if (!existingName(n)) {
                    error(out, 400, "Nombre no válido: " + n);
                    return;
                }
            }
            if (names.isEmpty()) {
                error(out, 400, "No has elegido nada");
                return;
            }
            JSONObject o = new JSONObject();
            if (path.equals("/api/delete")) {
                int ok = 0;
                for (String n : names) {
                    st.delete(d, n);
                    Shares.deleted(ctx, user, rel.isEmpty() ? n : rel + "/" + n);
                    Favorites.deleted(ctx, dirRel + "/" + n);
                    Geo.deleted(ctx, dirRel + "/" + n);
                    ok++;
                }
                o.put("deleted", ok);
            } else if (path.equals("/api/move")) {
                String to = cleanPath(params.get("to"));
                if (Shares.isVirtual(to)) {
                    error(out, 400, "No se puede mover a lo que te han compartido.");
                    return;
                }
                for (String n : names) {
                    String moved = rel.isEmpty() ? n : rel + "/" + n;
                    if (to.equals(rel)) {
                        error(out, 400, "Ya está en esa carpeta");
                        return;
                    }
                    if (to.equals(moved) || to.startsWith(moved + "/")) {
                        error(out, 400, "No puedes mover la carpeta «" + n + "» dentro de sí misma");
                        return;
                    }
                }
                Object dest = st.dir(to.isEmpty() ? user : user + "/" + to, true);
                JSONArray arr = new JSONArray();
                for (String n : names) {
                    String fin = st.move(d, n, dest);
                    Shares.moved(ctx, user, rel.isEmpty() ? n : rel + "/" + n, to.isEmpty() ? fin : to + "/" + fin);
                    Favorites.moved(ctx, dirRel + "/" + n, (to.isEmpty() ? user : user + "/" + to) + "/" + fin);
                    Geo.moved(ctx, dirRel + "/" + n, (to.isEmpty() ? user : user + "/" + to) + "/" + fin);
                    arr.put(fin);
                }
                o.put("moved", arr);
                o.put("to", to);
            } else {
                String nn = cleanName(params.get("new"));
                if (nn == null || names.size() != 1) {
                    error(out, 400, "Falta el nombre nuevo");
                    return;
                }
                if (!st.rename(d, names.get(0), nn)) {
                    error(out, 409, "Ya hay algo llamado «" + nn + "» en esta carpeta");
                    return;
                }
                Shares.moved(ctx, user, rel.isEmpty() ? names.get(0) : rel + "/" + names.get(0), rel.isEmpty() ? nn : rel + "/" + nn);
                Favorites.moved(ctx, dirRel + "/" + names.get(0), dirRel + "/" + nn);
                Geo.moved(ctx, dirRel + "/" + names.get(0), dirRel + "/" + nn);
                o.put("name", nn);
            }
            o.put("ok", true);
            json(out, 200, o);
            EchoSync.kick(ctx, user); // por si ha tocado la carpeta del Echo
        } else if (path.equals("/api/zip")) {
            zip(st, dirRel, names, out);
        } else {
            error(out, 404, "No encontrado");
        }
    }

    // ---------- Echo (Amazon Photos) ----------

    private void amazon(String method, String path, String user, Map<String, String> params, OutputStream out)
            throws IOException, JSONException {
        if (path.equals("/api/amazon")) {
            json(out, 200, EchoSync.statusJson(ctx, user));
            return;
        }
        if (!method.equals("POST") && !path.equals("/api/amazon/albums")) {
            error(out, 405, "Usa POST");
            return;
        }
        // los fallos de Amazon van como 502: el 401 lo reserva la app para «sesión de Súbelo caducada»
        try {
            if (path.equals("/api/amazon/connect")) {
                String tld = params.get("tld");
                if (tld == null || !tld.matches("[a-z]{2,3}(\\.[a-z]{2})?")) tld = "es";
                EchoSync.connect(ctx, user, params.get("cookie"), tld);
                JSONObject o = EchoSync.statusJson(ctx, user);
                try {
                    o.put("albums", EchoSync.albumsJson(EchoSync.albums(ctx, user)));
                } catch (IOException e) {
                    o.put("albumsError", e.getMessage());
                }
                json(out, 200, o);
            } else if (path.equals("/api/amazon/albums")) {
                JSONObject o = new JSONObject();
                o.put("albums", EchoSync.albumsJson(EchoSync.albums(ctx, user)));
                json(out, 200, o);
            } else if (path.equals("/api/amazon/album")) {
                String id = params.get("id"), name = params.get("title"), link = params.get("link"); // «name» lo usan los ficheros
                if (link != null && !link.trim().isEmpty()) {
                    Amazon.Album a = EchoSync.albumFromLink(ctx, user, link);
                    id = a.id;
                    name = a.name;
                }
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) {
                    error(out, 400, "Elige un álbum");
                    return;
                }
                EchoSync.setAlbum(ctx, user, id, name);
                json(out, 200, EchoSync.statusJson(ctx, user));
            } else if (path.equals("/api/amazon/sync")) {
                EchoSync.kick(ctx, user);
                json(out, 200, EchoSync.statusJson(ctx, user));
            } else if (path.equals("/api/amazon/disconnect")) {
                EchoSync.disconnect(ctx, user);
                json(out, 200, EchoSync.statusJson(ctx, user));
            } else {
                error(out, 404, "No encontrado");
            }
        } catch (IOException e) {
            error(out, 502, e.getMessage() == null ? "No se pudo hablar con Amazon" : e.getMessage());
        }
    }

    // ---------- traer de Google Fotos ----------

    private void google(String method, String path, String user, Map<String, String> params, Store st, OutputStream out)
            throws IOException, JSONException {
        if (path.equals("/api/google")) {
            json(out, 200, GoogleImport.statusJson(ctx, user));
            return;
        }
        if (!method.equals("POST")) {
            error(out, 405, "Usa POST");
            return;
        }
        // los fallos de Google van como 502 (o 400): el 401 lo reserva la app para «sesión de Súbelo caducada»
        try {
            if (path.equals("/api/google/setup")) {
                GoogleImport.setup(ctx, params.get("client"), params.get("secret"));
            } else if (path.equals("/api/google/reset")) {
                GoogleImport.cancel(user);
                GoogleImport.reset(ctx);
            } else if (path.equals("/api/google/cancel")) {
                GoogleImport.cancel(user);
                GoogleImport.webForget(user);
            } else if (path.equals("/api/google/clear")) {
                GoogleImport.clear(user);
            } else if (path.equals("/api/google/start") || path.equals("/api/google/auth")) {
                boolean web = path.equals("/api/google/auth"); // la web: primero la dirección de Google
                String code = params.get("code"), verifier = params.get("verifier"), redirect = params.get("redirect");
                if (!web && (code == null || code.isEmpty() || code.length() > 2000 || verifier == null
                        || !verifier.matches("[A-Za-z0-9._~-]{43,128}")
                        || redirect == null || !redirect.matches("http://(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?/?"))) {
                    error(out, 400, "Petición de Google incompleta");
                    return;
                }
                if (st == null) {
                    error(out, 503, "El disco USB no está disponible. ¿Está conectado al box?");
                    return;
                }
                String rel = cleanPath(params.get("path"));
                Shares.Access acc = Shares.resolve(ctx, user, rel);
                if (acc == null || acc.virtualRoot) {
                    error(out, 404, "Elige una carpeta tuya o una que te hayan compartido para subir");
                    return;
                }
                if (!acc.own && !acc.canAdd()) {
                    error(out, 403, "Esta carpeta es de " + acc.owner + " y solo te deja verla.");
                    return;
                }
                if (web) {
                    JSONObject o = GoogleImport.statusJson(ctx, user);
                    o.put("authUrl", GoogleImport.webAuthUrl(ctx, user, acc.owner, acc.storeRel, rel));
                    json(out, 200, o);
                    return;
                }
                GoogleImport.start(ctx, user, acc.owner, acc.storeRel, rel, code, verifier, redirect);
            } else if (path.equals("/api/google/code")) {
                GoogleImport.webFinish(ctx, user, params.get("url"));
            } else {
                error(out, 404, "No encontrado");
                return;
            }
            json(out, 200, GoogleImport.statusJson(ctx, user));
        } catch (GoogleImport.GoogleException e) {
            error(out, e.code == 400 ? 400 : 502, e.getMessage());
        } catch (IOException e) {
            error(out, 502, e.getMessage() == null ? "No se pudo hablar con Google" : e.getMessage());
        }
    }

    /** Copia fotos a la carpeta «Echo» del usuario (desde la suya o desde una compartida). */
    private void echoCopy(Store st, String dirRel, String user, List<String> names, OutputStream out)
            throws IOException, JSONException {
        if (st.dir(dirRel, false) == null) {
            error(out, 404, "La carpeta no existe");
            return;
        }
        List<String[]> items = new ArrayList<String[]>();
        for (String n : names) items.add(new String[]{dirRel, n});
        echoCopy(st, user, items, out);
    }

    /** Copia fotos a la carpeta del Echo; items: {carpeta en el disco, nombre}. */
    private void echoCopy(Store st, String user, List<String[]> items, OutputStream out) throws IOException, JSONException {
        String echoRel = user + "/" + EchoSync.FOLDER;
        Object dst = st.dir(echoRel, true);
        int copied = 0, already = 0, skipped = 0;
        byte[] buf = new byte[256 * 1024];
        for (String[] it : items) {
            String dirRel = it[0], n = it[1];
            if (!existingName(n) || !Thumbs.isImage(n)) {
                skipped++;
                continue;
            }
            if (dirRel.equals(echoRel)) {
                already++;
                continue;
            }
            Object src = st.dir(dirRel, false);
            Store.Readable rd = src == null ? null : st.read(src, n);
            if (rd == null) {
                skipped++;
                continue;
            }
            Store.Upload up = null;
            boolean ok = false;
            try {
                up = st.begin(dst, n);
                OutputStream os = up.out();
                InputStream in = rd.open(0);
                long total = 0;
                try {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        os.write(buf, 0, r);
                        total += r;
                    }
                } finally {
                    in.close();
                }
                os.close();
                Store.Committed c = up.commit(rd.mtime(), total);
                ok = true;
                if (c.duplicate) already++;
                else copied++;
            } finally {
                if (!ok && up != null) up.abort();
                rd.close();
            }
        }
        EchoSync.kick(ctx, user);
        JSONObject o = EchoSync.statusJson(ctx, user);
        o.put("copied", copied);
        o.put("already", already);
        o.put("skipped", skipped);
        json(out, 200, o);
    }

    /** Una carpeta como «álbum»: cuántas fotos tiene, subcarpetas y una portada. null si no existe. */
    private static JSONObject album(Store st, String storeRel, String clientRel, String name) throws IOException, JSONException {
        Object sub = st.dir(storeRel, false);
        if (sub == null) return null;
        JSONObject a = new JSONObject();
        a.put("name", name);
        int media = 0;
        Store.FileInfo first = null;
        for (Store.FileInfo f : st.listFiles(sub)) {
            if (!Thumbs.isImage(f.name) && !Thumbs.isVideo(f.name)) continue;
            media++;
            if (first == null) first = f;
        }
        a.put("media", media);
        List<String> subs = st.listDirs(sub);
        a.put("folders", subs.size());
        String coverDir = clientRel;
        if (first == null) {
            String[] found = cover(st, storeRel, clientRel, subs, 2);
            if (found != null) {
                coverDir = found[0];
                first = new Store.FileInfo(found[1], Long.parseLong(found[2]), 0);
            }
        }
        if (first != null) {
            JSONObject c = new JSONObject();
            c.put("path", coverDir);
            c.put("name", first.name);
            c.put("size", first.size);
            a.put("cover", c);
        }
        return a;
    }

    private void thumb(Store st, String dirRel, String name, OutputStream out) throws IOException {
        Object d = st.dir(dirRel, false);
        byte[] t = d == null || name == null ? null : Thumbs.get(ctx, st, d, dirRel, name);
        if (t == null) {
            error(out, 404, "Sin miniatura");
            return;
        }
        sendHead(out, 200, "image/jpeg", t.length, "Cache-Control: max-age=604800\r\n");
        out.write(t);
        out.flush();
    }

    /** Dónde se hizo una foto (ruta en el disco): la puesta a mano o la del GPS, si ya se ha leído. */
    private JSONObject geoInfo(String user, String store) throws JSONException {
        JSONObject o = new JSONObject();
        double[] ll = Geo.get(ctx, store);
        boolean manual = ll != null;
        if (ll == null) {
            String inUser = store.substring(user.length() + 1);
            int slash = inUser.lastIndexOf('/');
            ll = Places.known(ctx, user, slash < 0 ? "" : inUser.substring(0, slash), inUser.substring(slash + 1));
        }
        if (ll != null) {
            o.put("lat", ll[0]);
            o.put("lon", ll[1]);
        }
        o.put("manual", manual);
        return o;
    }

    /** POST /api/geo: lat y lon, o clear=1 para volver a la del GPS. */
    private void geoSet(List<String> stores, Map<String, String> params, OutputStream out) throws IOException, JSONException {
        JSONObject o = new JSONObject();
        if ("1".equals(params.get("clear"))) {
            o.put("changed", Geo.clear(ctx, stores));
        } else {
            double lat, lon;
            try {
                lat = Double.parseDouble(params.get("lat"));
                lon = Double.parseDouble(params.get("lon"));
            } catch (RuntimeException e) {
                lat = lon = Double.NaN;
            }
            if (!Geo.valid(lat, lon)) {
                error(out, 400, "Falta el sitio: márcalo en el mapa");
                return;
            }
            o.put("changed", Geo.set(ctx, stores, lat, lon));
        }
        o.put("ok", true);
        json(out, 200, o);
    }

    /** La carpeta de favoritos como «álbum» de la carpeta principal. */
    private JSONObject favAlbum(Store st, String user) throws IOException, JSONException {
        JSONObject a = new JSONObject();
        a.put("name", Favorites.VIRTUAL);
        a.put("folders", 0);
        a.put("virtual", true);
        a.put("fav", true);
        int media = 0;
        Map<String, Map<String, Store.FileInfo>> cache = new HashMap<String, Map<String, Store.FileInfo>>();
        for (Favorites.Fav f : Favorites.list(ctx, user)) {
            Store.FileInfo fi = favInfo(st, cache, f);
            if (fi == null || (!Thumbs.isImage(f.name) && !Thumbs.isVideo(f.name))) continue;
            if (media++ == 0) {
                JSONObject c = new JSONObject();
                c.put("path", Favorites.VIRTUAL);
                c.put("name", f.shown);
                c.put("size", fi.size);
                a.put("cover", c);
            }
        }
        a.put("media", media);
        return a;
    }

    /** Tamaño y fecha de un favorito (null si el archivo ya no está); lee cada carpeta una vez. */
    private static Store.FileInfo favInfo(Store st, Map<String, Map<String, Store.FileInfo>> cache, Favorites.Fav f)
            throws IOException {
        Map<String, Store.FileInfo> m = cache.get(f.dir);
        if (m == null) {
            m = new HashMap<String, Store.FileInfo>();
            Object d = st.dir(f.dir, false);
            if (d != null) for (Store.FileInfo fi : st.listFiles(d)) m.put(fi.name, fi);
            cache.put(f.dir, m);
        }
        return m.get(f.name);
    }

    /**
     * La carpeta especial de favoritos: cada nombre apunta a un archivo de otra carpeta (tuya o que te
     * comparten). Aquí no se sube, ni se mueve, ni se borra nada: «borrar» solo lo quita de favoritos.
     */
    private void favorites(Store st, String method, String path, String user, String rel, List<String> names,
                           Map<String, String> params, Map<String, String> headers, OutputStream out)
            throws IOException, JSONException {
        if (!rel.equals(Favorites.VIRTUAL)) {
            error(out, 404, "En Favoritos no hay carpetas");
            return;
        }
        List<Favorites.Fav> all = Favorites.list(ctx, user);
        Map<String, Favorites.Fav> byName = new HashMap<String, Favorites.Fav>();
        for (Favorites.Fav f : all) byName.put(f.shown, f);
        if (path.equals("/api/list") || path.equals("/api/folders") || path.equals("/api/albums")) {
            JSONObject o = new JSONObject();
            o.put("path", Favorites.VIRTUAL);
            o.put("access", "fav");
            o.put("owner", user);
            if (path.equals("/api/albums")) {
                o.put("albums", new JSONArray());
            } else {
                o.put("folders", new JSONArray());
            }
            if (path.equals("/api/list")) {
                JSONArray files = new JSONArray();
                Map<String, Map<String, Store.FileInfo>> cache = new HashMap<String, Map<String, Store.FileInfo>>();
                for (Favorites.Fav f : all) {
                    Store.FileInfo fi = favInfo(st, cache, f);
                    if (fi == null) continue; // ya no está
                    JSONObject fo = new JSONObject();
                    fo.put("name", f.shown);
                    fo.put("size", fi.size);
                    fo.put("mtime", fi.mtime);
                    fo.put("fav", true);
                    fo.put("own", f.store.startsWith(user + "/"));
                    fo.put("from", f.dir.equals(user) ? "" : f.dir.startsWith(user + "/") ? f.dir.substring(user.length() + 1)
                            : f.dir.replaceFirst("/", ": ")); // lo de otro: «Julss: Viajes/Roma»
                    files.put(fo);
                }
                o.put("files", files);
            }
            json(out, 200, o);
            return;
        }
        if (path.equals("/api/file") || path.equals("/api/thumb")) {
            Favorites.Fav f = byName.get(params.get("name"));
            if (f == null) {
                error(out, 404, "Ya no está en tus favoritos");
                return;
            }
            if (path.equals("/api/thumb")) {
                thumb(st, f.dir, f.name, out);
            } else {
                Map<String, String> p = new HashMap<String, String>(params);
                p.put("name", f.name);
                download(st, f.dir, p, headers, method.equals("HEAD"), out);
            }
            return;
        }
        if (path.equals("/api/zip")) {
            List<Object[]> items = new ArrayList<Object[]>();
            for (Favorites.Fav f : all) {
                if (!names.isEmpty() && !names.contains(f.shown)) continue;
                Object d = st.dir(f.dir, false);
                if (d != null) items.add(new Object[]{d, f.name, f.shown});
            }
            zipItems(st, "Favoritos.zip", items, out);
            return;
        }
        if (path.equals("/api/geo")) {
            List<String> stores = new ArrayList<String>();
            for (String n : names) {
                Favorites.Fav f = byName.get(n);
                if (f != null && f.store.startsWith(user + "/")) stores.add(f.store);
            }
            if (stores.isEmpty()) {
                error(out, names.isEmpty() ? 400 : 403, names.isEmpty() ? "No has elegido ninguna foto"
                        : "Esa foto es de otro: solo su dueño puede cambiar dónde se hizo.");
                return;
            }
            if (method.equals("GET")) json(out, 200, geoInfo(user, stores.get(0)));
            else geoSet(stores, params, out);
            return;
        }
        if (path.equals("/api/echo") || path.equals("/api/fav") || path.equals("/api/delete")) {
            if (!method.equals("POST")) {
                error(out, 405, "Usa POST");
                return;
            }
            List<Favorites.Fav> chosen = new ArrayList<Favorites.Fav>();
            for (String n : names) {
                Favorites.Fav f = byName.get(n);
                if (f != null) chosen.add(f);
            }
            if (path.equals("/api/echo")) {
                List<String[]> items = new ArrayList<String[]>();
                for (Favorites.Fav f : chosen) items.add(new String[]{f.dir, f.name});
                echoCopy(st, user, items, out);
                return;
            }
            // quitar de favoritos (también lo que llega como «borrar»: el archivo nunca se toca)
            List<String> stores = new ArrayList<String>();
            if (!"1".equals(params.get("on"))) for (Favorites.Fav f : chosen) stores.add(f.store);
            int n = Favorites.set(ctx, user, stores, false);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("changed", n);
            o.put("deleted", n);
            json(out, 200, o);
            return;
        }
        error(out, 403, "En Favoritos solo están tus fotos marcadas: aquí no se puede subir, mover ni cambiar nombres.");
    }

    /** Qué puede hacer el usuario en la carpeta que está viendo (para que el móvil y la web lo muestren). */
    private void access(JSONObject o, Shares.Access acc, String user, String rel) throws JSONException {
        o.put("access", acc.own ? "own" : acc.mode);
        o.put("owner", acc.owner);
        if (acc.own && !rel.isEmpty()) o.put("sharedWith", new JSONObject(Shares.outgoing(ctx, user, rel)));
    }

    /** La carpeta especial con lo que te han compartido. */
    private void virtualRoot(Store st, String path, String user, OutputStream out) throws IOException, JSONException {
        JSONArray folders = new JSONArray(), albums = new JSONArray();
        for (Shares.Share sh : Shares.incoming(ctx, user)) {
            String storeRel = sh.owner + "/" + sh.path;
            if (path.equals("/api/albums")) {
                JSONObject a = album(st, storeRel, Shares.VIRTUAL + "/" + sh.name, sh.name);
                if (a == null) continue;
                a.put("owner", sh.owner);
                a.put("mode", sh.mode);
                albums.put(a);
            } else if (st.dir(storeRel, false) != null) {
                folders.put(sh.name);
            }
        }
        JSONObject o = new JSONObject();
        o.put("path", Shares.VIRTUAL);
        o.put("access", "virtual");
        if (path.equals("/api/albums")) {
            o.put("albums", albums);
        } else {
            o.put("folders", folders);
            if (path.equals("/api/list")) o.put("files", new JSONArray());
        }
        json(out, 200, o);
    }

    /** Compartir carpetas: ver lo compartido, compartir y dejar de compartir. */
    private void shares(Store st, String method, String path, String user, String rel, Map<String, String> params,
                        OutputStream out) throws IOException, JSONException {
        if (path.equals("/api/shares")) {
            JSONArray mine = new JSONArray(), withMe = new JSONArray(), others = new JSONArray();
            for (Shares.Share sh : Shares.outgoing(ctx, user)) {
                JSONObject x = new JSONObject();
                x.put("path", sh.path);
                x.put("with", sh.guest);
                x.put("mode", sh.mode);
                mine.put(x);
            }
            for (Shares.Share sh : Shares.incoming(ctx, user)) {
                JSONObject x = new JSONObject();
                x.put("owner", sh.owner);
                x.put("name", sh.name);
                x.put("path", Shares.VIRTUAL + "/" + sh.name);
                x.put("mode", sh.mode);
                withMe.put(x);
            }
            for (String n : Users.NAMES) if (!n.equals(user)) others.put(n);
            JSONObject o = new JSONObject();
            o.put("mine", mine);
            o.put("withMe", withMe);
            o.put("others", others);
            json(out, 200, o);
            return;
        }
        if (!method.equals("POST")) {
            error(out, 405, "Usa POST");
            return;
        }
        String guest = Users.canonical(params.get("with"));
        if (guest == null || guest.equals(user)) {
            error(out, 400, "¿Con quién la compartes?");
            return;
        }
        if (rel.isEmpty() || Shares.isVirtual(rel)) {
            error(out, 400, rel.isEmpty() ? "Elige una carpeta concreta para compartir, no todo tu disco."
                    : "Solo puedes compartir carpetas tuyas.");
            return;
        }
        if (path.equals("/api/share")) {
            if (st.dir(user + "/" + rel, false) == null) {
                error(out, 404, "La carpeta no existe");
                return;
            }
            Shares.set(ctx, user, rel, guest, params.get("mode"));
        } else {
            Shares.remove(ctx, user, rel, guest);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("sharedWith", new JSONObject(Shares.outgoing(ctx, user, rel)));
        json(out, 200, o);
    }

    /** Portada de una carpeta sin fotos sueltas: la primera foto de sus subcarpetas (año → mes). */
    private static String[] cover(Store st, String dirRel, String rel, List<String> subs, int depth) throws IOException {
        int tried = 0;
        for (String n : subs) {
            if (Store.hidden(n) || ++tried > 6) break;
            Object d = st.dir(dirRel + "/" + n, false);
            if (d == null) continue;
            for (Store.FileInfo f : st.listFiles(d)) {
                if (Thumbs.isImage(f.name) || Thumbs.isVideo(f.name)) {
                    return new String[]{rel + "/" + n, f.name, String.valueOf(f.size)};
                }
            }
            if (depth > 1) {
                String[] r = cover(st, dirRel + "/" + n, rel + "/" + n, st.listDirs(d), depth - 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void upload(Store st, String dirRel, Map<String, String> params, InputStream body,
                        long contentLength, OutputStream out) throws IOException, JSONException {
        String name = cleanName(params.get("name"));
        if (name == null) {
            error(out, 400, "Falta el nombre del fichero");
            return;
        }
        long declared = parseLong(params.get("size"), -1);
        long mtime = parseLong(params.get("mtime"), 0);
        Object d = st.dir(dirRel, true);
        Store.Upload up = st.begin(d, name);
        boolean ok = false;
        try {
            OutputStream os = up.out();
            byte[] buf = new byte[256 * 1024];
            long n = 0;
            int r;
            while ((r = body.read(buf)) != -1) {
                os.write(buf, 0, r);
                n += r;
            }
            os.close();
            if ((contentLength >= 0 && n != contentLength) || (declared >= 0 && n != declared)) {
                throw new IOException("Subida incompleta");
            }
            Store.Committed c = up.commit(mtime, n);
            ok = true;
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", c.name);
            o.put("size", n);
            o.put("duplicate", c.duplicate);
            json(out, 200, o);
        } finally {
            if (!ok) up.abort();
        }
    }

    private void download(Store st, String dirRel, Map<String, String> params, Map<String, String> headers,
                          boolean headOnly, OutputStream out) throws IOException {
        String name = cleanName(params.get("name"));
        Object d = name == null ? null : st.dir(dirRel, false);
        Store.Readable r = d == null ? null : st.read(d, name);
        if (r == null) {
            error(out, 404, "No existe el fichero");
            return;
        }
        try {
            long size = r.size();
            long start = 0, end = size - 1;
            int code = 200;
            String range = headers.get("range");
            if (range != null && range.startsWith("bytes=") && size > 0) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) {
                        start = Math.max(0, size - Long.parseLong(spec.substring(1)));
                    } else if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash));
                        if (dash < spec.length() - 1) end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                    }
                    if (start > end || start >= size) {
                        sendHead(out, 416, "text/plain", 0, "Content-Range: bytes */" + size + "\r\n");
                        out.flush();
                        return;
                    }
                    code = 206;
                } catch (NumberFormatException ignored) {
                    start = 0;
                    end = size - 1;
                }
            }
            long len = size == 0 ? 0 : end - start + 1;
            String extra = "Accept-Ranges: bytes\r\n"
                    + (code == 206 ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "")
                    + "Content-Disposition: " + ("1".equals(params.get("download")) ? "attachment" : "inline")
                    + "; filename*=UTF-8''" + encodeRfc5987(name) + "\r\n";
            sendHead(out, code, mime(name), len, extra);
            if (!headOnly && len > 0) {
                InputStream in = r.open(start);
                try {
                    byte[] buf = new byte[256 * 1024];
                    long left = len;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    in.close();
                }
            }
            out.flush();
        } finally {
            r.close();
        }
    }

    /** Envía un .zip sin comprimir (las fotos ya vienen comprimidas) con los ficheros pedidos o toda la carpeta. */
    private void zip(Store st, String dirRel, List<String> names, OutputStream out) throws IOException {
        Object d = st.dir(dirRel, false);
        if (d == null) {
            error(out, 404, "La carpeta no existe");
            return;
        }
        List<String> list = new ArrayList<String>();
        if (names.isEmpty()) {
            for (Store.FileInfo f : st.listFiles(d)) list.add(f.name);
        } else {
            for (String n : names) {
                String c = cleanName(n);
                if (c != null) list.add(c);
            }
        }
        List<Object[]> items = new ArrayList<Object[]>();
        for (String n : list) items.add(new Object[]{d, n, n});
        zipItems(st, dirRel.substring(dirRel.lastIndexOf('/') + 1) + ".zip", items, out);
    }

    /** Un .zip con items {carpeta (de Store), nombre, nombre dentro del .zip}. */
    private void zipItems(Store st, String zipName, List<Object[]> items, OutputStream out) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename*=UTF-8''" + encodeRfc5987(zipName) + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(ISO));
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.setLevel(0);
        byte[] buf = new byte[256 * 1024];
        for (Object[] it : items) {
            Store.Readable r = st.read(it[0], (String) it[1]);
            if (r == null) continue;
            try {
                ZipEntry e = new ZipEntry((String) it[2]);
                if (r.mtime() > 0) e.setTime(r.mtime());
                zos.putNextEntry(e);
                InputStream in = r.open(0);
                try {
                    int k;
                    while ((k = in.read(buf)) != -1) zos.write(buf, 0, k);
                } finally {
                    in.close();
                }
                zos.closeEntry();
            } finally {
                r.close();
            }
        }
        zos.finish();
        zos.flush();
    }

    static String mime(String name) {
        String e = Thumbs.ext(name);
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("png")) return "image/png";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("heic") || e.equals("heif")) return "image/heic";
        if (e.equals("bmp")) return "image/bmp";
        if (e.equals("mp4") || e.equals("m4v")) return "video/mp4";
        if (e.equals("mov")) return "video/quicktime";
        if (e.equals("3gp")) return "video/3gpp";
        if (e.equals("webm")) return "video/webm";
        if (e.equals("mkv")) return "video/x-matroska";
        if (e.equals("pdf")) return "application/pdf";
        if (e.equals("txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static String encodeRfc5987(String s) {
        try {
            return java.net.URLEncoder.encode(s, UTF8).replace("+", "%20");
        } catch (Exception e) {
            return "fichero";
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            b.write(buf, 0, r);
            if (b.size() > max) throw new IOException("Petición demasiado grande");
        }
        return b.toByteArray();
    }

    private static void sendHead(OutputStream out, int code, String type, long len, String extra) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + extra
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(ISO));
    }

    // ---------- usuarios, bloqueo y sesiones ----------

    private void login(String ip, String method, Map<String, String> params, String password, OutputStream out)
            throws IOException, JSONException {
        if (!method.equals("POST") || password == null) {
            error(out, 400, "Falta la contraseña");
            return;
        }
        String user = Users.canonical(params.get("user"));
        if (user == null) {
            error(out, 400, "Ese usuario no existe");
            return;
        }
        if (locked(ip)) {
            error(out, 429, "Demasiadas contraseñas incorrectas. Espera 5 minutos.");
            return;
        }
        boolean created = false;
        if (!Users.hasPassword(ctx, user)) {
            if (password.length() < Users.MIN_LENGTH) {
                error(out, 400, "La contraseña tiene que tener al menos " + Users.MIN_LENGTH + " caracteres");
                return;
            }
            created = Users.setFirst(ctx, user, password);
        }
        if (!created && !Users.check(ctx, user, password)) {
            fail(ip);
            error(out, 403, "Contraseña incorrecta"); // 403 y no 401: así el móvil puede leer el mensaje
            return;
        }
        clearFails(ip);
        boolean app = "app".equals(params.get("client"));
        String token = app ? Users.newSession(ctx, user, Users.APP_MS) : Users.newWebSession(user);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("user", user);
        o.put("created", created);
        if (app) o.put("token", token);
        byte[] b = o.toString().getBytes(UTF8);
        sendHead(out, 200, "application/json; charset=utf-8", b.length, app ? "Cache-Control: no-store\r\n"
                : "Set-Cookie: subelo_s=" + token + "; Path=/; HttpOnly; Secure; SameSite=Strict\r\nCache-Control: no-store\r\n");
        out.write(b);
        out.flush();
    }

    private void changePassword(String ip, String method, String user, String password, String newPassword, String webToken,
                                boolean phones, OutputStream out) throws IOException, JSONException {
        if (!method.equals("POST") || password == null || newPassword == null) {
            error(out, 400, "Falta la contraseña");
            return;
        }
        if (locked(ip)) {
            error(out, 429, "Demasiadas contraseñas incorrectas. Espera 5 minutos.");
            return;
        }
        if (!Users.check(ctx, user, password)) {
            fail(ip);
            error(out, 403, "La contraseña actual no es esa");
            return;
        }
        clearFails(ip);
        if (newPassword.length() < Users.MIN_LENGTH) {
            error(out, 400, "La contraseña nueva tiene que tener al menos " + Users.MIN_LENGTH + " caracteres");
            return;
        }
        Users.change(ctx, user, newPassword, webToken, phones);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        json(out, 200, o);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CHECKING = new java.util.concurrent.atomic.AtomicBoolean();

    /** Datos del box para los ajustes de la web; si hace rato que no se mira GitHub, lo mira de fondo. */
    private void box(Store st, boolean available, OutputStream out) throws IOException, JSONException {
        JSONObject o = new JSONObject();
        o.put("model", Build.MODEL);
        o.put("version", Updater.installedName(ctx));
        o.put("versionCode", Updater.installedCode(ctx));
        o.put("storage", st == null ? JSONObject.NULL : st.describe());
        o.put("available", available);
        o.put("free", available ? st.free() : -1);
        o.put("total", available ? st.total() : -1);
        long last = Updater.lastCheck(ctx);
        Updater.Release r = Updater.cached(ctx);
        o.put("checked", last);
        List<String> ips = Net.localIps();
        o.put("webPort", port + (Config.WEB_PORT - Config.PORT));
        o.put("webUrl", webServer == null || ips.isEmpty() ? JSONObject.NULL : "https://" + ips.get(0) + ":" + (port + (Config.WEB_PORT - Config.PORT)));
        o.put("caThumbprint", Tls.caThumbprint(ctx.getFilesDir()));
        if (r != null) o.put("update", r.name);
        if (System.currentTimeMillis() - last > 10 * 60 * 1000L && CHECKING.compareAndSet(false, true)) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Updater.checkThrottled(ctx);
                    } finally {
                        CHECKING.set(false);
                    }
                }
            }, "subelo-update-check");
            t.setDaemon(true);
            t.start();
        }
        o.put("checking", CHECKING.get());
        json(out, 200, o);
    }

    private synchronized boolean locked(String ip) {
        long now = System.currentTimeMillis();
        while (!recentFails.isEmpty() && now - recentFails.peekFirst() > 10 * 60 * 1000L) recentFails.pollFirst();
        if (recentFails.size() >= 30) return true; // demasiados fallos en toda la red
        long[] f = fails.get(ip);
        return f != null && f[1] > now;
    }

    private synchronized void fail(String ip) {
        long now = System.currentTimeMillis();
        recentFails.addLast(now);
        long[] f = fails.get(ip);
        if (f == null) {
            if (fails.size() > 1000) fails.clear();
            f = new long[2];
            fails.put(ip, f);
        }
        f[0]++;
        if (f[0] >= 5) {
            f[0] = 0;
            f[1] = now + LOCK_MS;
        }
    }

    private synchronized void clearFails(String ip) {
        fails.remove(ip);
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    // ---------- utilidades ----------

    /** Normaliza una ruta relativa: quita "..", barras sobrantes y caracteres no válidos en discos FAT/exFAT. */
    static String cleanPath(String p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : p.replace('\\', '/').split("/")) {
            String s = cleanName(seg);
            if (s == null) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Nombre de algo que ya existe en la carpeta: sin barras ni «..». */
    static boolean existingName(String n) {
        return n != null && !n.isEmpty() && !n.equals(".") && !n.equals("..") && n.indexOf('/') < 0
                && n.indexOf('\\') < 0 && n.indexOf('\0') < 0;
    }

    static String cleanName(String n) {
        if (n == null) return null;
        n = n.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch < 32 || "/\\:*?\"<>|".indexOf(ch) >= 0) ch = '_';
            sb.append(ch);
        }
        String s = sb.toString();
        if (s.startsWith(".")) s = "_" + s.substring(1);
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }

    private static Map<String, String> parseQuery(String q) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        if (q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i >= 0 ? kv.substring(0, i) : kv;
            String v = i >= 0 ? kv.substring(i + 1) : "";
            m.put(URLDecoder.decode(k, UTF8), URLDecoder.decode(v, UTF8));
        }
        return m;
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Línea demasiado larga");
        }
        if (!any) return null;
        return b.toString(ISO);
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) b.write(buf, 0, r);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void error(OutputStream out, int code, String msg) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("error", msg);
            json(out, code, o);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void json(OutputStream out, int code, JSONObject o) throws IOException {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes(UTF8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(ISO));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** Cuerpo con Content-Length. */
    static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long len) {
            this.in = in;
            this.left = Math.max(0, len);
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new EOFException("Conexión cortada");
            left -= r;
            return r;
        }
    }

    /** Cuerpo con Transfer-Encoding: chunked. */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long left;
        private boolean eof;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (left == 0) {
                String line = readLine(in);
                if (line == null) throw new EOFException("Conexión cortada");
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                left = Long.parseLong(line.trim(), 16);
                if (left == 0) {
                    String t;
                    while ((t = readLine(in)) != null && !t.isEmpty()) {
                        // cabeceras finales: se ignoran
                    }
                    eof = true;
                    return -1;
                }
            }
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new EOFException("Conexión cortada");
            left -= r;
            if (left == 0) readLine(in);
            return r;
        }
    }
}
