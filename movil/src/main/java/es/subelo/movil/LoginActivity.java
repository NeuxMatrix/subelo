package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla de entrada: busca el box y muestra los dos usuarios (Fer y Julss).
 * La primera vez que alguien pulsa su nombre, inventa su contraseña; después, la escribe para entrar.
 */
public class LoginActivity extends Activity {
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private TextView boxText;
    private LinearLayout usersBox;
    private String code;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.loggedIn(this)) {
            openMain();
            return;
        }
        loadUsers();
    }

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

        TextView title = Brutal.title(this, "¿Quién eres?", 34, Brutal.INK);
        title.setPadding(dp(2), dp(26), 0, dp(6));
        col.addView(title);
        TextView sub = Brutal.mono(this, 12, Brutal.INK);
        sub.setText("CADA UNO VE SOLO SU CARPETA DEL DISCO");
        sub.setPadding(dp(2), 0, 0, dp(8));
        col.addView(sub);

        usersBox = new LinearLayout(this);
        usersBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(usersBox);

        LinearLayout box = Brutal.card(this, "tu box", Brutal.WHITE);
        boxText = Brutal.text(this, 15, Brutal.INK);
        box.addView(boxText);
        LinearLayout row = new LinearLayout(this);
        row.addView(Brutal.button(this, "Buscar el box", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchBox();
            }
        }), Brutal.half(this));
        row.addView(Brutal.button(this, "IP a mano", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualIp();
            }
        }), Brutal.half(this));
        box.addView(row);
        col.addView(box, Brutal.cardParams(this));

        setContentView(scroll);
    }

    // ---------- conectar con el box y pedir los usuarios ----------

    private void loadUsers() {
        usersBox.removeAllViews();
        if (Prefs.host(this) == null) {
            boxText.setText("Buscando el box en la red de casa…");
            searchBox();
            return;
        }
        boxText.setText("Conectando con " + (Prefs.boxName(this) != null ? Prefs.boxName(this) : Prefs.host(this)) + "…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject users = null;
                String err = null;
                BoxClient bc = BoxClient.fromPrefs(LoginActivity.this);
                try {
                    users = bc.users();
                } catch (BoxClient.WrongBoxException e) {
                    err = e.getMessage();
                } catch (IOException e) {
                    // IP antigua: probamos a buscarlo
                    List<Discovery.Found> found = Discovery.discover(LoginActivity.this, 3000);
                    Discovery.Found pick = null;
                    for (Discovery.Found f : found) if (f.name.equals(Prefs.boxName(LoginActivity.this))) pick = f;
                    if (pick == null && found.size() == 1 && Prefs.certFp(LoginActivity.this) == null) pick = found.get(0);
                    if (pick != null) {
                        bc = new BoxClient(pick.host, pick.port, null, Prefs.certFp(LoginActivity.this));
                        try {
                            users = bc.users();
                            Prefs.setBox(LoginActivity.this, pick.name, pick.host, pick.port);
                        } catch (IOException e2) {
                            err = e2.getMessage();
                        }
                    } else {
                        err = "No encuentro el box. ¿Estás en el Wi-Fi de casa y el box está encendido?";
                    }
                }
                final JSONObject u = users;
                final String error = err;
                final BoxClient client = bc;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (u == null) {
                            boxText.setText("✖ " + error);
                            return;
                        }
                        String fp = Prefs.certFp(LoginActivity.this) != null
                                ? Prefs.certFp(LoginActivity.this) : client.fingerprint();
                        code = BoxClient.shortCode(fp);
                        boxText.setText("✔ " + (Prefs.boxName(LoginActivity.this) != null
                                ? Prefs.boxName(LoginActivity.this) : client.host) + "  🔒 " + code);
                        showUsers(client, u);
                    }
                });
            }
        });
    }

    private void showUsers(final BoxClient bc, JSONObject o) {
        usersBox.removeAllViews();
        JSONArray arr = o.optJSONArray("users");
        final int min = o.optInt("minLength", 4);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject u = arr.optJSONObject(i);
            final String name = u.optString("name");
            final boolean has = u.optBoolean("hasPassword");
            boolean first = i == 0;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int fill = first ? Brutal.ORANGE : Brutal.PURPLE;
            int ink = first ? Brutal.BLACK : Brutal.SNOW;
            card.setBackground(Brutal.buttonBg(this, fill, first ? Brutal.PURPLE : Brutal.ORANGE, 7));
            card.setPadding(dp(20), dp(18), dp(27), dp(25));
            card.setClickable(true);
            card.setFocusable(true);
            TextView n = Brutal.title(this, name, 40, ink);
            TextView s = Brutal.mono(this, 13, ink);
            s.setText(has ? "▸ ENTRAR" : "▸ PRIMERA VEZ: CREA TU CONTRASEÑA");
            s.setPadding(0, dp(8), 0, 0);
            card.addView(n);
            card.addView(s);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askPassword(bc, name, has, min);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(10), 0, dp(10));
            usersBox.addView(card, lp);
        }
    }

    private void askPassword(final BoxClient bc, final String user, final boolean has, final int min) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText pw = password(has ? "Contraseña" : "Contraseña nueva");
        final EditText pw2 = password("Repítela");
        form.addView(pw);
        if (!has) form.addView(pw2);
        String msg = has ? "Escribe tu contraseña." : "Es la primera vez que entras como " + user
                + ". Inventa una contraseña (mínimo " + min + " caracteres). Solo tú podrás ver tu carpeta.";
        if (Prefs.certFp(this) == null && code != null) {
            msg += "\n\nCódigo del box: " + code + "\nDebe coincidir con el de la tele. Si no coincide, cancela.";
        }
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(has ? "Hola, " + user : "Hola, " + user + ": crea tu contraseña")
                .setMessage(msg)
                .setView(form)
                .setPositiveButton("Entrar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String p = pw.getText().toString();
                        if (p.length() < min) {
                            pw.setError("Mínimo " + min + " caracteres");
                            return;
                        }
                        if (!has && !p.equals(pw2.getText().toString())) {
                            pw2.setError("No coinciden");
                            return;
                        }
                        d.dismiss();
                        login(bc, user, p);
                    }
                });
            }
        });
        d.show();
    }

    private EditText password(String hint) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(hint);
        et.setSingleLine(true);
        return et;
    }

    private void login(final BoxClient bc, final String user, final String password) {
        boxText.setText("Entrando como " + user + "…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    JSONObject r = bc.login(user, password);
                    if (Prefs.certFp(LoginActivity.this) == null && bc.fingerprint() != null) {
                        Prefs.setCertFp(LoginActivity.this, bc.fingerprint());
                    }
                    Prefs.setLogin(LoginActivity.this, r.optString("user", user), r.optString("token"));
                    AppLock.loggedIn(LoginActivity.this, password);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            openMain();
                        } else {
                            new AlertDialog.Builder(LoginActivity.this).setMessage("✖ " + error)
                                    .setPositiveButton("Vale", null).show();
                            loadUsers();
                        }
                    }
                });
            }
        });
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        Intent from = getIntent().getParcelableExtra("share");
        if (from != null) i = from.setClass(this, MainActivity.class);
        startActivity(i);
        finish();
    }

    // ---------- elegir el box ----------

    private void searchBox() {
        boxText.setText("Buscando el box en la red de casa…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<Discovery.Found> found = Discovery.discover(LoginActivity.this, 4000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found.isEmpty()) {
                            boxText.setText("No he encontrado el box. Comprueba que el móvil está en el Wi-Fi de casa "
                                    + "y que la tele dice «● ACTIVO». Si sigue sin aparecer, pulsa «IP a mano».");
                        } else if (found.size() == 1) {
                            choose(found.get(0).name, found.get(0).host, found.get(0).port);
                        } else {
                            String[] names = new String[found.size()];
                            for (int i = 0; i < names.length; i++) names[i] = found.get(i).name + " (" + found.get(i).host + ")";
                            new AlertDialog.Builder(LoginActivity.this)
                                    .setTitle("¿Qué box?")
                                    .setItems(names, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int which) {
                                            Discovery.Found f = found.get(which);
                                            choose(f.name, f.host, f.port);
                                        }
                                    }).show();
                        }
                    }
                });
            }
        });
    }

    private void choose(String name, String host, int port) {
        boolean same = host.equals(Prefs.host(this)) && Prefs.certFp(this) != null;
        if (same) Prefs.setBox(this, name, host, port);
        else Prefs.pairNew(this, name, host, port);
        loadUsers();
    }

    private void manualIp() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("192.168.1.50");
        if (Prefs.host(this) != null) et.setText(Prefs.host(this));
        new AlertDialog.Builder(this)
                .setTitle("Dirección del box")
                .setMessage("Escribe la IP que aparece en la pantalla del box.")
                .setView(et)
                .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String h = et.getText().toString().trim().replace("https://", "").replace("http://", "");
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
                        if (!h.isEmpty()) choose(null, h, port);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private int dp(int v) {
        return Brutal.dp(this, v);
    }
}
