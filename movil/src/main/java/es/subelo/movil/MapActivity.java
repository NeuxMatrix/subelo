package es.subelo.movil;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Mapa con dónde se hizo cada foto. Es la misma página que sirve el box (mapa.html), cargada desde
 * la app: las peticiones a «subelo.local» las contesta la app (con la conexión segura al box) y el
 * mapa de fondo viene de internet. Con {@link #pick} sirve para elegir dónde se hizo una foto
 * (📍 Ubicación) y devuelve «lat» y «lon» (o «clear» para quitar la puesta a mano).
 */
public class MapActivity extends Activity {
    private static final String HOST = "subelo.local";

    /**
     * Para startActivityForResult: elegir el sitio de n cosas (de ellas, «folders» carpetas); info = lo que
     * dice el box ({lat, lon, manual, con}); other = el otro usuario, para preguntar «solo yo / con él».
     */
    static Intent pick(Context c, JSONObject info, int n, int folders, String other) {
        Intent i = new Intent(c, MapActivity.class).putExtra("pick", true).putExtra("n", n).putExtra("folders", folders);
        if (info != null && info.has("lat")) i.putExtra("lat", info.optDouble("lat")).putExtra("lon", info.optDouble("lon"));
        if (info != null) i.putExtra("manual", info.optBoolean("manual"));
        if (info != null && info.optJSONArray("con") != null) i.putExtra("con", info.optJSONArray("con").optString(0, ""));
        if (info != null && info.optJSONArray("heredado") != null) {
            i.putExtra("heredado", info.optJSONArray("heredado").optString(0, ""));
        }
        if (other != null) i.putExtra("other", other);
        return i;
    }

    private BoxClient bc;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.setBackgroundColor(Brutal.CREAM);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "SubeloApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null; // el mapa de fondo, de internet
                return serve(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // enlaces de créditos del mapa
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        setContentView(web);
        String url = "https://" + HOST + "/mapa.html?app=1&tema=" + (Brutal.dark ? "oscuro" : "claro");
        Intent in = getIntent();
        if (in.getBooleanExtra("pick", false)) {
            url += "&pick=1&n=" + in.getIntExtra("n", 1) + "&carpetas=" + in.getIntExtra("folders", 0)
                    + (in.getBooleanExtra("manual", false) ? "&manual=1" : "")
                    + (in.getStringExtra("other") != null ? "&otro=" + Uri.encode(in.getStringExtra("other")) : "")
                    + (in.getStringExtra("con") != null ? "&con=" + Uri.encode(in.getStringExtra("con")) : "")
                    + (in.getStringExtra("heredado") != null ? "&heredado=" + Uri.encode(in.getStringExtra("heredado")) : "");
            if (in.hasExtra("lat")) {
                url += String.format(java.util.Locale.US, "&lat=%.6f&lon=%.6f", in.getDoubleExtra("lat", 0), in.getDoubleExtra("lon", 0));
            }
        }
        web.loadUrl(url);
    }

    private WebResourceResponse serve(Uri u) {
        String path = u.getPath() == null ? "" : u.getPath();
        try {
            if (path.equals("/mapa.html")) return asset("mapa.html", "text/html");
            if (path.startsWith("/static/")) {
                String n = path.substring(8);
                if (n.matches("(leaflet|markercluster)\\.(js|css)")) {
                    return asset("mapa/" + n, n.endsWith(".js") ? "text/javascript" : "text/css");
                }
                if (n.matches("[A-Za-z-]+\\.ttf")) return asset("fonts/" + n, "font/ttf");
                return status(404, "No encontrado");
            }
            if (path.equals("/api/places")) {
                return new WebResourceResponse("application/json", "UTF-8",
                        new ByteArrayInputStream(client().places().getBytes("UTF-8")));
            }
            if (path.equals("/api/thumb")) {
                byte[] b = client().thumb(param(u, "path"), param(u, "name"));
                if (b == null) return status(404, "Sin miniatura");
                HashMap<String, String> h = new HashMap<String, String>();
                h.put("Cache-Control", "max-age=604800");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
            }
        } catch (BoxClient.BoxException e) {
            return status(e.code, e.getMessage());
        } catch (IOException e) {
            return status(502, e.getMessage());
        }
        return status(404, "No encontrado");
    }

    private synchronized BoxClient client() throws IOException {
        if (bc == null) bc = BoxClient.connect(this);
        return bc;
    }

    private WebResourceResponse asset(String name, String mime) throws IOException {
        InputStream in = getAssets().open(name);
        return new WebResourceResponse(mime, "UTF-8", in);
    }

    private static WebResourceResponse status(int code, String msg) {
        String body = "{\"error\":" + JSONObject.quote(msg == null ? "Error" : msg) + "}";
        try {
            return new WebResourceResponse("application/json", "UTF-8", code, code >= 400 && code < 600 ? "Error" : "OK",
                    new HashMap<String, String>(), new ByteArrayInputStream(body.getBytes("UTF-8")));
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private static String param(Uri u, String k) {
        String v = u.getQueryParameter(k);
        return v == null ? "" : v;
    }

    /** Lo que la página puede pedir a la app. */
    private final class Bridge {
        /** Elegir sitio: el elegido, y con quién se comparte en el mapa («» = solo yo). */
        @JavascriptInterface
        public void sitio(final double lat, final double lon, final String con) {
            done(new Intent().putExtra("lat", lat).putExtra("lon", lon).putExtra("con", con == null ? "" : con));
        }

        /** Elegir sitio: quitar la ubicación puesta a mano. */
        @JavascriptInterface
        public void quitar() {
            done(new Intent().putExtra("clear", true));
        }

        @JavascriptInterface
        public void cancelar() {
            done(null);
        }

        private void done(final Intent result) {
            if (!getIntent().getBooleanExtra("pick", false)) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (result != null) setResult(RESULT_OK, result);
                    finish();
                }
            });
        }

        /** Abre el visor del móvil con las fotos [[carpeta, nombre, tamaño], …] empezando en la elegida. */
        @JavascriptInterface
        public void ver(String json, final int index) {
            final List<BoxClient.RemoteFile> files = new ArrayList<BoxClient.RemoteFile>();
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONArray x = a.getJSONArray(i);
                    BoxClient.RemoteFile f = new BoxClient.RemoteFile(x.getString(0), x.getString(1), x.optLong(2, -1), 0);
                    f.own = x.optInt(3, 1) == 1; // las del mapa común son del otro: solo se ven
                    if (!f.own && !x.optString(4).isEmpty()) f.from = "🔒 de " + x.optString(4);
                    files.add(f);
                }
            } catch (Exception e) {
                return;
            }
            if (files.isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ViewerActivity.open(MapActivity.this, files, Math.min(Math.max(0, index), files.size() - 1));
                }
            });
        }
    }
}
