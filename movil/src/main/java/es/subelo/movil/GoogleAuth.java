package es.subelo.movil;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Permiso de Google para «Traer de Google Fotos». Google no deja pedirlo dentro de la app, así que
 * se abre el navegador; al aceptar, Google vuelve a http://127.0.0.1:puerto (este mismo móvil), donde
 * esto escucha. El código se pasa al box (que lo canjea con su cliente de Google) y el navegador
 * salta directamente al selector de Google Fotos.
 */
final class GoogleAuth {
    private static final String TAG = "SubeloGoogle";
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    /** Lo que se espera a que vuelva del navegador. */
    private static final int WAIT_MS = 15 * 60 * 1000;

    interface Listener {
        /** En un hilo de fondo: null si el box ya tiene el permiso, o el error. */
        void done(String error);
    }

    /** El permiso que se está pidiendo ahora (sobrevive a que la pantalla se cierre). */
    static volatile GoogleAuth current;

    final String url;
    private final String verifier, state, redirect, dest;
    private final ServerSocket server;
    private final Context app;
    private volatile Listener listener;
    private volatile boolean finished;

    private GoogleAuth(Context c, String clientId, String scope, String dest) throws IOException {
        app = c.getApplicationContext();
        this.dest = dest;
        verifier = random(48);
        state = random(16);
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        redirect = "http://127.0.0.1:" + server.getLocalPort();
        url = Uri.parse(AUTH).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", scope)
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("prompt", "select_account") // con varias cuentas de Google, que pregunte cuál
                .build().toString();
    }

    /** Empieza a escuchar; hay que abrir {@link #url} en el navegador. */
    static GoogleAuth start(Context c, String clientId, String scope, String dest, Listener l) throws IOException {
        GoogleAuth old = current;
        if (old != null) old.cancel();
        final GoogleAuth a = new GoogleAuth(c, clientId, scope, dest);
        a.listener = l;
        current = a;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                a.serve();
            }
        }, "subelo-google-auth");
        t.setDaemon(true);
        t.start();
        return a;
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean waiting() {
        return !finished;
    }

    void cancel() {
        finished = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        if (current == this) current = null;
    }

    private void serve() {
        String error = "No has terminado de dar permiso en Google";
        try {
            server.setSoTimeout(WAIT_MS);
            while (!finished) {
                Socket s = server.accept();
                try {
                    s.setSoTimeout(15000);
                    String result = handle(s);
                    if (result != null) {
                        error = result.isEmpty() ? null : result;
                        break;
                    }
                } finally {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            error = "Se acabó el tiempo para dar permiso en Google. Vuelve a empezar.";
        } catch (IOException e) {
            if (finished) return; // cancelado
            error = e.getMessage();
        }
        boolean wasCancelled = finished;
        cancel();
        Listener l = listener;
        if (!wasCancelled && l != null) l.done(error);
    }

    /** Atiende una petición del navegador. null: seguir esperando; "": hecho; otro: error. */
    private String handle(Socket s) throws IOException {
        String line = readHead(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String[] parts = line == null ? new String[0] : line.split(" ");
        if (parts.length < 2 || !parts[0].equals("GET")) {
            page(out, 400, "Petición rara");
            return null;
        }
        Uri u = Uri.parse("http://127.0.0.1" + parts[1]);
        if (!"/".equals(u.getPath()) && u.getPath() != null && !u.getPath().isEmpty()) {
            page(out, 404, "No encontrado"); // favicon y demás
            return null;
        }
        if (!state.equals(u.getQueryParameter("state"))) {
            page(out, 400, "Esta respuesta de Google no es de esta petición. Vuelve a Súbelo y empieza otra vez.");
            return null;
        }
        String err = u.getQueryParameter("error");
        if (err != null) {
            String msg = err.equals("access_denied") ? "No has dado permiso a Súbelo." : "Google ha dicho que no (" + err + ").";
            page(out, 200, "✖ " + msg + " Vuelve a Súbelo.");
            return msg;
        }
        String code = u.getQueryParameter("code");
        if (code == null) {
            page(out, 400, "Google no ha mandado el permiso. Vuelve a Súbelo y empieza otra vez.");
            return "Google no ha mandado el permiso";
        }
        try {
            JSONObject st = BoxClient.connect(app).googleStart(code, verifier, redirect, dest);
            JSONObject job = st.optJSONObject("job");
            String picker = job == null ? null : job.optString("pickerUri", null);
            if (picker == null || !picker.startsWith("https://") || picker.indexOf('\r') >= 0 || picker.indexOf('\n') >= 0) throw new IOException("Google no ha abierto el selector de fotos");
            // directo al selector de Google Fotos
            byte[] body = ("<a href=\"" + esc(picker).replace("\"", "&quot;") + "\">Elegir fotos</a>").getBytes("UTF-8");
            out.write(("HTTP/1.1 302 Found\r\nLocation: " + picker + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(body);
            out.flush();
            return "";
        } catch (IOException e) {
            Log.w(TAG, "start", e);
            String msg = e.getMessage() == null ? "No se pudo hablar con el box" : e.getMessage();
            page(out, 200, "✖ " + msg + "\n\nVuelve a Súbelo.");
            return msg;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String first = null;
        int c, total = 0;
        boolean empty = true;
        while ((c = in.read()) != -1 && total++ < 16384) {
            if (c == '\n') {
                if (first == null) first = line.toString("UTF-8").trim();
                if (empty) break; // línea vacía: fin de las cabeceras
                line.reset();
                empty = true;
            } else if (c != '\r') {
                line.write(c);
                empty = false;
            }
        }
        return first;
    }

    private static void page(OutputStream out, int code, String msg) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Súbelo</title><body style='font:18px sans-serif;margin:32px;background:#FFF4E6;color:#111'>"
                + "<h2>Súbelo</h2><p style='white-space:pre-wrap'>" + esc(msg) + "</p></body>";
        byte[] body = html.getBytes("UTF-8");
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : code == 404 ? " Not Found" : " Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String challenge(String verifier) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
            return Base64.encodeToString(h, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
