package es.subelo.movil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_FOLDER = 2;
    private static final int REQ_MEDIA_PERMS = 3;
    private static final int REQ_NOTIF = 4;
    private static final int REQ_UPLOAD_DIR = 5;
    private static final int REQ_LOCATION = 6;

    /** Fotos compartidas o elegidas esperando a que digas en qué carpeta van. */
    private ArrayList<Uri> pendingUpload;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private TextView boxText, destText, statusText, autoText, updateText;
    private LinearLayout updateCard;
    private Updater.Release update;
    private Switch autoSwitch, lockSwitch;
    private CheckBox lockBio;
    private Button cancelButton;
    private boolean ignoreSwitch;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String st = UploadService.status;
            statusText.setText(st == null || st.isEmpty() ? "▸ SIN TRANSFERENCIAS EN CURSO" : "▸ " + st);
            cancelButton.setVisibility(UploadService.busy ? View.VISIBLE : View.GONE);
            String last = Prefs.lastAuto(MainActivity.this);
            autoText.setText(last.isEmpty() ? "" : "ÚLTIMA: " + last);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        if (!Prefs.loggedIn(this)) {
            goLogin(getIntent());
            return;
        }
        if (savedInstanceState != null) pendingUpload = savedInstanceState.getParcelableArrayList("pendientes");
        buildUi();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        AutoUploadJob.schedule(this);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Prefs.loggedIn(this)) {
            goLogin(intent);
            return;
        }
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Prefs.loggedIn(this)) {
            goLogin(null);
            return;
        }
        handler.post(tick);
        refreshBox();
        checkUpdate(false);
        showDest();
    }

    private void showDest() {
        destText.setText("DISCO:/" + Prefs.user(this) + "/" + Prefs.dest(this) + (Prefs.organize(this) ? "/AÑO/MES" : ""));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // ---------- interfaz ----------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Brutal.CREAM);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(12), dp(28));
        scroll.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Brutal.logo(this, 52), new LinearLayout.LayoutParams(0, dp(52), 1));
        Button theme = Brutal.darkToggle(this, "☾ Oscuro", "☀ Claro");
        theme.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        theme.setMinHeight(dp(44));
        theme.setPadding(dp(10), dp(8), dp(15), dp(13));
        head.addView(theme, new LinearLayout.LayoutParams(-2, -2));
        col.addView(head);
        TextView claim = Brutal.mono(this, 12, Brutal.INK);
        claim.setText("TUS FOTOS · TU DISCO · TU CASA");
        claim.setPadding(dp(2), dp(8), 0, dp(6));
        col.addView(claim);

        // actualización disponible (solo se ve cuando la hay)
        updateCard = Brutal.card(this, "nueva versión", Brutal.ORANGE);
        updateText = Brutal.text(this, 15, Brutal.BLACK);
        updateCard.addView(updateText);
        updateCard.addView(Brutal.button(this, "⬆ Actualizar ahora", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installUpdate();
            }
        }), Brutal.full(this));
        updateCard.setVisibility(View.GONE);
        col.addView(updateCard, Brutal.cardParams(this));

        // 01 · box
        LinearLayout box = Brutal.card(this, "01 // tu box", Brutal.WHITE);
        boxText = Brutal.text(this, 15, Brutal.INK);
        box.addView(boxText);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Buscar el box", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchBox();
            }
        }), Brutal.half(this));
        row.addView(button("IP a mano", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualIp();
            }
        }), Brutal.half(this));
        box.addView(row);
        box.addView(button("Cerrar sesión de " + Prefs.user(this), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logout();
            }
        }), Brutal.full(this));
        col.addView(box, Brutal.cardParams(this));
        Brutal.foldable(this, box, "box", 1);

        // acciones principales
        LinearLayout main = Brutal.card(this, null, Brutal.PURPLE);
        TextView big = Brutal.title(this, "¿Qué hacemos?", 26, Brutal.PAPER);
        big.setPadding(0, 0, 0, dp(8));
        main.addView(big);
        main.addView(Brutal.button(this, "⬆ Subir fotos y vídeos", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        }), Brutal.full(this));
        main.addView(Brutal.button(this, "▤ Subir álbumes enteros", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBox(AlbumsActivity.class);
            }
        }), Brutal.full(this));
        main.addView(Brutal.button(this, "▦ Mis fotos por carpetas", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBox(ExploreActivity.class);
            }
        }), Brutal.full(this));
        main.addView(Brutal.button(this, "◉ Mapa de mis fotos", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBox(MapActivity.class);
            }
        }), Brutal.full(this));
        TextView sub = Brutal.mono(this, 11, Brutal.PAPER);
        sub.setText("VER · SUBIR A UNA CARPETA · DESCARGAR · DÓNDE SE HICIERON");
        sub.setPadding(dp(2), dp(4), 0, 0);
        main.addView(sub);
        col.addView(main, col.indexOfChild(box), Brutal.cardParams(this)); // lo primero, lo que más se usa

        statusText = Brutal.mono(this, 13, Brutal.INK);
        statusText.setBackground(Brutal.block(this, Brutal.PEACH, 4, 3));
        statusText.setPadding(dp(12), dp(10), dp(16), dp(14));
        col.addView(statusText, Brutal.cardParams(this));
        cancelButton = button("✕ Cancelar transferencia", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UploadService.cancel(MainActivity.this);
            }
        });
        col.addView(cancelButton, Brutal.full(this));

        // 02 · destino
        LinearLayout dest = Brutal.card(this, "02 // dónde se guardan", Brutal.LILAC);
        destText = Brutal.mono(this, 15, Brutal.INK);
        destText.setPadding(0, 0, 0, dp(6));
        dest.addView(destText);
        dest.addView(button("Elegir carpeta del disco", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, FolderActivity.class), REQ_FOLDER);
            }
        }), Brutal.full(this));
        CheckBox organize = check("Organizar por año y mes", Prefs.organize(this));
        organize.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setOrganize(MainActivity.this, on);
                showDest();
            }
        });
        dest.addView(organize);
        col.addView(dest, Brutal.cardParams(this));
        Brutal.foldable(this, dest, "destino", 1);

        // 03 · automático
        LinearLayout auto = Brutal.card(this, "03 // subida automática", Brutal.WHITE);
        autoSwitch = new Switch(this);
        autoSwitch.setText("Copiar solas las fotos nuevas (con el Wi-Fi de casa)");
        Brutal.style(autoSwitch);
        autoSwitch.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Brutal.ORANGE, Brutal.SNOW}));
        autoSwitch.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Brutal.PURPLE, Brutal.INK}));
        autoSwitch.setChecked(Prefs.auto(this));
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (ignoreSwitch) return;
                if (on) enableAuto();
                else {
                    Prefs.setAuto(MainActivity.this, false);
                    AutoUploadJob.schedule(MainActivity.this);
                }
            }
        });
        auto.addView(autoSwitch);
        CheckBox cam = check("Solo fotos de la cámara (sin capturas ni WhatsApp)", Prefs.onlyCamera(this));
        cam.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setOnlyCamera(MainActivity.this, on);
            }
        });
        auto.addView(cam);
        CheckBox vid = check("Incluir vídeos", Prefs.videos(this));
        vid.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setVideos(MainActivity.this, on);
            }
        });
        auto.addView(vid);
        auto.addView(button("Subir ahora las pendientes", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Prefs.auto(MainActivity.this)) {
                    alert("Activa primero la subida automática.");
                    return;
                }
                UploadService.scan(MainActivity.this);
            }
        }), Brutal.full(this));
        autoText = Brutal.mono(this, 11, Brutal.INK);
        autoText.setPadding(0, dp(6), 0, 0);
        auto.addView(autoText);
        col.addView(auto, Brutal.cardParams(this));
        Brutal.foldable(this, auto, "auto", 1);

        // 04 · seguridad
        LinearLayout lock = Brutal.card(this, "04 // seguridad", Brutal.LILAC);
        lockSwitch = new Switch(this);
        lockSwitch.setText("Pedir huella o contraseña al abrir Súbelo");
        Brutal.style(lockSwitch);
        lockSwitch.setThumbTintList(autoSwitch.getThumbTintList());
        lockSwitch.setTrackTintList(autoSwitch.getTrackTintList());
        lockSwitch.setChecked(AppLock.enabled(this));
        lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (ignoreSwitch) return;
                if (on) askLockPassword();
                else {
                    AppLock.disable(MainActivity.this);
                    lockBio.setEnabled(false);
                    Toast.makeText(MainActivity.this, "Súbelo ya no pide contraseña al abrir", Toast.LENGTH_SHORT).show();
                }
            }
        });
        lock.addView(lockSwitch);
        boolean bio = AppLock.biometricPossible(this);
        lockBio = check(bio ? "Con huella (si no, solo contraseña)" : "Con huella (este móvil no tiene ninguna configurada)",
                bio && AppLock.useBiometric(this));
        lockBio.setEnabled(bio && AppLock.enabled(this));
        lockBio.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                AppLock.setUseBiometric(MainActivity.this, on);
            }
        });
        lock.addView(lockBio);
        TextView lockText = Brutal.mono(this, 11, Brutal.INK);
        lockText.setText("LA CONTRASEÑA ES LA DE SÚBELO (LA DE ENTRAR COMO " + Prefs.user(this).toUpperCase(Locale.getDefault())
                + "). SE VUELVE A PEDIR SI SALES DE LA APP MÁS DE 1 MINUTO.");
        lockText.setPadding(0, dp(6), 0, 0);
        lock.addView(lockText);
        col.addView(lock, Brutal.cardParams(this));
        Brutal.foldable(this, lock, "seguridad", 1);

        TextView foot = Brutal.mono(this, 11, Brutal.INK);
        foot.setText("SÚBELO " + Updater.installedName(this) + " · CONEXIÓN CIFRADA 🔒");
        foot.setPadding(dp(2), dp(10), 0, 0);
        col.addView(foot);
        col.addView(button("Buscar actualizaciones", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        }), Brutal.full(this));

        setContentView(scroll);
    }

    // ---------- box ----------

    private void refreshBox() {
        final String name = Prefs.boxName(this);
        if (Prefs.host(this) == null) {
            boxText.setText("Todavía no has elegido el box. Pulsa «Buscar el box».");
            return;
        }
        boxText.setText("Conectando con " + (name != null ? name : Prefs.host(this)) + "…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    BoxClient bc = BoxClient.connect(MainActivity.this);
                    JSONObject info = bc.info();
                    long free = info.optLong("free", -1);
                    msg = "👤 " + Prefs.user(MainActivity.this) + "\n✔ Conectado a " + info.optString("name") + " (" + bc.host + ")  🔒 "
                            + BoxClient.shortCode(Prefs.certFp(MainActivity.this)) + "\n"
                            + (info.optBoolean("available")
                            ? "Disco: " + info.optString("storage") + (free >= 0 ? " · libre " + gb(free) : "")
                            : "⚠ El box no tiene el disco disponible. Revisa el disco USB y la app del box.");
                } catch (BoxClient.BoxException e) {
                    if (e.code == 401) {
                        // contraseña borrada desde la tele o sesión caducada
                        Prefs.logout(MainActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                goLogin(null);
                            }
                        });
                        return;
                    }
                    msg = "✖ " + e.getMessage();
                } catch (IOException e) {
                    msg = "✖ " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boxText.setText(m);
                        showDest();
                    }
                });
            }
        });
    }

    private void searchBox() {
        boxText.setText("Buscando el box en la red de casa…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<Discovery.Found> found = Discovery.discover(MainActivity.this, 4000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found.isEmpty()) {
                            boxText.setText("No he encontrado el box.");
                            alert("No he encontrado el box.\n\n• Comprueba que el móvil está en el Wi-Fi de casa.\n"
                                    + "• Comprueba que la app «Súbelo Servidor» está abierta en el box y dice «Servidor ACTIVO».\n"
                                    + "• Si sigue sin aparecer, pulsa «Poner IP a mano» y escribe la dirección que sale en la tele.");
                        } else if (found.size() == 1) {
                            chooseBox(found.get(0));
                        } else {
                            String[] names = new String[found.size()];
                            for (int i = 0; i < names.length; i++) names[i] = found.get(i).name + " (" + found.get(i).host + ")";
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("¿Qué box?")
                                    .setItems(names, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int which) {
                                            chooseBox(found.get(which));
                                        }
                                    }).show();
                        }
                    }
                });
            }
        });
    }

    private void chooseBox(Discovery.Found f) {
        Prefs.pairNew(this, f.name, f.host, f.port);
        refreshBox();
    }

    private void manualIp() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("192.168.1.50");
        if (Prefs.host(this) != null) et.setText(Prefs.host(this));
        new AlertDialog.Builder(this)
                .setTitle("Dirección del box")
                .setMessage("Escribe la IP que aparece en la pantalla del box (sin http:// ni :8765).")
                .setView(et)
                .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String h = et.getText().toString().trim().replace("http://", "");
                        int port = Prefs.DEFAULT_PORT;
                        int slash = h.indexOf('/');
                        if (slash >= 0) h = h.substring(0, slash);
                        int colon = h.lastIndexOf(':');
                        if (colon > 0 && h.indexOf(':') == colon) {
                            try {
                                port = Integer.parseInt(h.substring(colon + 1));
                            } catch (NumberFormatException ignored) {
                            }
                            h = h.substring(0, colon);
                        }
                        if (h.isEmpty()) return;
                        Prefs.pairNew(MainActivity.this, null, h, port);
                        refreshBox();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- actualizaciones ----------

    private void checkUpdate(final boolean now) {
        if (now) Toast.makeText(this, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show();
        bg.execute(new Runnable() {
            @Override
            public void run() {
                Updater.Release found;
                String error = null;
                if (now) {
                    try {
                        found = Updater.checkNow(MainActivity.this);
                    } catch (IOException e) {
                        found = null;
                        error = e.getMessage();
                    }
                } else {
                    found = Updater.checkThrottled(MainActivity.this);
                }
                final Updater.Release r = found;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (now && r == null) {
                            alert(err != null ? "No he podido mirar si hay versión nueva: " + err
                                    : "Ya tienes la última versión (" + Updater.installedName(MainActivity.this) + ").");
                        }
                        update = r;
                        updateCard.setVisibility(r == null ? View.GONE : View.VISIBLE);
                        if (r != null) updateText.setText("Hay una versión nueva de Súbelo (" + r.name + "). Tienes la "
                                + Updater.installedName(MainActivity.this) + ".");
                    }
                });
            }
        });
    }

    private void installUpdate() {
        final Updater.Release r = update;
        if (r == null) return;
        updateText.setText("Descargando la versión " + r.name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Updater.install(MainActivity.this, r, new Updater.Progress() {
                        long last;

                        @Override
                        public void onProgress(final long done, final long total) {
                            if (done - last < 256 * 1024) return;
                            last = done;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateText.setText("Descargando… " + (total > 0 ? (100 * done / total) + "%" : done / 1024 + " KB"));
                                }
                            });
                        }
                    });
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateText.setText(error == null ? "Confirma la instalación en la ventana de Android."
                                : "✖ " + error);
                    }
                });
            }
        }).start();
    }

    private void goLogin(Intent share) {
        Intent i = new Intent(this, LoginActivity.class);
        if (share != null && (Intent.ACTION_SEND.equals(share.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(share.getAction()))) {
            i.putExtra("share", share);
        }
        startActivity(i);
        finish();
    }

    private void logout() {
        final BoxClient bc = BoxClient.fromPrefs(this);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bc != null) bc.logout();
                } catch (IOException ignored) {
                    // sin conexión: la sesión caducará sola en el box
                }
            }
        });
        Prefs.logout(this);
        goLogin(null);
    }

    // ---------- elegir y subir fotos ----------

    private void pick() {
        if (Prefs.host(this) == null) {
            alert("Primero pulsa «Buscar el box».");
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Elige las fotos"), REQ_PICK);
        } catch (ActivityNotFoundException e) {
            alert("No hay ninguna galería para elegir fotos.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPLOAD_DIR) {
            ArrayList<Uri> uris = pendingUpload;
            pendingUpload = null;
            if (uris == null) return;
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "No he subido nada", Toast.LENGTH_SHORT).show();
                return;
            }
            String dir = data.getStringExtra("path");
            UploadService.upload(this, uris, dir == null ? "" : dir);
            statusText.setText("Subiendo " + uris.size() + (uris.size() == 1 ? " archivo" : " archivos")
                    + " a DISCO:/" + Prefs.user(this) + "/" + (dir == null ? "" : dir));
            return;
        }
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<Uri>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    if (cd.getItemAt(i).getUri() != null) uris.add(cd.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            startUpload(uris);
        }
    }

    @SuppressWarnings("deprecation")
    private void handleShare(Intent intent) {
        if (intent == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else {
            return;
        }
        setIntent(new Intent(this, MainActivity.class));
        if (Prefs.host(this) == null) {
            alert("Primero pulsa «Buscar el box» y luego vuelve a compartir las fotos.");
            return;
        }
        startUpload(uris);
    }

    /** Antes de subir, pregunta en qué carpeta del disco van. */
    private void startUpload(ArrayList<Uri> uris) {
        if (uris.isEmpty()) return;
        pendingUpload = uris;
        // sin este permiso Android borra de las fotos dónde se hicieron (y no salen en el mapa)
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            List<String> perms = new ArrayList<String>();
            perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
            if (!Uploader.hasMediaPermission(this)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES);
                    perms.add(Manifest.permission.READ_MEDIA_VIDEO);
                } else {
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            requestPermissions(perms.toArray(new String[0]), REQ_LOCATION);
            return;
        }
        askFolder();
    }

    private void askFolder() {
        int n = pendingUpload.size();
        startActivityForResult(new Intent(this, FolderActivity.class)
                .putExtra(FolderActivity.EXTRA_PICK, Prefs.dest(this))
                .putExtra(FolderActivity.EXTRA_TITLE, n == 1 ? "¿Dónde la guardo?" : "¿Dónde guardo las " + n + "?")
                .putExtra(FolderActivity.EXTRA_OK, "⬆ Subir aquí"), REQ_UPLOAD_DIR);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUpload != null) out.putParcelableArrayList("pendientes", pendingUpload);
    }

    // ---------- subida automática ----------

    private void enableAuto() {
        if (!Uploader.hasMediaPermission(this)) {
            List<String> perms = new ArrayList<String>();
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES);
                perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            } else {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT >= 29) perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
            requestPermissions(perms.toArray(new String[0]), REQ_MEDIA_PERMS);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, 99);
        }
        new AlertDialog.Builder(this)
                .setTitle("Subida automática")
                .setMessage("¿Qué fotos copio al disco?")
                .setPositiveButton("Solo las nuevas desde ahora", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Uploader.markEverythingAsDone(MainActivity.this);
                        turnAutoOn(false);
                    }
                })
                .setNeutralButton("Todas, también las que ya tengo", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setWatermark(MainActivity.this, 0, 0);
                        turnAutoOn(true);
                    }
                })
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setSwitch(false);
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void turnAutoOn(boolean scanNow) {
        Prefs.setAuto(this, true);
        AutoUploadJob.schedule(this);
        if (scanNow) UploadService.scan(this);
    }

    // ---------- bloqueo con huella o contraseña ----------

    private void askLockPassword() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText pw = new EditText(this);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setHint("Contraseña de Súbelo");
        pw.setSingleLine(true);
        form.addView(pw);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Proteger Súbelo")
                .setMessage("Escribe tu contraseña de Súbelo (la de entrar como " + Prefs.user(this) + "). "
                        + "Lo compruebo con el box, así que tienes que estar en el Wi-Fi de casa.")
                .setView(form)
                .setPositiveButton("Activar", null)
                .setNegativeButton("Cancelar", null)
                .setCancelable(false)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        setLockSwitch(false);
                    }
                });
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String p = pw.getText().toString();
                        if (p.isEmpty()) {
                            pw.setError("Escríbela");
                            return;
                        }
                        d.dismiss();
                        verifyAndLock(p);
                    }
                });
            }
        });
        d.show();
    }

    private void verifyAndLock(final String password) {
        final String user = Prefs.user(this);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    BoxClient bc = BoxClient.connect(MainActivity.this);
                    JSONObject r = bc.login(user, password);
                    String token = r.optString("token");
                    if (!token.isEmpty()) {
                        try {
                            bc.logout(); // la sesión de antes; nos quedamos con la nueva
                        } catch (IOException ignored) {
                        }
                        Prefs.setLogin(MainActivity.this, r.optString("user", user), token);
                    }
                } catch (BoxClient.BoxException e) {
                    err = e.code == 401 || e.code == 403 ? "Contraseña incorrecta." : e.getMessage();
                } catch (IOException e) {
                    err = "No encuentro el box: conéctate al Wi-Fi de casa y vuelve a intentarlo.\n(" + e.getMessage() + ")";
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            setLockSwitch(false);
                            alert("✖ " + error);
                            return;
                        }
                        AppLock.enable(MainActivity.this, password);
                        lockBio.setEnabled(AppLock.biometricPossible(MainActivity.this));
                        alert("🔒 Listo. Desde ahora Súbelo pide "
                                + (AppLock.useBiometric(MainActivity.this) ? "tu huella o tu contraseña" : "tu contraseña")
                                + " al abrirlo.");
                    }
                });
            }
        });
    }

    private void setLockSwitch(boolean on) {
        ignoreSwitch = true;
        lockSwitch.setChecked(on);
        ignoreSwitch = false;
    }

    private void setSwitch(boolean on) {
        ignoreSwitch = true;
        autoSwitch.setChecked(on);
        ignoreSwitch = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LOCATION) {
            if (pendingUpload != null) askFolder(); // con o sin permiso, se sube igual
            return;
        }
        if (requestCode != REQ_MEDIA_PERMS) return;
        if (Uploader.hasMediaPermission(this)) {
            enableAuto();
        } else {
            setSwitch(false);
            alert("Sin permiso para ver tus fotos no puedo subirlas automáticamente. "
                    + "Puedes darlo en Ajustes › Aplicaciones › Súbelo › Permisos.");
        }
    }

    // ---------- utilidades ----------

    private void openBox(Class<?> screen) {
        if (Prefs.host(this) == null) {
            alert("Primero pulsa «Buscar el box».");
            return;
        }
        startActivity(new Intent(this, screen));
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show();
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        Brutal.style(c);
        return c;
    }

    private Button button(String label, View.OnClickListener l) {
        return Brutal.button(this, label, Brutal.WHITE, Brutal.INK, l);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String gb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9);
    }
}
