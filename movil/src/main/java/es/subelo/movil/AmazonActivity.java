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
 * sesión se pasa al box, que es quien sube la carpeta «Echo» al álbum y las copias de seguridad.
 * Una copia se queda cifrada en el móvil (AmazonSession) para ver las copias fuera de casa.
 */
public class AmazonActivity extends Activity {
    /** Solo entrar en Amazon (para «☁ Copias en Amazon»): al terminar, vuelve atrás. */
    static final String EXTRA_ONLY_LOGIN = "soloEntrar";
    /**
     * La contraseña de Súbelo que «☁ Copias en Amazon» acaba de comprobar, para no pedirla dos
     * veces seguidas. Solo en memoria; se gasta al abrir esta pantalla.
     */
    static String handoff;
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
    /** Contraseña de Súbelo para este cambio de cuenta (el box la pide para conectar o desconectar). */
    private String password;
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
                withPassword(new PasswordOk() {
                    @Override
                    public void ok(String pw) {
                        password = pw;
                        startLogin();
                    }
                }, null);
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
                + "ASÍ QUE PUEDE DEJAR DE IR SI AMAZON LA CAMBIA. TU SESIÓN DE AMAZON SE GUARDA DENTRO DEL BOX "
                + "(NO EN EL DISCO), CIFRADA SI EL BOX LO PERMITE, Y CIFRADA EN ESTE MÓVIL PARA VER TUS COPIAS FUERA DE CASA. "
                + "SE BORRA DE LOS DOS AL PULSAR «DESCONECTAR» Y DEL MÓVIL AL CERRAR TU SESIÓN DE SÚBELO. "
                + "CADA USUARIO DE SÚBELO, CON SU PROPIA CUENTA DE AMAZON: AMAZON FOTOS NO TIENE PERFILES.");
        note.setPadding(dp(2), dp(10), 0, 0);
        col.addView(note);

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        if (getIntent().getBooleanExtra(EXTRA_ONLY_LOGIN, false)) {
            title.setText("Entrar en Amazon");
            intro.setText("Entra con tu cuenta de Amazon para ver tus copias de seguridad desde el móvil.");
            card.setVisibility(View.GONE);
        }
        setContentView(root);
        show(null);
        if (getIntent().getBooleanExtra(EXTRA_ONLY_LOGIN, false)) {
            String h = handoff;
            handoff = null;
            if (h != null) {
                password = h;
                startLogin();
            } else {
                withPassword(new PasswordOk() {
                    @Override
                    public void ok(String pw) {
                        password = pw;
                        startLogin();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }
    }

    private interface PasswordOk {
        void ok(String password);
    }

    /**
     * Pide la contraseña de Súbelo antes de cambiar o quitar la cuenta de Amazon: así, con el móvil
     * de otro desbloqueado no se pueden llevar sus copias a otra cuenta. El box la vuelve a comprobar.
     */
    private void withPassword(final PasswordOk then, final Runnable cancelled) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText pw = Brutal.password(this, "Contraseña de " + Prefs.user(this));
        form.addView(pw);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tu contraseña de Súbelo")
                .setMessage("Para cambiar la cuenta de Amazon de " + Prefs.user(this) + ".")
                .setView(form)
                .setPositiveButton("Seguir", null)
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        if (cancelled != null) cancelled.run();
                    }
                })
                .setCancelable(false)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String p = pw.getText().toString();
                        if (p.isEmpty()) return;
                        long wait = AmazonSession.waitMs(AmazonActivity.this);
                        if (wait > 0) {
                            pw.setError("Demasiados intentos: espera " + (wait / 1000 + 1) + " s");
                            return;
                        }
                        // si el móvil ya sabe comprobarla, se comprueba aquí; si no, la comprueba el box
                        if (AmazonSession.hasPassword(AmazonActivity.this) && !AmazonSession.check(AmazonActivity.this, p)) {
                            pw.setText("");
                            pw.setError("Contraseña incorrecta");
                            return;
                        }
                        d.dismiss();
                        then.ok(p);
                    }
                });
            }
        });
        d.show();
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
        if (web != null || sending || getIntent().getBooleanExtra(EXTRA_ONLY_LOGIN, false)) return;
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
        if (!o.isNull("sameAs")) err = (err == null ? "" : err + "\n\n") + sameAccountText(Prefs.user(this), o.optString("sameAs"), o.optBoolean("sameBefore"));
        errorText.setText(err == null || expired ? "" : "✖ " + err);
        errorText.setVisibility(err == null || expired ? View.GONE : View.VISIBLE);
        boolean shared = connected && !o.isNull("sameAs");
        loginButton.setText(shared ? "Entrar con otra cuenta de Amazon" : connected ? "Volver a entrar en Amazon" : "Entrar en Amazon Photos");
        loginButton.setVisibility(!connected || expired || shared ? View.VISIBLE : View.GONE);
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
                .setMessage("¿Desconectar Amazon Photos? El box y este móvil olvidan tu sesión de Amazon: se dejan de "
                        + "enviar fotos al Echo y de hacer las copias de seguridad. Lo que ya está en Amazon se queda.")
                .setPositiveButton("Desconectar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        withPassword(new PasswordOk() {
                            @Override
                            public void ok(final String pw) {
                                act(new Call() {
                                    @Override
                                    public JSONObject run(BoxClient bc) throws IOException {
                                        JSONObject r = bc.amazonDisconnect(pw);
                                        AmazonSession.clear(AmazonActivity.this); // solo si el box ha dicho que sí
                                        return r;
                                    }
                                });
                            }
                        }, null);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- entrar en Amazon ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void startLogin() {
        final CookieManager cm = CookieManager.getInstance();
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
        // empezar limpio: hasta que no se borran las cookies de antes no se abre la página
        cm.removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean removed) {
                if (web != null) web.loadUrl(HOME + "/photos/all");
            }
        });
    }

    /** ¿Ya ha entrado? Amazon pone la cookie «at-acbes» al iniciar sesión. */
    private void check() {
        if (web == null || sending) return;
        String ck = CookieManager.getInstance().getCookie(HOME);
        if (ck == null || !ck.contains("at-acb" + TLD + "=")) return;
        final String cookie = ck;
        sending = true;
        closeWeb();
        final boolean onlyLogin = getIntent().getBooleanExtra(EXTRA_ONLY_LOGIN, false);
        stateText.setText("Conectando el box con Amazon…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject r = null;
                String err = null;
                boolean no = false;
                try {
                    r = BoxClient.connect(AmazonActivity.this).amazonConnect(cookie, TLD, password);
                } catch (IOException e) {
                    err = e.getMessage();
                    int code = e instanceof BoxClient.BoxException ? ((BoxClient.BoxException) e).code : 0;
                    no = code == 401 || code == 403 || code == 429; // el box no se cree que seas tú
                }
                final JSONObject res = r;
                final String error = err;
                final boolean refused = no;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sending = false;
                        forgetSession();
                        password = null;
                        if (isFinishing() || isDestroyed()) {
                            // ya se ha cerrado la pantalla: sin avisos, pero la sesión sí se guarda si vale
                            if (!refused) AmazonSession.save(AmazonActivity.this, cookie, TLD);
                            return;
                        }
                        if (refused) {
                            new AlertDialog.Builder(AmazonActivity.this)
                                    .setMessage("✖ " + error)
                                    .setPositiveButton("Vale", null)
                                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                        @Override
                                        public void onDismiss(DialogInterface d) {
                                            if (onlyLogin) finish();
                                            else refresh();
                                        }
                                    })
                                    .show();
                            return;
                        }
                        // fuera de casa (box sin contestar) también: para ver las copias basta con el móvil
                        AmazonSession.save(AmazonActivity.this, cookie, TLD);
                        if (onlyLogin) {
                            // fuera de casa el box no contesta: para ver las copias basta con el móvil
                            setResult(RESULT_OK);
                            if (res != null && !res.isNull("sameAs")) {
                                new AlertDialog.Builder(AmazonActivity.this)
                                        .setMessage(sameAccountText(Prefs.user(AmazonActivity.this), res.optString("sameAs"),
                                                res.optBoolean("sameBefore")))
                                        .setPositiveButton("Vale", null)
                                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                            @Override
                                            public void onDismiss(DialogInterface d) {
                                                finish();
                                            }
                                        })
                                        .show();
                                return;
                            }
                            Toast.makeText(AmazonActivity.this, "Dentro de Amazon", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
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
        if (!sending && getIntent().getBooleanExtra(EXTRA_ONLY_LOGIN, false)) finish(); // ha cerrado sin entrar
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

    /** Aviso de que los dos usuarios de Súbelo han entrado con la misma cuenta de Amazon. */
    static String sameAccountText(String me, String other, boolean before) {
        if (before) {
            return "🔒 " + other + " entró en Súbelo con esta misma cuenta de Amazon, así que sabe entrar en ella y "
                    + "vería tus copias (Amazon Fotos no tiene perfiles). Súbelo no hace copias de seguridad así (el Echo "
                    + "sí sigue funcionando).\n\nSi la cuenta es tuya, cámbiale la contraseña en Amazon y luego, en la "
                    + "carpeta, pulsa ☁ › «" + other + " ya no puede entrar». Si no es tuya, pulsa antes ☁ › «Dejar de "
                    + "hacer copia» en cada carpeta copiada (se borra de esta cuenta) y luego entra con tu propia cuenta.";
        }
        return "🔒 Esta cuenta de Amazon es la misma que tiene conectada " + other + ". Amazon Fotos no tiene perfiles: "
                + "con una sola cuenta, cada uno vería las fotos del otro, así que Súbelo no hace copias de seguridad "
                + "así (el Echo sí sigue funcionando).\n\nLo que ya se copió en esta cuenta sigue en ella, en «Súbelo "
                + "Backup/" + me + "». Si no quieres que " + other + " lo vea, antes de cambiar de cuenta pulsa ☁ › «Dejar "
                + "de hacer copia» en cada carpeta copiada y espera a que se borre de Amazon. Si cambias antes, ya solo "
                + "se puede borrar en la web de Amazon Fotos.\n\nPara las copias, cada uno con su propia cuenta de Amazon "
                + "(«Entrar con otra cuenta de Amazon»). Para que las dos tengan fotos ilimitadas, quien tenga Prime "
                + "puede invitar a la otra a su «Archivo familiar» desde Amazon Fotos; dejad apagado «añadir subidas "
                + "al Archivo familiar».";
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show();
    }

    private int dp(float v) {
        return Brutal.dp(this, v);
    }
}
