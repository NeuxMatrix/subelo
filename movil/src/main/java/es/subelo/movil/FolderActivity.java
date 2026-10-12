package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navegador de carpetas del disco del box, para elegir dónde se guardan las fotos o, con
 * {@link #EXTRA_PICK}, a dónde mover algo (devuelve la carpeta en el extra «path»).
 */
public class FolderActivity extends Activity {
    private static final String UP = "↑  SUBIR UN NIVEL";
    static final String EXTRA_PICK = "pick";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OK = "ok";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<String> items = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private TextView pathText;
    private String path;
    private BoxClient bc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        final boolean pick = getIntent().hasExtra(EXTRA_PICK);
        path = pick ? getIntent().getStringExtra(EXTRA_PICK) : Prefs.dest(this);
        int pad = dp(16);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Brutal.CREAM);
        col.setPadding(pad, pad, pad - dp(6), pad);

        String t = getIntent().getStringExtra(EXTRA_TITLE);
        TextView title = Brutal.title(this, t != null ? t : pick ? "Mover a…" : "Elige carpeta", 24, Brutal.INK);
        title.setPadding(0, 0, 0, dp(10));
        col.addView(title);
        pathText = Brutal.mono(this, 13, Brutal.PAPER);
        pathText.setBackground(Brutal.block(this, Brutal.BLACK, 4, 3));
        pathText.setPadding(dp(12), dp(10), dp(16), dp(14));
        col.addView(pathText, Brutal.full(this));

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Brutal.INK));
        list.setDividerHeight(dp(2));
        list.setBackground(Brutal.block(this, Brutal.WHITE, 6, 3));
        list.setPadding(dp(3), dp(3), dp(9), dp(9));
        list.setClipToPadding(true);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTypeface(Brutal.bodyBold(FolderActivity.this));
                t.setTextColor(Brutal.INK);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                t.setBackgroundColor(items.get(position).equals(UP) ? Brutal.LILAC : Brutal.WHITE);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String n = items.get(position);
                if (n.equals(UP)) {
                    int i = path.lastIndexOf('/');
                    path = i > 0 ? path.substring(0, i) : "";
                } else {
                    path = path.isEmpty() ? n.substring(3) : path + "/" + n.substring(3);
                }
                load();
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        llp.setMargins(0, dp(10), dp(6), dp(10));
        col.addView(list, llp);

        LinearLayout row = new LinearLayout(this);
        Button mk = Brutal.button(this, "+ Nueva carpeta", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        String ok = getIntent().getStringExtra(EXTRA_OK);
        Button use = Brutal.button(this, ok != null ? ok : pick ? "✓ Mover aquí" : "✓ Usar esta", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pick) {
                    setResult(RESULT_OK, new android.content.Intent().putExtra("path", path));
                    finish();
                    return;
                }
                Prefs.setDest(FolderActivity.this, path);
                setResult(RESULT_OK);
                finish();
            }
        });
        row.addView(mk, Brutal.half(this));
        row.addView(use, Brutal.half(this));
        col.addView(row);
        setContentView(col);
        load();
    }

    private void load() {
        pathText.setText("DISCO:/" + Prefs.user(this) + "/" + path + "\nCARGANDO…");
        final String p = path;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bc == null) bc = BoxClient.connect(FolderActivity.this);
                    List<String> f;
                    try {
                        f = bc.folders(p);
                    } catch (BoxClient.BoxException e) {
                        if (e.code != 404 || p.isEmpty()) throw e;
                        path = "";
                        f = bc.folders("");
                    }
                    final List<String> folders = f;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            items.clear();
                            if (!path.isEmpty()) items.add(UP);
                            for (String n : folders) items.add("▸  " + n);
                            adapter.notifyDataSetChanged();
                            pathText.setText("DISCO:/" + Prefs.user(FolderActivity.this) + "/" + path
                                    + (folders.isEmpty() ? "\n(SIN SUBCARPETAS)" : ""));
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pathText.setText("No se pudo leer el disco: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void newFolder() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setHint("Ej.: Vacaciones 2026");
        new AlertDialog.Builder(this)
                .setTitle("Nueva carpeta en /" + path)
                .setView(et)
                .setPositiveButton("Crear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim().replace("/", "-");
                        if (name.isEmpty()) return;
                        final String np = path.isEmpty() ? name : path + "/" + name;
                        bg.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (bc == null) bc = BoxClient.connect(FolderActivity.this);
                                    bc.mkdir(np);
                                    path = np;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            load();
                                        }
                                    });
                                } catch (final IOException e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(FolderActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
