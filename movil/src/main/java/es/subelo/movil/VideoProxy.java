package es.subelo.movil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Pasarela local (solo dentro del móvil, 127.0.0.1) para que el reproductor de vídeo de Android pueda
 * ver los vídeos del box: el reproductor no acepta el certificado propio del box, así que habla con
 * esta pasarela y ella habla con el box por HTTPS comprobando su huella. Lleva un código secreto en la
 * dirección para que otras apps del móvil no puedan usarla.
 */
final class VideoProxy {
    private final BoxClient bc;
    private final String secret;
    private ServerSocket server;
    private volatile boolean running;

    VideoProxy(BoxClient bc) {
        this.bc = bc;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        secret = sb.toString();
    }

    synchronized String url(String dir, String name) throws IOException {
        if (server == null) {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "subelo-video");
            t.setDaemon(true);
            t.start();
        }
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + secret + "?path="
                + java.net.URLEncoder.encode(dir, "UTF-8") + "&name=" + java.net.URLEncoder.encode(name, "UTF-8");
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void loop() {
        ServerSocket ss = server;
        while (running && ss != null) {
            try {
                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(s);
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket s) {
        HttpURLConnection c = null;
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            String range = null;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                if (h.toLowerCase(Locale.US).startsWith("range:")) range = h.substring(6).trim();
            }
            String target = parts.length > 1 ? parts[1] : "";
            if (!target.startsWith("/" + secret + "?")) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("ISO-8859-1"));
                return;
            }
            String dir = "", name = "";
            for (String kv : target.substring(target.indexOf('?') + 1).split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                String v = URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                if (kv.startsWith("path=")) dir = v;
                if (kv.startsWith("name=")) name = v;
            }
            c = bc.openFile(dir, name, range);
            int code = c.getResponseCode();
            StringBuilder head = new StringBuilder("HTTP/1.1 " + code + " OK\r\n");
            for (String k : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
                String v = c.getHeaderField(k);
                if (v != null) head.append(k).append(": ").append(v).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            if (!"HEAD".equals(parts[0])) {
                InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (body != null) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = body.read(buf)) != -1) out.write(buf, 0, r);
                    body.close();
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // el reproductor cierra conexiones al saltar a otro punto del vídeo
        } finally {
            if (c != null) c.disconnect();
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        boolean any = false;
        while ((ch = in.read()) != -1) {
            any = true;
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) throw new IOException("línea larga");
        }
        return any ? b.toString("ISO-8859-1") : null;
    }
}
