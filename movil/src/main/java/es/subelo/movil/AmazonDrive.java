package es.subelo.movil;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Amazon Fotos directamente desde el móvil (sin el box), con la sesión guardada en AmazonSession:
 * para ver fuera de casa las carpetas con copia de seguridad. Solo lee: no sube ni borra nada.
 * Son las mismas llamadas que hace la web de Amazon Fotos (no hay API oficial).
 */
final class AmazonDrive {
    /** Carpeta de Amazon donde el box deja las copias (Backup.AMAZON_FOLDER en el box). */
    static final String BACKUP_FOLDER = "Súbelo Backup";

    static final class AmazonException extends IOException {
        final int code;

        AmazonException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final class Node {
        String id, name, tempLink, ownerId, contentType;
        boolean folder;
        long size;

        boolean isImage() {
            return contentType != null ? contentType.startsWith("image/") : Downloader.isImage(name);
        }

        boolean isVideo() {
            return contentType != null ? contentType.startsWith("video/") : Downloader.isVideo(name);
        }
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String V2 = "resourceVersion=V2&ContentType=JSON";

    private final String cookie, sessionId, drive, tld;

    AmazonDrive(String cookie, String tld) {
        this.cookie = cookie;
        this.tld = tld;
        this.sessionId = cookieValue(cookie, "session-id");
        this.drive = "https://www.amazon." + tld + "/drive/v1";
    }

    private static String cookieValue(String cookies, String name) {
        for (String part : cookies.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).trim().equals(name)) return part.substring(i + 1).trim();
        }
        return null;
    }

    /** La carpeta de copias de este usuario («Súbelo Backup/Fer»), o null si aún no hay ninguna. */
    Node userFolder(String user) throws IOException {
        JSONObject o = json(drive + "/nodes?filters=" + enc("isRoot:true") + "&asset=NONE&tempLink=false&" + V2);
        JSONArray d = o.optJSONArray("data");
        if (d == null || d.length() == 0) throw new IOException("Amazon no ha devuelto la carpeta principal");
        String root = d.optJSONObject(0).optString("id");
        Node backup = child(root, BACKUP_FOLDER);
        return backup == null ? null : child(backup.id, user);
    }

    private Node child(String parent, String name) throws IOException {
        for (Node n : children(parent)) if (n.folder && n.name.equals(name)) return n;
        return null;
    }

    /** Lo que hay dentro de una carpeta: primero las carpetas, luego los archivos, por nombre. */
    List<Node> children(String parent) throws IOException {
        List<Node> out = new ArrayList<Node>();
        String token = null;
        boolean byOffset = false, byFilter = false;
        for (int page = 0; page < 200; page++) {
            // páginas de 200: con «startToken» si Amazon lo da, y si no con «offset» (su web ya va así)
            String url = drive + "/nodes/" + enc(parent) + "/children?asset=ALL&tempLink=true&limit=200"
                    + "&filters=" + enc("status:AVAILABLE")
                    + (byOffset ? "&offset=" + out.size() : token == null ? "" : "&startToken=" + enc(token)) + "&" + V2;
            String filtered = drive + "/nodes?filters=" + enc("parents:" + parent + " AND status:AVAILABLE")
                    + "&asset=ALL&tempLink=true&limit=200" + (byOffset ? "&offset=" + out.size() : "") + "&" + V2;
            JSONObject o;
            if (byFilter) {
                o = json(filtered);
            } else {
                try {
                    o = json(url);
                } catch (AmazonException e) {
                    if (e.code == 401 || page > 0) throw e;
                    // por si esta versión de la web no tiene «/children»: lo mismo con un filtro
                    byFilter = true;
                    o = json(filtered);
                }
            }
            JSONArray a = o.optJSONArray("data");
            int got = a == null ? 0 : a.length();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null || x.optString("id").isEmpty()) continue;
                Node n = new Node();
                n.id = x.optString("id");
                n.name = x.optString("name", "");
                n.folder = "FOLDER".equals(x.optString("kind"));
                n.tempLink = x.optString("tempLink", null);
                n.ownerId = x.optString("ownerId", null);
                JSONObject cp = x.optJSONObject("contentProperties");
                if (cp != null) {
                    n.size = cp.optLong("size", -1);
                    n.contentType = cp.optString("contentType", null);
                }
                out.add(n);
            }
            token = o.optString("nextToken", "");
            if (!token.isEmpty() && !byOffset && !byFilter) continue;
            int total = o.optInt("count", -1);
            if (got < 200 || (total >= 0 && out.size() >= total) || out.size() >= 9800) break;
            byOffset = true;
        }
        Collections.sort(out, new Comparator<Node>() {
            @Override
            public int compare(Node x, Node y) {
                if (x.folder != y.folder) return x.folder ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return out;
    }

    /** La foto reducida a «px» de lado (miniatura o para verla en pantalla). */
    Bitmap image(Node n, int px) throws IOException {
        IOException last = null;
        if (n.tempLink != null && !n.tempLink.isEmpty()) {
            try {
                return decode(get(n.tempLink + (n.tempLink.contains("?") ? "&" : "?") + "viewBox=" + px, false), px);
            } catch (IOException e) {
                last = e;
            }
        }
        try {
            String u = "https://thumbnails-photos.amazon." + tld + "/v1/thumbnail/" + enc(n.id) + "?viewBox=" + px
                    + (n.ownerId == null ? "" : "&ownerId=" + enc(n.ownerId));
            return decode(get(u, true), px);
        } catch (IOException e) {
            throw last != null ? last : e;
        }
    }

    /** Dirección para ver un vídeo con el reproductor del móvil (caduca en un rato). */
    String videoLink(Node n) {
        return n.tempLink;
    }

    private static Bitmap decode(byte[] b, int px) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0) throw new IOException("Amazon no ha mandado una foto");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (bm == null) throw new IOException("Amazon no ha mandado una foto");
        return bm;
    }

    // ---------- HTTP ----------

    private JSONObject json(String url) throws IOException {
        try {
            return new JSONObject(new String(get(url, true), "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Respuesta rara de Amazon");
        }
    }

    private byte[] get(String url, boolean withSession) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, image/*, */*");
            if (withSession) {
                c.setRequestProperty("Cookie", cookie);
                if (sessionId != null) c.setRequestProperty("x-amzn-sessionid", sessionId);
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403 || code / 100 == 3) {
                throw new AmazonException(401, "La sesión de Amazon ha caducado: vuelve a entrar en Amazon");
            }
            if (code / 100 != 2) throw new AmazonException(code, "Amazon respondió " + code);
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int k;
                while ((k = in.read(buf)) != -1) {
                    bo.write(buf, 0, k);
                    if (bo.size() > 40 * 1024 * 1024) throw new IOException("Demasiado grande para verlo aquí");
                }
                return bo.toByteArray();
            } finally {
                in.close();
            }
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Sin conexión a Internet");
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
