package es.subelo.movil;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
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
 * mapa de fondo viene de internet.
 */
public class MapActivity extends Activity {
    private static final String HOST = "subelo.local";

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
        web.loadUrl("https://" + HOST + "/mapa.html?app=1&tema=" + (Brutal.dark ? "oscuro" : "claro"));
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
        /** Abre el visor del móvil con las fotos [[carpeta, nombre, tamaño], …] empezando en la elegida. */
        @JavascriptInterface
        public void ver(String json, final int index) {
            final List<BoxClient.RemoteFile> files = new ArrayList<BoxClient.RemoteFile>();
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONArray x = a.getJSONArray(i);
                    files.add(new BoxClient.RemoteFile(x.getString(0), x.getString(1), x.optLong(2, -1), 0));
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
