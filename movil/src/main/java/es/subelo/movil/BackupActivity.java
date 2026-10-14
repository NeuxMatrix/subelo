package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «☁ Copias en Amazon»: las carpetas con copia de seguridad, vistas directamente desde Amazon
 * Fotos (sirve fuera de casa, sin el box). Pide siempre la contraseña de Súbelo al entrar.
 * Una carpeta se puede borrar de Amazon manteniéndola pulsada: eso lo hace el box (en casa).
 */
public class BackupActivity extends Activity {
    private static final int REQ_LOGIN = 1;
    /** Tiempo que se perdona fuera al ir a ver un vídeo o a entrar en Amazon. */
    private static final long AWAY_MS = 10 * 60 * 1000L;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount() / 1024;
        }
    };
    /** Carpetas abiertas, de la de copias del usuario hacia dentro. */
    private final ArrayList<AmazonDrive.Node> stack = new ArrayList<AmazonDrive.Node>();
    private final List<AmazonDrive.Node> entries = new ArrayList<AmazonDrive.Node>();
    private final List<AmazonDrive.Node> media = new ArrayList<AmazonDrive.Node>();
    private FrameLayout root;
    private TextView pathText, infoText;
    private Button loginButton;
    private GridView grid;
    private Adapter adapter;
    private AmazonDrive drive;
    private boolean unlocked;
    private int loadSeq;
    /** El usuario de Súbelo que abrió esta pantalla: si cambia, se cierra. */
    private String openedFor;
    /** La contraseña ya comprobada, solo en memoria mientras está abierta (para no pedirla otra vez al entrar en Amazon). */
    private String password;
    private AlertDialog passwordDialog;
    private boolean expectReturn;
    private long stoppedAt;

    // visor
    private FrameLayout viewer;
    private ImageView viewerImg;
    private TextView viewerText;
    private int viewerIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        openedFor = Prefs.user(this);
        // que la lista de apps recientes no enseñe las fotos
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Brutal.CREAM);
        pathText = Brutal.mono(this, 13, Brutal.PAPER);
        pathText.setBackgroundColor(Brutal.BLACK);
        pathText.setPadding(dp(14), dp(12), dp(14), dp(12));
        pathText.setText("☁ COPIAS EN AMAZON");
        col.addView(pathText);
        infoText = Brutal.text(this, 15, Brutal.INK);
        infoText.setPadding(dp(16), dp(12), dp(16), dp(4));
        col.addView(infoText);
        loginButton = Brutal.button(this, "Entrar en Amazon", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AmazonActivity.handoff = password;
                expectReturn = true;
                startActivityForResult(new Intent(BackupActivity.this, AmazonActivity.class)
                        .putExtra(AmazonActivity.EXTRA_ONLY_LOGIN, true), REQ_LOGIN);
            }
        });
        loginButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = Brutal.full(this);
        lp.setMargins(dp(16), dp(4), dp(16), dp(8));
        col.addView(loginButton, lp);
        grid = new GridView(this);
        grid.setColumnWidth(dp(108));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                AmazonDrive.Node n = entries.get(pos);
                if (n.folder) {
                    stack.add(n);
                    load();
                } else {
                    open(n);
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                AmazonDrive.Node n = entries.get(pos);
                if (n.folder) {
                    confirmDelete(n);
                } else {
                    Toast.makeText(BackupActivity.this, "Se borran carpetas enteras: mantén pulsada la carpeta",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        askPassword();
    }

    @Override
    protected void onResume() {
        super.onResume();
        expectReturn = false;
        // otro usuario ha entrado en Súbelo en este móvil: estas copias no son suyas
        if (openedFor == null || !openedFor.equals(Prefs.user(this))) {
            lock();
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) stoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (stoppedAt == 0) return;
        long away = SystemClock.elapsedRealtime() - stoppedAt;
        stoppedAt = 0;
        // al salir de esta pantalla y volver se pide la contraseña otra vez
        // (salvo un rato si se ha ido a ver un vídeo o a entrar en Amazon)
        if (unlocked && (!expectReturn || away > AWAY_MS)) {
            lock();
            askPassword();
        }
    }

    /** Lo olvida todo hasta que se vuelva a escribir la contraseña. */
    private void lock() {
        unlocked = false;
        password = null;
        AmazonActivity.handoff = null;
        loadSeq++;
        if (viewer != null) closeViewer();
        drive = null;
        stack.clear();
        entries.clear();
        media.clear();
        cache.evictAll();
        adapter.notifyDataSetChanged();
        infoText.setText("");
        loginButton.setVisibility(View.GONE);
        pathText.setText("☁ COPIAS EN AMAZON");
    }

    @Override
    protected void onDestroy() {
        password = null;
        if (passwordDialog != null) passwordDialog.dismiss();
        bg.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (viewer != null) {
            closeViewer();
            return;
        }
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            load();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && unlocked) start();
    }

    // ---------- contraseña ----------

    private void askPassword() {
        if (passwordDialog != null && passwordDialog.isShowing()) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText pw = Brutal.password(this, "Contraseña de " + Prefs.user(this));
        form.addView(pw);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("☁ Copias en Amazon")
                .setMessage("Escribe tu contraseña de Súbelo.")
                .setView(form)
                .setPositiveButton("Entrar", null)
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        finish();
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
                        checkPassword(d, pw, p);
                    }
                });
            }
        });
        passwordDialog = d;
        d.show();
    }

    private void checkPassword(final AlertDialog d, final EditText pw, final String password) {
        long wait = AmazonSession.waitMs(this);
        if (wait > 0) {
            pw.setError("Demasiados intentos: espera " + (wait / 1000 + 1) + " s");
            return;
        }
        if (AmazonSession.hasPassword(this)) {
            if (AmazonSession.check(this, password)) {
                d.dismiss();
                unlocked(password);
                revalidate(password);
            } else {
                pw.setText("");
                pw.setError("Contraseña incorrecta");
            }
            return;
        }
        // la primera vez se comprueba con el box (luego ya no hace falta estar en casa)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    BoxClient.connect(BackupActivity.this).checkPassword(Prefs.user(BackupActivity.this), password);
                    AmazonSession.rememberPassword(BackupActivity.this, password);
                } catch (BoxClient.BoxException e) {
                    err = e.code == 401 || e.code == 403 ? "Contraseña incorrecta" : e.getMessage();
                } catch (IOException e) {
                    err = "La primera vez tengo que comprobarla con el box: ábrelo en casa, con el Wi-Fi.";
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (error != null) {
                            pw.setText("");
                            pw.setError(error);
                            return;
                        }
                        d.dismiss();
                        unlocked(password);
                    }
                });
            }
        });
    }

    private void unlocked(String password) {
        unlocked = true;
        this.password = password;
        start();
    }

    /**
     * Comprobada sin el box: si estamos en casa, se confirma también con el box. Si la contraseña ha
     * cambiado (o se ha borrado desde la tele), la de antes deja de servir aquí.
     */
    private void revalidate(final String password) {
        final String user = Prefs.user(this);
        if (user == null) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                BoxClient bc;
                try {
                    bc = BoxClient.connect(BackupActivity.this);
                } catch (BoxClient.BoxException e) {
                    if (e.code == 401) revoked(); // el box ha cerrado la sesión de este móvil
                    return;
                } catch (IOException e) {
                    return; // fuera de casa: vale con la comprobación del móvil
                }
                try {
                    bc.checkPassword(user, password);
                } catch (BoxClient.BoxException e) {
                    if (e.code == 401 || e.code == 403) changed();
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Desde otro hilo: la contraseña escrita ya no es la buena. */
    private void changed() {
        AmazonSession.forgetPassword(this);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                lock();
                Toast.makeText(BackupActivity.this, "Tu contraseña de Súbelo ha cambiado: escribe la nueva",
                        Toast.LENGTH_LONG).show();
                askPassword();
            }
        });
    }

    /** Desde otro hilo: el box ya no reconoce este móvil (contraseña borrada o cambiada en la tele). */
    private void revoked() {
        AmazonSession.forgetPassword(this);
        AmazonSession.clear(this);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                lock();
                Toast.makeText(BackupActivity.this, "Tu sesión de Súbelo se ha cerrado: vuelve a entrar", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    // ---------- Amazon ----------

    private void start() {
        String cookie = AmazonSession.cookie(this);
        stack.clear();
        entries.clear();
        adapter.notifyDataSetChanged();
        if (cookie == null) {
            drive = null;
            infoText.setText("Para ver tus copias fuera de casa, entra en tu Amazon desde aquí una vez. "
                    + "La sesión se guarda cifrada en este móvil.");
            loginButton.setVisibility(View.VISIBLE);
            return;
        }
        final AmazonDrive d = drive = new AmazonDrive(cookie, AmazonSession.tld(this));
        loginButton.setVisibility(View.GONE);
        infoText.setText("Buscando tus copias…");
        final int seq = ++loadSeq;
        final String user = Prefs.user(this);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                AmazonDrive.Node top = null;
                String err = null;
                boolean expired = false;
                try {
                    top = d.userFolder(user);
                } catch (AmazonDrive.AmazonException e) {
                    err = e.getMessage();
                    expired = e.code == 401;
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final AmazonDrive.Node t = top;
                final String error = err;
                final boolean exp = expired;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) return;
                        if (error != null) {
                            infoText.setText("✖ " + error);
                            loginButton.setText(exp ? "Volver a entrar en Amazon" : "Entrar en Amazon");
                            loginButton.setVisibility(exp ? View.VISIBLE : View.GONE);
                            return;
                        }
                        if (t == null) {
                            infoText.setText("Aún no hay copias en Amazon. En «Mis fotos por carpetas», entra en una "
                                    + "carpeta y pulsa «☁» › «Hacer backup en Amazon».");
                            return;
                        }
                        t.name = "";
                        stack.add(t);
                        load();
                    }
                });
            }
        });
    }

    private void load() {
        final AmazonDrive d = drive;
        if (d == null || stack.isEmpty()) return;
        final AmazonDrive.Node dir = stack.get(stack.size() - 1);
        StringBuilder where = new StringBuilder("☁ AMAZON:/" + Prefs.user(this));
        for (int i = 1; i < stack.size(); i++) where.append("/").append(stack.get(i).name);
        pathText.setText(where.toString().toUpperCase(Locale.getDefault()));
        infoText.setText("Cargando…");
        infoText.setVisibility(View.VISIBLE);
        entries.clear();
        media.clear();
        adapter.notifyDataSetChanged();
        final int seq = ++loadSeq;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                List<AmazonDrive.Node> list = null;
                String err = null;
                try {
                    list = d.children(dir.id);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final List<AmazonDrive.Node> l = list;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) return;
                        if (error != null) {
                            infoText.setText("✖ " + error);
                            return;
                        }
                        entries.addAll(l);
                        int files = 0;
                        for (AmazonDrive.Node n : l) {
                            if (!n.folder) files++;
                            if (!n.folder && (n.isImage() || n.isVideo())) media.add(n);
                        }
                        if (l.isEmpty()) infoText.setText("Carpeta vacía (o aún subiéndose desde el box).");
                        else if (stack.size() == 1) infoText.setText("Tus carpetas con copia. Toca una para verla; "
                                + "mantenla pulsada para borrarla de Amazon.");
                        else infoText.setText(files == 1 ? "1 archivo" : files + " archivos");
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    // ---------- borrar de Amazon ----------

    /** La ruta de una carpeta de aquí dentro de la de copias del usuario (la misma que en el disco). */
    private String pathOf(AmazonDrive.Node n) {
        StringBuilder p = new StringBuilder();
        for (int i = 1; i < stack.size(); i++) p.append(stack.get(i).name).append('/');
        return p.append(n.name).toString();
    }

    private void confirmDelete(final AmazonDrive.Node n) {
        final String rel = pathOf(n);
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar «" + n.name + "» de Amazon?")
                .setMessage("Se borra de Amazon Fotos con todo lo que tiene dentro: va a su papelera, donde se puede "
                        + "recuperar durante 90 días. Si esta carpeta tiene copia, deja de copiarse. En el disco del box "
                        + "no se toca nada.\n\nLo borra el box, así que tienes que estar en casa, con el Wi-Fi.")
                .setPositiveButton("Borrar de Amazon", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        delete(n, rel);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void delete(final AmazonDrive.Node n, final String rel) {
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    BoxClient.connect(BackupActivity.this).backupRemove(rel, n.id);
                } catch (BoxClient.BoxException e) {
                    err = e.getMessage();
                } catch (IOException e) {
                    err = "No encuentro el box: para borrar de Amazon tienes que estar en casa, con el Wi-Fi.";
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || !unlocked) return;
                        if (error != null) {
                            Toast.makeText(BackupActivity.this, "✖ " + error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(BackupActivity.this, "Borrando «" + n.name + "» de Amazon. Tarda un poco.",
                                Toast.LENGTH_LONG).show();
                        if (entries.remove(n)) adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private void open(AmazonDrive.Node n) {
        if (drive == null) return;
        if (n.isVideo()) {
            String link = drive.videoLink(n);
            if (link == null) {
                Toast.makeText(this, "Amazon no deja ver este vídeo desde aquí", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                expectReturn = true;
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(link), "video/*"));
            } catch (ActivityNotFoundException e) {
                expectReturn = false;
                Toast.makeText(this, "No hay reproductor de vídeo", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!n.isImage()) {
            Toast.makeText(this, "Aquí solo se ven fotos y vídeos", Toast.LENGTH_SHORT).show();
            return;
        }
        openViewer(media.indexOf(n));
    }

    private void loadThumb(final ImageView img, final AmazonDrive.Node n, final int px) {
        final AmazonDrive d = drive;
        if (d == null) return;
        final String key = n.id + "@" + px;
        img.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) {
            img.setImageBitmap(b);
            return;
        }
        thumbs.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(img.getTag())) return; // la celda ya enseña otra cosa
                final Bitmap bm;
                try {
                    bm = d.image(n, px);
                } catch (IOException | OutOfMemoryError e) {
                    return;
                }
                cache.put(key, bm);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(img.getTag())) img.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    // ---------- visor de fotos ----------

    private void openViewer(int index) {
        if (index < 0) return;
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(0xFF000000);
        viewerImg = new ImageView(this);
        viewerImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(viewerImg, new FrameLayout.LayoutParams(-1, -1));
        viewerText = Brutal.mono(this, 12, 0xFFFFFFFF);
        viewerText.setBackgroundColor(0xAA000000);
        viewerText.setPadding(dp(14), dp(10), dp(14), dp(10));
        viewerText.setSingleLine(true);
        viewerText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        viewer.addView(viewerText, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy)) return false;
                show(viewerIndex + (vx < 0 ? 1 : -1));
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float w = viewer.getWidth();
                if (e.getX() < w * 0.25f) show(viewerIndex - 1);
                else if (e.getX() > w * 0.75f) show(viewerIndex + 1);
                else viewerText.setVisibility(viewerText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }
        });
        viewer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return gd.onTouchEvent(e);
            }
        });
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        show(index);
    }

    private void show(int index) {
        if (viewer == null || index < 0 || index >= media.size()) return;
        viewerIndex = index;
        final AmazonDrive.Node n = media.get(index);
        viewerText.setText((index + 1) + "/" + media.size() + " · " + n.name + (n.isVideo() ? " · ▶ TOCA ABAJO PARA VERLO" : ""));
        viewerText.setOnClickListener(n.isVideo() ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(n);
            }
        } : null);
        Bitmap small = cache.get(n.id + "@" + thumbPx());
        viewerImg.setImageBitmap(small);
        int px = Math.min(2048, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        loadThumb(viewerImg, n, px);
    }

    private void closeViewer() {
        root.removeView(viewer);
        viewer = null;
        viewerImg = null;
    }

    private int thumbPx() {
        return Math.min(480, dp(108) * 2);
    }

    // ---------- rejilla ----------

    private GradientDrawable cellBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(3), Brutal.INK);
        return g;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int pos) {
            return entries.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ExploreActivity.SquareFrame cell;
            ImageView img;
            TextView icon, label;
            if (convert instanceof ExploreActivity.SquareFrame) {
                cell = (ExploreActivity.SquareFrame) convert;
                img = (ImageView) cell.getChildAt(0);
                icon = (TextView) cell.getChildAt(1);
                label = (TextView) cell.getChildAt(2);
            } else {
                Context c = BackupActivity.this;
                cell = new ExploreActivity.SquareFrame(c);
                img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
                ip.setMargins(dp(3), dp(3), dp(3), dp(3));
                cell.addView(img, ip);
                icon = new TextView(c);
                icon.setGravity(Gravity.CENTER);
                icon.setPadding(dp(8), dp(8), dp(8), dp(22));
                icon.setTypeface(Brutal.display(c));
                cell.addView(icon, new FrameLayout.LayoutParams(-1, -1));
                label = Brutal.mono(c, 10, Brutal.PAPER);
                label.setBackgroundColor(Brutal.BLACK);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                label.setPadding(dp(6), dp(3), dp(6), dp(3));
                cell.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            }
            AmazonDrive.Node n = entries.get(pos);
            img.setImageDrawable(null);
            img.setTag(null);
            if (n.folder) {
                cell.setBackground(cellBg(Brutal.PURPLE));
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                icon.setTextColor(Brutal.PAPER);
                icon.setText(n.name.toUpperCase(Locale.getDefault()));
                label.setText("▸ CARPETA");
                return cell;
            }
            cell.setBackground(cellBg(Brutal.WHITE));
            label.setText(n.name);
            icon.setTextColor(Brutal.INK);
            if (n.isImage() || n.isVideo()) {
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                icon.setText(n.isVideo() ? "▶" : "");
                if (n.isVideo()) icon.setTextColor(Brutal.ORANGE);
                loadThumb(img, n, thumbPx());
            } else {
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                int dot = n.name.lastIndexOf('.');
                icon.setText(dot > 0 ? n.name.substring(dot + 1).toUpperCase(Locale.getDefault()) : "?");
            }
            return cell;
        }
    }

    private int dp(float v) {
        return Brutal.dp(this, v);
    }

    /** ¿El box está borrando de Amazon lo que se copió de esta carpeta? */
    static boolean removing(JSONObject status, String rel) {
        JSONArray a = status == null ? null : status.optJSONArray("removing");
        for (int i = 0; a != null && i < a.length(); i++) {
            String p = a.optString(i);
            if (rel.equals(p) || rel.startsWith(p + "/")) return true;
        }
        return false;
    }

    /** Lista de carpetas con copia que da el box (para el diálogo de «Mis fotos»). */
    static String describe(JSONObject status, String rel) {
        JSONArray a = status == null ? null : status.optJSONArray("folders");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject f = a.optJSONObject(i);
            if (f == null) continue;
            String p = f.optString("path");
            if (!rel.equals(p) && !rel.startsWith(p + "/")) continue;
            if (!f.has("files")) return "Preparando la copia…";
            if (f.optBoolean("missing")) return "La carpeta ya no está en el disco.";
            long files = f.optLong("files"), done = f.optLong("done");
            String s = done + " de " + files + " archivos ya en Amazon";
            if (!rel.equals(p)) s += " (con la carpeta «" + p + "»)";
            return s;
        }
        return null;
    }
}
