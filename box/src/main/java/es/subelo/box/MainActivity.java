package es.subelo.box;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pantalla del box (pensada para manejarla con el mando de la tele). */
public class MainActivity extends Activity {
    private static final int REQ_TREE = 1;
    private static final int REQ_PERMS = 2;

    private TextView serverText, addressText, usersText, codeText, storageText, errorText;
    private Button toggleButton, updateButton;
    private volatile Updater.Release update;
    private volatile String fingerprint;
    private volatile String caThumbprint; // del certificado para el ordenador (Windows enseña esta huella)
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        buildUi();
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMS);
        }
        if (Config.autostart(this) && !ServerService.running) ServerService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        checkUpdate(false);
        if (UsbPicker.returningFromSettings) {
            // volvemos de la pantalla de permisos: reabrimos la lista de discos
            UsbPicker.returningFromSettings = false;
            openUsbPicker();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Brutal.CREAM);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(28), dp(48), dp(28));
        scroll.addView(page);

        // cabecera: logo + etiqueta
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Brutal.logo(this, 64));
        TextView tag = Brutal.mono(this, 16, Brutal.BLACK);
        tag.setText("SERVIDOR DE FOTOS · NAS");
        tag.setBackground(Brutal.block(this, Brutal.ORANGE, 4, 3));
        tag.setPadding(dp(12), dp(6), dp(16), dp(10));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(24);
        head.addView(tag, tlp);
        page.addView(head);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(cols, new LinearLayout.LayoutParams(-1, -2));

        // columna izquierda: estado
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1.15f);
        llp.rightMargin = dp(36);
        cols.addView(left, llp);

        LinearLayout server = Brutal.card(this, "01 // servidor", Brutal.BLACK);
        serverText = Brutal.title(this, "", 26, Brutal.PAPER);
        addressText = Brutal.mono(this, 20, Brutal.ORANGE);
        addressText.setPadding(0, dp(8), 0, 0);
        server.addView(serverText);
        server.addView(addressText);
        left.addView(server, Brutal.cardParams(this));

        LinearLayout usersCard = Brutal.card(this, "02 // usuarios", Brutal.ORANGE);
        usersText = Brutal.title(this, "", 26, Brutal.BLACK);
        usersText.setLineSpacing(dp(6), 1f);
        codeText = Brutal.mono(this, 18, Brutal.BLACK);
        codeText.setPadding(0, dp(10), 0, 0);
        usersCard.addView(usersText);
        usersCard.addView(codeText);
        left.addView(usersCard, Brutal.cardParams(this));

        LinearLayout disk = Brutal.card(this, "03 // disco", Brutal.LILAC);
        storageText = Brutal.text(this, 19, Brutal.INK);
        storageText.setTypeface(Brutal.bodyBold(this));
        disk.addView(storageText);
        left.addView(disk, Brutal.cardParams(this));

        errorText = Brutal.mono(this, 16, Brutal.SNOW);
        errorText.setBackground(Brutal.block(this, Brutal.RED, 5, 3));
        errorText.setPadding(dp(14), dp(10), dp(18), dp(14));
        left.addView(errorText, Brutal.cardParams(this));

        // columna derecha: acciones
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        cols.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView actions = Brutal.title(this, "Acciones", 22, Brutal.INK);
        actions.setPadding(0, dp(14), 0, dp(4));
        right.addView(actions);
        Button pick = Brutal.button(this, "Elegir carpeta del disco USB", Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
            }
        });
        right.addView(pick, Brutal.full(this));
        toggleButton = Brutal.button(this, "Detener servidor", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ServerService.running) {
                    Config.setAutostart(MainActivity.this, false);
                    ServerService.stop(MainActivity.this);
                } else {
                    Config.setAutostart(MainActivity.this, true);
                    ServerService.start(MainActivity.this);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 600);
            }
        });
        right.addView(toggleButton, Brutal.full(this));
        for (final String u : Users.NAMES) {
            right.addView(Brutal.button(this, "Borrar contraseña de " + u, Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmReset(u);
                }
            }), Brutal.full(this));
        }
        updateButton = Brutal.button(this, "Buscar actualizaciones", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (update != null) installUpdate();
                else checkUpdate(true);
            }
        });
        right.addView(updateButton, Brutal.full(this));
        right.addView(Brutal.darkToggle(this, "☾ Modo oscuro", "☀ Modo claro"), Brutal.full(this));

        LinearLayout help = Brutal.card(this, "cómo se usa", Brutal.WHITE);
        TextView ht = Brutal.text(this, 15, Brutal.INK);
        ht.setText("1. Conecta el disco duro al USB del box.\n"
                + "2. «Elegir carpeta del disco USB» y elige tu disco. Si Android pide el permiso de "
                + "«todos los archivos», actívalo y vuelve.\n"
                + "3. En el móvil, app Súbelo › «Buscar el box». Comprueba que el código coincide, pulsa tu nombre "
                + "y escribe tu contraseña (la primera vez, inventas una).\n"
                + "4. Desde un ordenador abre la dirección de arriba. El navegador avisará del certificado "
                + "la primera vez: «Configuración avanzada» › «Continuar».\n\n"
                + "Cada usuario solo ve su carpeta del disco (Fer o Julss). Si alguien olvida su contraseña, bórrala "
                + "aquí y pondrá otra al entrar. Tras 5 contraseñas incorrectas, ese aparato queda bloqueado 5 minutos. "
                + "Desactiva la suspensión del box para que esté siempre disponible.");
        help.addView(ht);
        right.addView(help, Brutal.cardParams(this));

        setContentView(scroll);
        pick.requestFocus();
    }

    private void refresh() {
        final Store st = Config.store(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (fingerprint == null || fingerprint.isEmpty()) fingerprint = Tls.fingerprint(getFilesDir());
                if (caThumbprint == null || caThumbprint.isEmpty()) caThumbprint = Tls.caThumbprint(getFilesDir());
                final String storage;
                if (st == null) {
                    storage = "Sin configurar. Elige una carpeta del disco USB.";
                } else if (!st.available()) {
                    storage = st.describe() + "\n⚠ NO DISPONIBLE. ¿Está conectado el disco?";
                } else {
                    long free = st.free(), total = st.total();
                    String used = Usage.lines(MainActivity.this, st); // lo que ocupa cada usuario
                    storage = st.describe()
                            + (free >= 0 ? "\nLibre: " + gb(free) + " de " + gb(total) : "")
                            + (used.isEmpty() ? "" : "\n\n" + used);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        storageText.setText(storage);
                        showUsers();
                    }
                });
            }
        }).start();

        List<String> ips = Net.localIps();
        String addr = ips.isEmpty() ? "(sin red)" : "https://" + ips.get(0) + ":" + Config.PORT
                + "\nComo app en el ordenador: https://" + ips.get(0) + ":" + Config.WEB_PORT;
        serverText.setText(ServerService.running ? "● ACTIVO" : "○ DETENIDO");
        serverText.setTextColor(ServerService.running ? 0xFF3DDC84 : Brutal.ORANGE);
        addressText.setText(addr);
        showUsers();
        toggleButton.setText(ServerService.running ? "DETENER SERVIDOR" : "INICIAR SERVIDOR");
        errorText.setText(ServerService.error == null ? "" : ServerService.error);
        errorText.setVisibility(ServerService.error == null ? View.GONE : View.VISIBLE);
    }

    private void showUsers() {
        StringBuilder sb = new StringBuilder();
        for (String u : Users.NAMES) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(u).append(Users.hasPassword(this, u) ? "  ✔ con contraseña" : "  ○ sin contraseña");
        }
        usersText.setText(sb.toString().toUpperCase(java.util.Locale.getDefault()));
        codeText.setText("CÓDIGO DEL BOX " + Tls.shortCode(fingerprint)
                + (caThumbprint == null || caThumbprint.length() < 8 ? "" : "\nCERTIFICADO DEL ORDENADOR " + caThumbprint.substring(0, 8)));
    }

    private void confirmReset(final String user) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("¿Borrar la contraseña de " + user + "?")
                .setMessage("Se cerrará su sesión en el móvil y en el ordenador, y se desconectará su Amazon. "
                        + "La próxima vez que " + user + " entre, pondrá una contraseña nueva y tendrá que volver a "
                        + "entrar en Amazon. Sus fotos no se tocan.")
                .setPositiveButton("Borrar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Users.reset(MainActivity.this, user);
                            // quien ponga la contraseña nueva no hereda su Amazon (Echo y copias)
                            EchoSync.forgetSession(MainActivity.this, user);
                        } catch (java.io.IOException e) {
                            ServerService.error = "No se pudo borrar la contraseña: " + e.getMessage();
                        }
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- elegir carpeta con el selector de Android ----------

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        if (Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 29) {
            StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                Intent a = v.createAccessIntent(null);
                if (a == null) continue;
                try {
                    startActivityForResult(a, REQ_TREE);
                    return;
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        openUsbPicker();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            openUsbPicker();
            return;
        }
        Config.setStore(this, "saf", uri.toString());
        Toast.makeText(this, "Carpeta guardada", Toast.LENGTH_SHORT).show();
        refresh();
    }

    // ---------- actualizaciones ----------

    private void checkUpdate(final boolean now) {
        if (now) updateButton.setText("BUSCANDO…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Updater.Release r = null;
                String err = null;
                try {
                    r = now ? Updater.check(MainActivity.this) : Updater.checkThrottled(MainActivity.this);
                } catch (java.io.IOException e) {
                    err = e.getMessage();
                }
                final Updater.Release found = r;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        update = found;
                        if (found != null) {
                            updateButton.setText("⬆ INSTALAR VERSIÓN " + found.name.toUpperCase(Locale.getDefault()));
                        } else {
                            updateButton.setText("BUSCAR ACTUALIZACIONES");
                            if (now) Toast.makeText(MainActivity.this, error != null ? "No se pudo comprobar: " + error
                                    : "Ya tienes la última versión (" + Updater.installedName(MainActivity.this) + ")",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void installUpdate() {
        final Updater.Release r = update;
        updateButton.setText("DESCARGANDO…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Updater.install(MainActivity.this, r, null);
                } catch (java.io.IOException e) {
                    err = e.getMessage();
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateButton.setText("⬆ INSTALAR VERSIÓN " + r.name.toUpperCase(Locale.getDefault()));
                        if (error != null) alert("No se pudo descargar la actualización: " + error);
                    }
                });
            }
        }).start();
    }

    // ---------- disco USB sin selector de Android ----------

    private void openUsbPicker() {
        UsbPicker.show(this, new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "Disco guardado", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
    }

    // ---------- utilidades de interfaz ----------

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String gb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9);
    }
}
