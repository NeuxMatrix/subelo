package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Traer de Google Fotos: eliges las fotos en el selector de Google (Google no deja que otras apps
 * vean todas tus fotos) y el box las copia a la carpeta del disco que digas.
 */
public class GoogleActivity extends Activity {
    private static final int REQ_DEST = 51;
    private static final String CLOUD = "https://console.cloud.google.com/auth/clients";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout setupCard, importCard;
    private TextView stateText, errorText;
    private EditText clientField, secretField;
    private Button pickButton, reopenButton, cancelButton, viewButton, resetButton;
    private JSONObject last;
    private boolean busy, polling;
    private String authError;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (polling) handler.postDelayed(this, 2000);
        }
    };

    private final GoogleAuth.Listener authListener = new GoogleAuth.Listener() {
        @Override
        public void done(final String error) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    authError = error;
                    refresh();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Brutal.CREAM);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(12), dp(28));
        sv.addView(col);

        col.addView(Brutal.title(this, "Traer de Google Fotos", 28, Brutal.INK));
        TextView intro = Brutal.text(this, 15, Brutal.INK);
        intro.setText("Eliges las fotos en Google Fotos (también las de un álbum: búscalo por su nombre) "
                + "y el box las copia a la carpeta del disco que digas.");
        intro.setPadding(0, dp(6), 0, dp(4));
        col.addView(intro);

        // configurar (una vez)
        setupCard = Brutal.card(this, "g // preparar google (una vez)", Brutal.WHITE);
        TextView steps = Brutal.text(this, 14, Brutal.INK);
        steps.setText("Google pide que cada app tenga su propia «llave». Se hace una vez (mejor desde el ordenador) "
                + "y sirve para Fer y para Julss:\n\n"
                + "1. Entra en console.cloud.google.com con tu cuenta de Google y crea un proyecto («Subelo»).\n"
                + "2. Busca «Google Photos Picker API» y pulsa Habilitar.\n"
                + "3. En «Google Auth Platform» rellena la pantalla de consentimiento: nombre Súbelo, tu correo, tipo Externo. "
                + "En «Público», añade como usuarios de prueba tu Gmail y el de Julss.\n"
                + "4. En «Clientes», crea un cliente de tipo «Aplicación de escritorio».\n"
                + "5. Copia el ID de cliente y el secreto (el secreto solo se ve al crearlo) y pégalos aquí.");
        setupCard.addView(steps);
        setupCard.addView(Brutal.button(this, "Abrir Google Cloud", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(CLOUD);
            }
        }), Brutal.full(this));
        clientField = new EditText(this);
        clientField.setSingleLine(true);
        clientField.setHint("ID de cliente (….apps.googleusercontent.com)");
        setupCard.addView(clientField, new LinearLayout.LayoutParams(-1, -2));
        secretField = Brutal.password(this, "Secreto de cliente (GOCSPX-…)");
        setupCard.addView(secretField, new LinearLayout.LayoutParams(-1, -2));
        setupCard.addView(Brutal.button(this, "Guardar", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSetup();
            }
        }), Brutal.full(this));
        col.addView(setupCard, Brutal.cardParams(this));

        // traer
        importCard = Brutal.card(this, "⬇ // google fotos", Brutal.WHITE);
        stateText = Brutal.text(this, 16, Brutal.INK);
        importCard.addView(stateText);
        errorText = Brutal.text(this, 14, Brutal.RED);
        errorText.setPadding(0, dp(6), 0, 0);
        importCard.addView(errorText);
        pickButton = Brutal.button(this, "Elegir fotos en Google Fotos", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDest();
            }
        });
        importCard.addView(pickButton, Brutal.full(this));
        reopenButton = Brutal.button(this, "Abrir otra vez Google Fotos", Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject j = job();
                if (j != null && !j.isNull("pickerUri")) open(j.optString("pickerUri"));
            }
        });
        importCard.addView(reopenButton, Brutal.full(this));
        viewButton = Brutal.button(this, "Ver la carpeta", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject j = job();
                if (j != null) startActivity(new Intent(GoogleActivity.this, ExploreActivity.class).putExtra("path", j.optString("dest")));
            }
        });
        importCard.addView(viewButton, Brutal.full(this));
        cancelButton = Brutal.button(this, "Parar", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });
        importCard.addView(cancelButton, Brutal.full(this));
        col.addView(importCard, Brutal.cardParams(this));

        TextView note = Brutal.mono(this, 11, Brutal.INK);
        note.setText("GOOGLE QUITA LA UBICACIÓN A LAS FOTOS QUE DA A OTRAS APPS: ESTAS NO SALDRÁN EN EL MAPA. "
                + "SÚBELO NO GUARDA NINGÚN PERMISO DE GOOGLE: SOLO PUEDE LEER LAS FOTOS QUE ELIJAS, Y SOLO MIENTRAS SE COPIAN. "
                + "SI YA ESTÁN EN LA CARPETA (MISMO NOMBRE Y TAMAÑO), SE SALTAN.");
        note.setPadding(dp(2), dp(10), 0, 0);
        col.addView(note);
        resetButton = Brutal.button(this, "Cambiar la llave de Google", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        col.addView(resetButton, Brutal.full(this));

        setContentView(sv);
        show(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GoogleAuth a = GoogleAuth.current;
        if (a != null) a.setListener(authListener);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    // ---------- estado ----------

    private JSONObject job() {
        return last == null ? null : last.optJSONObject("job");
    }

    private void refresh() {
        if (busy) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = BoxClient.connect(GoogleActivity.this).google();
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
                                setupCard.setVisibility(View.GONE);
                                importCard.setVisibility(View.VISIBLE);
                                hide(pickButton, reopenButton, viewButton, cancelButton, resetButton);
                            }
                        }
                    });
                }
            }
        });
    }

    private static void hide(View... vs) {
        for (View v : vs) v.setVisibility(View.GONE);
    }

    private static void showIf(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void show(JSONObject o) {
        last = o;
        if (o == null) {
            setupCard.setVisibility(View.GONE);
            stateText.setText("Mirando el box…");
            hide(errorText, pickButton, reopenButton, viewButton, cancelButton, resetButton);
            return;
        }
        boolean configured = o.optBoolean("configured");
        showIf(setupCard, !configured);
        showIf(importCard, configured);
        showIf(resetButton, configured);
        GoogleAuth auth = GoogleAuth.current;
        boolean waitingAuth = auth != null && auth.waiting();
        JSONObject j = o.optJSONObject("job");
        String state = j == null ? "" : j.optString("state");
        boolean active = state.equals("picking") || state.equals("downloading");
        String err = authError;
        String where = j == null ? "" : "DISCO:/" + Prefs.user(this) + "/" + j.optString("dest");
        if (waitingAuth) {
            stateText.setText("Esperando a que des permiso en Google (en el navegador). Al aceptar se abre Google Fotos para elegir.");
        } else if (state.equals("picking")) {
            stateText.setText("Elige las fotos en Google Fotos y pulsa «Hecho». Luego vuelve aquí.\n\nIrán a " + where);
            err = null;
        } else if (state.equals("downloading")) {
            int total = j.optInt("total"), done = j.optInt("done");
            stateText.setText("Copiando " + done + " de " + total + "…"
                    + (j.isNull("current") ? "" : "\n" + j.optString("current")) + "\n\nA " + where);
            err = null;
        } else if (state.equals("done") || state.equals("cancelled") || state.equals("error")) {
            int copied = j.optInt("copied"), already = j.optInt("already"), failed = j.optInt("failed");
            String head = state.equals("done") ? "✔ Listo." : state.equals("cancelled") ? "Parado: lo ya copiado se queda." : "✖ No se ha terminado.";
            stateText.setText(head + "\n" + (copied == 1 ? "1 copiada" : copied + " copiadas")
                    + (already > 0 ? " · " + already + " ya estaban" : "")
                    + (failed > 0 ? " · " + failed + " sin copiar" : "")
                    + "\nEn " + where);
            if (err == null && !j.isNull("error")) err = j.optString("error");
        } else {
            stateText.setText("Elige en Google Fotos las fotos que quieras traer: primero te pregunto a qué carpeta del disco van.");
        }
        errorText.setText(err == null ? "" : "✖ " + err);
        showIf(errorText, err != null);
        showIf(pickButton, configured && !active && !waitingAuth);
        pickButton.setText(j == null ? "Elegir fotos en Google Fotos" : "Elegir más fotos");
        showIf(reopenButton, state.equals("picking") && !j.isNull("pickerUri"));
        showIf(viewButton, j != null && !active && j.optInt("copied") + j.optInt("already") > 0);
        showIf(cancelButton, active || waitingAuth);
        boolean poll = active || waitingAuth;
        if (poll && !polling) {
            polling = true;
            handler.postDelayed(this.poll, 2000);
        } else if (!poll) {
            polling = false;
            handler.removeCallbacks(this.poll);
        }
    }

    // ---------- configurar ----------

    private void saveSetup() {
        String id = clientField.getText().toString().trim(), secret = secretField.getText().toString().trim();
        if (id.startsWith("{")) { // han pegado el JSON que descarga Google Cloud
            try {
                JSONObject o = new JSONObject(id);
                JSONObject c = o.optJSONObject("installed");
                if (c == null) c = o.optJSONObject("web");
                if (c == null) c = o;
                id = c.optString("client_id", "");
                if (secret.isEmpty()) secret = c.optString("client_secret", "");
            } catch (JSONException ignored) {
            }
        }
        final String fid = id, fsecret = secret;
        act(new Call() {
            @Override
            public JSONObject run(BoxClient bc) throws IOException {
                return bc.googleSetup(fid, fsecret);
            }
        });
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setMessage("¿Borrar del box la llave de Google? Para volver a traer fotos tendrás que pegar otra.")
                .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        act(new Call() {
                            @Override
                            public JSONObject run(BoxClient bc) throws IOException {
                                return bc.googleReset();
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- traer ----------

    private void chooseDest() {
        startActivityForResult(new Intent(this, FolderActivity.class)
                .putExtra(FolderActivity.EXTRA_PICK, "Google")
                .putExtra(FolderActivity.EXTRA_TITLE, "¿Dónde guardo las fotos de Google?")
                .putExtra(FolderActivity.EXTRA_OK, "⬇ Traer aquí"), REQ_DEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DEST || resultCode != RESULT_OK || data == null || last == null) return;
        String dest = data.getStringExtra("path");
        try {
            authError = null;
            GoogleAuth a = GoogleAuth.start(this, last.optString("clientId"), last.optString("scope"),
                    dest == null ? "" : dest, authListener);
            show(last);
            open(a.url);
        } catch (IOException e) {
            alert("✖ " + e.getMessage());
        }
    }

    private void stop() {
        GoogleAuth a = GoogleAuth.current;
        if (a != null) a.cancel();
        act(new Call() {
            @Override
            public JSONObject run(BoxClient bc) throws IOException {
                return bc.googleCancel();
            }
        });
    }

    private interface Call {
        JSONObject run(BoxClient bc) throws IOException;
    }

    private void act(final Call call) {
        busy = true;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject r = null;
                String err = null;
                try {
                    r = call.run(BoxClient.connect(GoogleActivity.this));
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final JSONObject res = r;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (error != null) alert("✖ " + error);
                        if (res != null) show(res);
                        else refresh();
                    }
                });
            }
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            alert("No encuentro un navegador para abrir " + url);
        }
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Vale", null).show();
    }

    private int dp(float v) {
        return Brutal.dp(this, v);
    }
}
