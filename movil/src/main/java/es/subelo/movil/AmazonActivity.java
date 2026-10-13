package es.subelo.movil;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fotos al Echo: conectar Súbelo con Amazon Photos y elegir el álbum que enseña el Echo.
 *
 * Se entra en Amazon dentro de la app (su propia página, con su verificación); al terminar, la
 * sesión se pasa al box, que es quien sube la carpeta «Echo» al álbum, y se borra del móvil.
 */
public class AmazonActivity extends Activity {
    /** Amazon de España; las cookies de sesión se llaman «…-acbes». */
    private static final String TLD = "es";
    private static final String HOME = "https://www.amazon." + TLD;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ScrollView panel;
    private TextView stateText, albumText, countText, errorText;
    private Button loginButton, albumButton, syncButton, disconnectButton;
    private WebView web;
    private boolean sending;
    private JSONObject last;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 4000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Brutal.CREAM);
        panel = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(12), dp(28));
        panel.addView(col);

        TextView title = Brutal.title(this, "Fotos al Echo", 28, Brutal.INK);
        col.addView(title);
        TextView intro = Brutal.text(this, 15, Brutal.INK);
        intro.setText("Todo lo que pongas en tu carpeta «Echo» del disco se sube a tu Amazon Photos y se añade "
                + "al álbum que enseña tu Echo. Si quitas una foto de la carpeta, se quita del álbum.\n\n"
                + "Para meter fotos: en «Mis fotos» mantén pulsado, elige y pulsa «📺 Enviar al Echo».");
        intro.setPadding(0, dp(6), 0, dp(4));
        col.addView(intro);

        LinearLayout card = Brutal.card(this, "📺 // amazon photos", Brutal.WHITE);
        stateText = Brutal.text(this, 16, Brutal.INK);
        card.addView(stateText);
        albumText = Brutal.mono(this, 13, Brutal.INK);
        albumText.setPadding(0, dp(8), 0, 0);
        card.addView(albumText);
        countText = Brutal.mono(this, 13, Brutal.INK);
        card.addView(countText);
        errorText = Brutal.text(this, 14, Brutal.RED);
        errorText.setPadding(0, dp(6), 0, 0);
        errorText.setVisibility(View.GONE);
        card.addView(errorText);
        loginButton = Brutal.button(this, "Entrar en Amazon Photos", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLogin();
            }
        });
        card.addView(loginButton, Brutal.full(this));
        albumButton = Brutal.button(this, "Elegir el álbum del Echo", Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadAlbums();
            }
        });
        card.addView(albumButton, Brutal.full(this));
        syncButton = Brutal.button(this, "Repasar ahora", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(new Call() {
                    @Override
                    public JSONObject run(BoxClient bc) throws IOException {
                        return bc.amazonSync();
                    }
                });
            }
        });
        card.addView(syncButton, Brutal.full(this));
        disconnectButton = Brutal.button(this, "Desconectar Amazon", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDisconnect();
            }
        });
        card.addView(disconnectButton, Brutal.full(this));
        col.addView(card, Brutal.cardParams(this));

        TextView note = Brutal.mono(this, 11, Brutal.INK);
        note.setText("AMAZON NO TIENE UNA FORMA OFICIAL DE HACER ESTO: SÚBELO USA LA MISMA CONEXIÓN QUE SU WEB, "
                + "ASÍ QUE PUEDE DEJAR DE IR SI AMAZON LA CAMBIA. TU SESIÓN DE AMAZON SE GUARDA SOLO DENTRO DEL BOX "
                + "(NO EN EL DISCO) Y SE BORRA AL PULSAR «DESCONECTAR».");
        note.setPadding(dp(2), dp(10), 0, 0);
        col.addView(note);

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        show(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        closeWeb();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            if (web.canGoBack()) web.goBack();
            else closeWeb();
            return;
        }
        super.onBackPressed();
    }

    // ---------- estado ----------

    private void refresh() {
        if (web != null || sending) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = BoxClient.connect(AmazonActivity.this).amazon();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            show(o);
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (last == null) {
                                stateText.setText("✖ " + e.getMessage());
                                hideButtons();
                            }
                        }
                    });
                }
            }
        });
    }

    private void hideButtons() {
        loginButton.setVisibility(View.GONE);
        albumButton.setVisibility(View.GONE);
        syncButton.setVisibility(View.GONE);
        disconnectButton.setVisibility(View.GONE);
    }

    private void show(JSONObject o) {
        last = o;
        if (o == null) {
            stateText.setText("Mirando el box…");
            albumText.setText("");
            countText.setText("");
            hideButtons();
            return;
        }
        boolean connected = o.optBoolean("connected"), expired = o.optBoolean("expired");
        JSONObject album = o.optJSONObject("album");
        String err = o.isNull("error") ? null : o.optString("error", null);
        if (!connected) {
            stateText.setText("Súbelo aún no está conectado con tu Amazon Photos.");
        } else if (expired) {
            stateText.setText("⚠ La sesión de Amazon ha caducado. Vuelve a entrar para seguir enviando fotos.");
        } else if (album == null) {
            stateText.setText("✔ Conectado con Amazon Photos. Falta elegir el álbum que enseña tu Echo.");
        } else {
            stateText.setText(o.optBoolean("running") ? "↻ Enviando fotos al álbum…" : "✔ Funcionando");
        }
        albumText.setText(album == null ? "" : "ÁLBUM: " + album.optString("name") + "\nCARPETA: DISCO:/" + Prefs.user(this) + "/" + o.optString("folder", "Echo"));
        int total = o.optInt("total", -1), in = o.optInt("inAlbum"), pending = o.optInt("pending");
        long ok = o.optLong("lastOk");
        countText.setText(album == null ? "" : (in == 1 ? "1 FOTO" : in + " FOTOS") + " EN EL ÁLBUM"
                + (pending > 0 ? " · " + pending + " POR ENVIAR" : "")
                + (total >= 0 && total > in + pending ? " · " + (total - in - pending) + " SIN ENVIAR" : "")
                + (ok > 0 ? "\nÚLTIMO REPASO: " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ok)) : ""));
        errorText.setText(err == null || expired ? "" : "✖ " + err);
        errorText.setVisibility(err == null || expired ? View.GONE : View.VISIBLE);
        loginButton.setText(connected ? "Volver a entrar en Amazon" : "Entrar en Amazon Photos");
        loginButton.setVisibility(!connected || expired ? View.VISIBLE : View.GONE);
        albumButton.setText(album == null ? "Elegir el álbum del Echo" : "Cambiar de álbum");
        albumButton.setVisibility(connected && !expired ? View.VISIBLE : View.GONE);
        syncButton.setVisibility(connected && !expired && album != null ? View.VISIBLE : View.GONE);
        disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    private interface Call {
        JSONObject run(BoxClient bc) throws IOException;
    }

    /** Pide algo al box en segundo plano y enseña el estado que devuelve. */
    private void act(final Call call) {
        sending = true;
        stateText.setText("Un momento…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject r = null;
                String err = null;
                try {
                    r = call.run(BoxClient.connect(AmazonActivity.this));
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final JSONObject res = r;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        if (error != null) alert("✖ " + error);
                        if (res != null) show(res);
                        else refresh();
                    }
                });
            }
        });
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setMessage("¿Desconectar Amazon Photos? El box olvida tu sesión de Amazon y deja de enviar fotos. "
                        + "Lo que ya está en el álbum se queda.")
                .setPositiveButton("Desconectar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        act(new Call() {
                            @Override
                            public JSONObject run(BoxClient bc) throws IOException {
                                return bc.amazonDisconnect();
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- entrar en Amazon ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void startLogin() {
        final CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null); // empezar limpio: la sesión vieja no sirve
        cm.setAcceptCookie(true);
        web = new WebView(this);
        cm.setAcceptThirdPartyCookies(web, true);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                check();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                check();
            }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Brutal.BLACK);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(10), dp(4), dp(4), dp(4));
        TextView t = Brutal.mono(this, 12, Brutal.PAPER);
        t.setText("ENTRA CON TU CUENTA DE AMAZON");
        t.setPadding(dp(4), dp(14), 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(Brutal.button(this, "✕", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeWeb();
            }
        }), Brutal.spaced(this, dp(56), -2, 0));
        box.addView(bar);
        box.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        panel.setVisibility(View.GONE);
        web.loadUrl(HOME + "/photos/all");
    }

    /** ¿Ya ha entrado? Amazon pone la cookie «at-acbes» al iniciar sesión. */
    private void check() {
        if (web == null || sending) return;
        String ck = CookieManager.getInstance().getCookie(HOME);
        if (ck == null || !ck.contains("at-acb" + TLD + "=")) return;
        final String cookie = ck;
        sending = true;
        closeWeb();
        stateText.setText("Conectando el box con Amazon…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject r = null;
                String err = null;
                try {
                    r = BoxClient.connect(AmazonActivity.this).amazonConnect(cookie, TLD);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final JSONObject res = r;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        forgetSession();
                        if (error != null) {
                            alert("✖ No se pudo conectar: " + error);
                            refresh();
                            return;
                        }
                        show(res);
                        if (res.isNull("album")) pickAlbum(res.optJSONArray("albums"), res.optString("albumsError", null));
                        else Toast.makeText(AmazonActivity.this, "Conectado de nuevo", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** La sesión ya está en el box: en el móvil no se guarda. */
    private void forgetSession() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
    }

    private void closeWeb() {
        if (web == null) return;
        View box = (View) web.getParent();
        root.removeView(box);
        web.stopLoading();
        web.destroy();
        web = null;
        panel.setVisibility(View.VISIBLE);
        if (!sending) forgetSession();
    }

    // ---------- álbum ----------

    private void loadAlbums() {
        sending = true;
        stateText.setText("Buscando tus álbumes…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONArray a = null;
                String err = null;
                try {
                    a = BoxClient.connect(AmazonActivity.this).amazonAlbums().optJSONArray("albums");
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final JSONArray albums = a;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        pickAlbum(albums, error);
                        refresh();
                    }
                });
            }
        });
    }

    private void pickAlbum(final JSONArray albums, String error) {
        final int n = albums == null ? 0 : albums.length();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject a = albums.optJSONObject(i);
            int count = a.optInt("count", -1);
            names[i] = a.optString("name") + (count >= 0 ? "  (" + count + ")" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("¿Qué álbum enseña tu Echo?");
        if (n > 0) {
            b.setItems(names, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    final JSONObject a = albums.optJSONObject(which);
                    act(new Call() {
                        @Override
                        public JSONObject run(BoxClient bc) throws IOException {
                            return bc.amazonAlbum(a.optString("id"), a.optString("name"));
                        }
                    });
                }
            });
        } else {
            b.setMessage((error != null ? "No he podido leer tus álbumes (" + error + ").\n\n" : "No encuentro álbumes.\n\n")
                    + "Pega el enlace para compartir del álbum (amazon.es/photos/share/…).");
        }
        b.setNeutralButton("Pegar enlace", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                askLink();
            }
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void askLink() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://www.amazon.es/photos/share/…");
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cb == null ? null : cb.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence txt = clip.getItemAt(0).getText();
            if (txt != null && txt.toString().contains("/photos/share/")) et.setText(txt.toString().trim());
        }
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Enlace del álbum")
                .setMessage("En Amazon Photos abre el álbum del Echo, pulsa Compartir › Copiar enlace y pégalo aquí.")
                .setView(form)
                .setPositiveButton("Usar este álbum", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String link = et.getText().toString().trim();
                        if (link.isEmpty()) return;
                        act(new Call() {
                            @Override
                            public JSONObject run(BoxClient bc) throws IOException {
                                return bc.amazonAlbumLink(link);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show();
    }

    private int dp(float v) {
        return Brutal.dp(this, v);
    }
}
