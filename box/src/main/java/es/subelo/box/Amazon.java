package es.subelo.box;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Amazon Photos, con la sesión de la web (las cookies de amazon.es tras entrar desde el móvil).
 * Amazon no tiene una API pública para esto: son las mismas llamadas que hace su web, así que
 * pueden dejar de funcionar si Amazon las cambia.
 *
 * Usa un cliente HTTP propio porque HttpURLConnection no admite PATCH (añadir a un álbum).
 */
final class Amazon {
    /** Error de Amazon con su código HTTP (401 = la sesión ha caducado). */
    static final class AmazonException extends IOException {
        final int code;

        AmazonException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final class Album {
        final String id, name;
        final int count;

        Album(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String V2 = "resourceVersion=V2&ContentType=JSON";

    private final String cookie, sessionId, drive, upload;

    Amazon(String cookie, String tld) {
        this.cookie = cookie;
        this.sessionId = cookieValue(cookie, "session-id");
        String d = System.getProperty("subelo.amazon.drive"); // solo para las pruebas
        String u = System.getProperty("subelo.amazon.upload");
        boolean na = tld.equals("com") || tld.equals("ca") || tld.equals("com.mx") || tld.equals("com.br");
        this.drive = d != null ? d : "https://www.amazon." + tld + "/drive/v1";
        this.upload = u != null ? u : "https://content-" + (na ? "na" : "eu") + ".drive.amazonaws.com/cdproxy/nodes";
    }

    static String cookieValue(String cookies, String name) {
        for (String part : cookies.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).trim().equals(name)) return part.substring(i + 1).trim();
        }
        return null;
    }

    /** Comprueba la sesión: la carpeta raíz de Amazon Drive. */
    String rootId() throws IOException {
        return root()[0];
    }

    /**
     * La carpeta raíz y el cliente de Amazon dueño de la cuenta ({id, ownerId}; ownerId puede ser
     * null). Las dos cosas son únicas por cuenta: sirven para saber si dos sesiones son de la misma.
     */
    String[] root() throws IOException {
        JSONObject o = json("GET", drive + "/nodes?filters=" + enc("isRoot:true") + "&asset=ALL&tempLink=false&" + V2, null);
        try {
            JSONObject n = o.getJSONArray("data").getJSONObject(0);
            String owner = n.optString("ownerId", "");
            return new String[]{n.getString("id"), owner.isEmpty() ? null : owner};
        } catch (JSONException e) {
            throw new IOException("Amazon no ha devuelto la carpeta principal");
        }
    }

    /** Los álbumes de la cuenta. */
    List<Album> albums() throws IOException {
        IOException last = null;
        for (String f : new String[]{"kind:(VISUAL_COLLECTION*) AND status:AVAILABLE", "kind:VISUAL_COLLECTION"}) {
            try {
                JSONObject o = json("GET", drive + "/nodes?filters=" + enc(f) + "&limit=200&asset=NONE&tempLink=false&" + V2, null);
                List<Album> out = new ArrayList<Album>();
                JSONArray a = o.optJSONArray("data");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject n = a.optJSONObject(i);
                    if (n == null || n.optString("id").isEmpty()) continue;
                    if ("TRASH".equals(n.optString("status"))) continue;
                    JSONObject cp = n.optJSONObject("collectionProperties");
                    out.add(new Album(n.getString("id"), n.optString("name", "Álbum"),
                            cp == null ? -1 : cp.optInt("childCount", -1)));
                }
                return out;
            } catch (AmazonException e) {
                if (e.code == 401) throw e;
                last = e;
            } catch (JSONException e) {
                last = new IOException("Respuesta rara de Amazon");
            }
        }
        throw last;
    }

    /** El álbum de un enlace para compartir (…/photos/share/XXXX). */
    Album albumFromLink(String link) throws IOException {
        String id = link.trim();
        int i = id.indexOf("/share/");
        if (i >= 0) id = id.substring(i + 7);
        int cut = id.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int k = id.indexOf(c);
            if (k >= 0 && k < cut) cut = k;
        }
        id = id.substring(0, cut);
        if (!id.matches("[A-Za-z0-9_-]{6,}")) throw new IOException("Ese enlace no parece de un álbum de Amazon Photos");
        JSONObject o = json("GET", drive + "/shares/" + id + "?shareId=" + id + "&asset=NONE&" + V2, null);
        JSONObject n = o.optJSONObject("nodeInfo");
        if (n == null) n = o.optJSONObject("node");
        if (n == null || n.optString("id").isEmpty()) throw new IOException("Amazon no ha dicho qué álbum es ese enlace");
        return new Album(n.optString("id"), n.optString("name", "Álbum"), -1);
    }

    /** Carpeta de Amazon Drive donde dejamos las fotos (la crea si no está). */
    String folder(String parentId, String name) throws IOException {
        String tryName = name;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("kind", "FOLDER");
                body.put("name", tryName);
                body.put("parents", new JSONArray().put(parentId));
                body.put("resourceVersion", "V2");
                body.put("ContentType", "JSON");
                Resp r = request("POST", drive + "/nodes", "application/json", body.toString().getBytes("UTF-8"), null, -1);
                String id = r.code == 409 || r.code / 100 == 2 ? nodeId(r.json()) : null;
                if (id == null) throw fail(r);
                if (r.code / 100 == 2) return id;
                // ya había una con ese nombre: vale, salvo si es una que está en la papelera
                JSONObject n = nodeIfMine(id);
                if (n != null && !"TRASH".equals(n.optString("status"))) return id;
                tryName = name + " (" + (attempt + 1) + ")";
            } catch (JSONException e) {
                throw new IOException(e.getMessage());
            }
        }
        throw new IOException("Amazon no deja crear la carpeta " + name);
    }

    /**
     * Lo que hay dentro de una carpeta (sin lo que está en la papelera): carpetas y archivos, tal
     * como los da Amazon. Páginas de 200, con «startToken» si Amazon lo da y si no con «offset».
     */
    List<JSONObject> children(String parentId) throws IOException {
        List<JSONObject> out = new ArrayList<JSONObject>();
        String token = null;
        boolean byOffset = false, byFilter = false;
        for (int page = 0; page < 200; page++) {
            String url = drive + "/nodes/" + enc(parentId) + "/children?asset=NONE&tempLink=false&limit=200"
                    + "&filters=" + enc("status:AVAILABLE")
                    + (byOffset ? "&offset=" + out.size() : token == null ? "" : "&startToken=" + enc(token)) + "&" + V2;
            String filtered = drive + "/nodes?filters=" + enc("parents:" + parentId + " AND status:AVAILABLE")
                    + "&asset=NONE&tempLink=false&limit=200" + (byOffset ? "&offset=" + out.size() : "") + "&" + V2;
            JSONObject o;
            if (byFilter) {
                o = json("GET", filtered, null);
            } else {
                try {
                    o = json("GET", url, null);
                } catch (AmazonException e) {
                    if (e.code == 401 || page > 0) throw e;
                    // por si esta versión de la web no tiene «/children»: lo mismo con un filtro
                    byFilter = true;
                    o = json("GET", filtered, null);
                }
            }
            JSONArray a = o.optJSONArray("data");
            int got = a == null ? 0 : a.length();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null || x.optString("id").isEmpty() || "TRASH".equals(x.optString("status"))) continue;
                out.add(x);
            }
            token = o.optString("nextToken", "");
            if (!token.isEmpty() && !byOffset && !byFilter) continue;
            int total = o.optInt("count", -1);
            if (got < 200 || (total >= 0 && out.size() >= total) || out.size() >= 9800) break;
            byOffset = true;
        }
        return out;
    }

    /**
     * Lleva a la papelera de Amazon Fotos (de ahí se borra sola a los 90 días). Una carpeta se
     * lleva con todo lo que tiene dentro.
     */
    void trash(List<String> ids) throws IOException {
        for (int from = 0; from < ids.size(); from += 50) {
            try {
                JSONObject body = new JSONObject();
                body.put("recurse", "true");
                body.put("op", "add");
                body.put("filters", "");
                body.put("conflictResolution", "RENAME");
                body.put("value", new JSONArray(ids.subList(from, Math.min(ids.size(), from + 50))));
                body.put("resourceVersion", "V2");
                body.put("ContentType", "JSON");
                Resp r = request("PATCH", drive + "/trash", "application/json", body.toString().getBytes("UTF-8"), null, -1);
                if (r.code / 100 != 2) throw fail(r);
            } catch (JSONException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    /**
     * Sube una foto a la carpeta y devuelve su identificador en Amazon. Si ya estaba (misma foto),
     * devuelve la que había; si hay otra distinta con ese nombre, la sube con otro nombre.
     */
    String upload(String parentId, String name, Opener data, long size) throws IOException {
        return uploadNode(parentId, name, data, size).id;
    }

    /** Lo subido: identificador y si está seguro de que es un archivo de esa carpeta. */
    static final class Uploaded {
        final String id;
        final boolean inFolder;

        Uploaded(String id, boolean inFolder) {
            this.id = id;
            this.inFolder = inFolder;
        }
    }

    Uploaded uploadNode(String parentId, String name, Opener data, long size) throws IOException {
        return uploadNode(parentId, name, data, size, false);
    }

    /** Amazon no admite «suppress=deduplication»: ya no se pide (hasta que se reinicie el box). */
    private static volatile boolean noCopies;

    /**
     * copy: que la suba aunque la misma foto ya esté en otra carpeta de la cuenta (para la copia de
     * seguridad: si no, Amazon la deja donde estaba y no entra en la carpeta).
     */
    Uploaded uploadNode(String parentId, String name, Opener data, long size, boolean copy) throws IOException {
        String tryName = name;
        boolean plain = false;
        for (int attempt = 1; attempt <= 5; attempt++) {
            boolean suppress = copy && !noCopies && !plain;
            InputStream in = data.open();
            Resp r;
            try {
                r = request("POST", upload + "?name=" + enc(tryName) + "&kind=FILE&parentNodeId=" + enc(parentId)
                        + (suppress ? "&suppress=deduplication" : ""), "application/octet-stream", null, in, size);
            } finally {
                in.close();
            }
            if (suppress && r.code == 400) { // ¿no lo entiende? se prueba como siempre
                plain = true;
                attempt--;
                continue;
            }
            if (plain && r.code != 400) noCopies = true; // así sí: no lo entiende
            if (r.code / 100 == 2) {
                String id = nodeId(r.json());
                if (id == null) throw new IOException("Amazon no ha devuelto el identificador de " + name);
                return new Uploaded(id, true);
            }
            if (r.code != 409) throw fail(r);
            String existing = nodeId(r.json());
            if (existing == null) throw fail(r);
            JSONObject node = node(existing);
            JSONObject cp = node == null ? null : node.optJSONObject("contentProperties");
            if (cp != null && cp.optLong("size", -1) == size) { // la misma foto ya estaba
                return new Uploaded(existing, hasParent(node, parentId));
            }
            int dot = name.lastIndexOf('.');
            tryName = dot > 0 ? name.substring(0, dot) + " (" + (attempt + 1) + ")" + name.substring(dot)
                    : name + " (" + (attempt + 1) + ")";
        }
        throw new IOException("Demasiados nombres repetidos en Amazon para " + name);
    }

    static boolean hasParent(JSONObject node, String parentId) {
        JSONArray parents = node.optJSONArray("parents");
        for (int i = 0; parents != null && i < parents.length(); i++) if (parentId.equals(parents.optString(i))) return true;
        return false;
    }

    private JSONObject node(String nodeId) {
        try {
            return json("GET", drive + "/nodes/" + enc(nodeId) + "?asset=NONE&tempLink=false&" + V2, null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * El archivo o carpeta, o null si esta cuenta no lo tiene (borrado, o de otra cuenta de Amazon).
     * Si la sesión ha caducado, falla con 401.
     */
    JSONObject nodeIfMine(String nodeId) throws IOException {
        Resp r = request("GET", drive + "/nodes/" + enc(nodeId) + "?asset=NONE&tempLink=false&" + V2, null, null, null, -1);
        if (r.code / 100 == 2) return r.json();
        if (r.code == 400 || r.code == 403 || r.code == 404) {
            root(); // ¿no es suyo, o es que la sesión ya no vale? Si no vale, esto falla con 401
            return null;
        }
        throw fail(r);
    }

    /**
     * Oculta una foto en Amazon Fotos: deja de salir en la línea de tiempo, en los recuerdos y en
     * el Echo (sigue en su carpeta y en «Ocultos», para quien entre con esa cuenta).
     * Si Amazon no deja tocar esa foto, falla con su código (403, 404…), no con 401.
     */
    void hide(String nodeId) throws IOException {
        setHidden(nodeId, true);
    }

    void setHidden(String nodeId, boolean hidden) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("settings", new JSONObject().put("hidden", hidden));
            body.put("resourceVersion", "V2");
            body.put("ContentType", "JSON");
            Resp r = request("PATCH", drive + "/nodes/" + enc(nodeId), "application/json", body.toString().getBytes("UTF-8"), null, -1);
            if (r.code / 100 == 2) {
                // si contesta con el archivo, se comprueba que de verdad ha cambiado
                JSONObject st = r.json().optJSONObject("settings");
                if (st != null && st.has("hidden") && st.optBoolean("hidden") != hidden) {
                    throw new AmazonException(422, "Amazon no ha cambiado si se ve o no");
                }
                return;
            }
            if (r.code == 400 || r.code == 403 || r.code == 404) {
                String msg = r.json().optString("message", "");
                throw new AmazonException(r.code, "Amazon respondió " + r.code + (msg.isEmpty() ? "" : ": " + msg));
            }
            throw fail(r);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    void addToAlbum(String albumId, List<String> ids) throws IOException {
        children(albumId, "add", ids);
    }

    void removeFromAlbum(String albumId, List<String> ids) throws IOException {
        children(albumId, "remove", ids);
    }

    private void children(String albumId, String op, List<String> ids) throws IOException {
        for (int from = 0; from < ids.size(); from += 25) {
            try {
                JSONObject body = new JSONObject();
                body.put("op", op);
                body.put("value", new JSONArray(ids.subList(from, Math.min(ids.size(), from + 25))));
                body.put("resourceVersion", "V2");
                body.put("ContentType", "JSON");
                Resp r = request("PATCH", drive + "/nodes/" + enc(albumId) + "/children", "application/json",
                        body.toString().getBytes("UTF-8"), null, -1);
                if (r.code / 100 != 2) throw fail(r);
            } catch (JSONException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    interface Opener {
        InputStream open() throws IOException;
    }

    // ---------- HTTP ----------

    private static final class Resp {
        int code;
        byte[] body;

        JSONObject json() {
            try {
                return new JSONObject(new String(body, "UTF-8"));
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private JSONObject json(String method, String url, byte[] body) throws IOException {
        Resp r = request(method, url, body == null ? null : "application/json", body, null, -1);
        if (r.code / 100 != 2) throw fail(r);
        try {
            return new JSONObject(new String(r.body, "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Respuesta rara de Amazon");
        }
    }

    private static String nodeId(JSONObject o) {
        String id = o.optString("id", null);
        if (id != null && !id.isEmpty()) return id;
        JSONObject info = o.optJSONObject("info");
        id = info == null ? null : info.optString("nodeId", null);
        return id == null || id.isEmpty() ? null : id;
    }

    private static AmazonException fail(Resp r) {
        if (r.code == 401 || r.code == 403 || r.code / 100 == 3) {
            return new AmazonException(401, "La sesión de Amazon ha caducado: vuelve a conectar Amazon Photos desde el móvil");
        }
        String msg = r.json().optString("message", "");
        return new AmazonException(r.code, "Amazon respondió " + r.code + (msg.isEmpty() ? "" : ": " + msg));
    }

    private Resp request(String method, String url, String type, byte[] body, InputStream stream, long streamLen)
            throws IOException {
        URL u = new URL(url);
        boolean tls = u.getProtocol().equals("https");
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), 20000);
        if (tls) {
            // encima de la conexión normal, con el nombre del servidor (SNI) y comprobando su certificado
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, u.getHost(), port, true);
            ss.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(u.getHost(), ss.getSession())) {
                ss.close();
                throw new IOException("El certificado de " + u.getHost() + " no es válido");
            }
            s = ss;
        }
        try {
            s.setSoTimeout(120000);
            OutputStream out = s.getOutputStream();
            String file = u.getFile().isEmpty() ? "/" : u.getFile();
            StringBuilder h = new StringBuilder();
            h.append(method).append(' ').append(file).append(" HTTP/1.1\r\n");
            h.append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n");
            h.append("User-Agent: ").append(UA).append("\r\n");
            h.append("Accept: application/json, text/plain, */*\r\n");
            h.append("Accept-Encoding: identity\r\n");
            h.append("Cookie: ").append(cookie).append("\r\n");
            if (sessionId != null) h.append("x-amzn-sessionid: ").append(sessionId).append("\r\n");
            if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
            long len = body != null ? body.length : stream != null ? streamLen : 0;
            if (body != null || stream != null || !method.equals("GET")) h.append("Content-Length: ").append(len).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            if (body != null) out.write(body);
            if (stream != null) {
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                int n;
                while (sent < streamLen && (n = stream.read(buf, 0, (int) Math.min(buf.length, streamLen - sent))) != -1) {
                    out.write(buf, 0, n);
                    sent += n;
                }
                if (sent != streamLen) throw new IOException("La foto ha cambiado mientras se subía");
            }
            out.flush();
            return read(new BufferedInputStream(s.getInputStream(), 16 * 1024));
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Resp read(InputStream in) throws IOException {
        Resp r = new Resp();
        String status = HttpServer.readLine(in);
        while (status != null && status.startsWith("HTTP/1.1 100")) { // «100 Continue»: viene otra
            String l;
            while ((l = HttpServer.readLine(in)) != null && !l.isEmpty()) ;
            status = HttpServer.readLine(in);
        }
        if (status == null || !status.startsWith("HTTP/")) throw new IOException("Amazon no ha contestado");
        String[] p = status.split(" ");
        r.code = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        Map<String, String> headers = new HashMap<String, String>();
        String l;
        while ((l = HttpServer.readLine(in)) != null && !l.isEmpty()) {
            int i = l.indexOf(':');
            if (i > 0) headers.put(l.substring(0, i).trim().toLowerCase(Locale.US), l.substring(i + 1).trim());
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int max = 8 * 1024 * 1024;
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            while (true) {
                String size = HttpServer.readLine(in);
                if (size == null) break;
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                if (n == 0) break;
                copy(in, bo, n, max);
                HttpServer.readLine(in); // fin del trozo
            }
        } else if (headers.containsKey("content-length")) {
            copy(in, bo, Long.parseLong(headers.get("content-length")), max);
        } else {
            copy(in, bo, Long.MAX_VALUE, max);
        }
        r.body = bo.toByteArray();
        return r;
    }

    private static void copy(InputStream in, ByteArrayOutputStream bo, long n, int max) throws IOException {
        byte[] buf = new byte[16 * 1024];
        while (n > 0) {
            int k = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (k == -1) break;
            if (bo.size() + k > max) throw new IOException("Respuesta de Amazon demasiado grande");
            bo.write(buf, 0, k);
            n -= k;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
