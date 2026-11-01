package es.subelo.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ver las fotos en la tele con el mando. Cada uno entra con su contraseña y solo ve su carpeta,
 * igual que en el móvil. Carpetas y miniaturas en una cuadrícula; al abrir una foto, ◀ ▶ pasan,
 * OK pone o quita el pase de diapositivas y Atrás vuelve.
 */
public class GalleryActivity extends Activity {
    private static final long SLIDE_MS = 6000;

    /** Un elemento de la cuadrícula: carpeta o foto. */
    private static final class Item {
        final String name;
        final boolean folder;

        Item(String name, boolean folder) {
            this.name = name;
            this.folder = folder;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbsPool = Executors.newFixedThreadPool(2);
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Store store;
    private String user;
    /** Carpeta actual, relativa al disco: «Fer» o «Fer/Viajes/Roma». */
    private String rel;
    private final List<Item> items = new ArrayList<Item>();
    private final List<String> photos = new ArrayList<String>();
    private final List<Integer> positions = new ArrayList<Integer>(); // posición en la cuadrícula al entrar en cada carpeta

    private FrameLayout root;
    private TextView pathText, emptyText;
    private GridView grid;
    private Adapter adapter;

    private FrameLayout viewer;
    private ImageView photoView;
    private TextView photoText;
    private int photoIndex;
    private boolean slideshow;
    private int loadSeq;

    private final Runnable nextSlide = new Runnable() {
        @Override
        public void run() {
            if (!slideshow || viewer.getVisibility() != View.VISIBLE) return;
            show(photoIndex + 1 < photos.size() ? photoIndex + 1 : 0);
            ui.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        store = Config.store(this);
        if (store == null || !store.available()) {
            toastAndFinish("Primero elige el disco USB en la pantalla del servidor.");
            return;
        }
        buildUi();
        askUser();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        thumbsPool.shutdownNow();
        bg.shutdownNow();
    }

    // ---------- quién eres ----------

    private void askUser() {
        final String[] names = Users.NAMES;
        new AlertDialog.Builder(this)
                .setTitle("¿De quién son las fotos?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        askPassword(names[which]);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        finish();
                    }
                })
                .show();
    }

    private void askPassword(final String name) {
        if (!Users.hasPassword(this, name)) {
            new AlertDialog.Builder(this)
                    .setTitle(name + " aún no tiene contraseña")
                    .setMessage("Entra primero desde el móvil con la app Súbelo y pon una contraseña. Luego podrás ver aquí sus fotos.")
                    .setPositiveButton("Vale", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            askUser();
                        }
                    })
                    .setCancelable(false)
                    .show();
            return;
        }
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSingleLine(true);
        et.setHint("Contraseña de " + name);
        FrameLayout wrap = new FrameLayout(this);
        int p = Brutal.dp(this, 20);
        wrap.setPadding(p, Brutal.dp(this, 8), p, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("Contraseña de " + name)
                .setView(wrap)
                .setPositiveButton("Entrar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        check(name, et.getText().toString());
                    }
                })
                .setNegativeButton("Atrás", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        askUser();
                    }
                })
                .setCancelable(false)
                .show();
        et.requestFocus();
    }

    private void check(final String name, final String password) {
        pathText.setText("Comprobando…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Users.check(GalleryActivity.this, name, password);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!ok) {
                            new AlertDialog.Builder(GalleryActivity.this)
                                    .setTitle("Contraseña incorrecta")
                                    .setPositiveButton("Otra vez", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            askPassword(name);
                                        }
                                    })
                                    .setCancelable(false)
                                    .show();
                            return;
                        }
                        user = name;
                        open(name, 0);
                    }
                });
            }
        });
    }

    // ---------- pantalla ----------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Brutal.CREAM);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Brutal.dp(this, 24);
        col.setPadding(p, Brutal.dp(this, 16), p, 0);
        pathText = Brutal.title(this, "Fotos", 24, Brutal.INK);
        col.addView(pathText);
        TextView hint = Brutal.mono(this, 13, Brutal.INK);
        hint.setText("OK abre · ATRÁS vuelve · en una foto: ◀ ▶ pasan, OK pase de diapositivas");
        hint.setPadding(0, Brutal.dp(this, 4), 0, Brutal.dp(this, 12));
        col.addView(hint);

        FrameLayout gridBox = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setHorizontalSpacing(Brutal.dp(this, 12));
        grid.setVerticalSpacing(Brutal.dp(this, 12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setDrawSelectorOnTop(true);
        grid.setSelector(new ColorDrawable(0x66FF6B1A));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, Brutal.dp(this, 24));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Item it = items.get(pos);
                if (it.folder) {
                    positions.add(pos);
                    open(rel + "/" + it.name, 0);
                } else {
                    openViewer(photos.indexOf(it.name));
                }
            }
        });
        gridBox.addView(grid);
        emptyText = Brutal.text(this, 18, Brutal.INK);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        gridBox.addView(emptyText, new FrameLayout.LayoutParams(-1, -1));
        col.addView(gridBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col);

        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(photoView, new FrameLayout.LayoutParams(-1, -1));
        photoText = Brutal.mono(this, 14, Brutal.SNOW);
        photoText.setBackgroundColor(0x99000000);
        int q = Brutal.dp(this, 10);
        photoText.setPadding(q, q / 2, q, q / 2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(q * 2, q * 2, q * 2, q * 2);
        viewer.addView(photoText, lp);
        viewer.setVisibility(View.GONE);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    /** Abre una carpeta (relativa al disco) y pone el foco en la posición indicada. */
    private void open(final String path, final int focus) {
        rel = path;
        pathText.setText(path.replace("/", "  ›  "));
        emptyText.setVisibility(View.GONE);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<Item> found = new ArrayList<Item>();
                final List<String> pics = new ArrayList<String>();
                String err = null;
                try {
                    Object dir = store.dir(path, false);
                    if (dir != null) {
                        List<String> dirs = new ArrayList<String>(store.listDirs(dir));
                        Collections.sort(dirs, String.CASE_INSENSITIVE_ORDER);
                        for (String d : dirs) if (!Store.hidden(d)) found.add(new Item(d, true));
                        List<Store.FileInfo> files = store.listFiles(dir);
                        // las más nuevas primero, como en la galería del móvil
                        Collections.sort(files, new java.util.Comparator<Store.FileInfo>() {
                            @Override
                            public int compare(Store.FileInfo a, Store.FileInfo b) {
                                return a.mtime < b.mtime ? 1 : a.mtime > b.mtime ? -1 : 0;
                            }
                        });
                        for (Store.FileInfo f : files) {
                            if (Store.hidden(f.name) || !Thumbs.isImage(f.name)) continue;
                            found.add(new Item(f.name, false));
                            pics.add(f.name);
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || !path.equals(rel)) return;
                        items.clear();
                        items.addAll(found);
                        photos.clear();
                        photos.addAll(pics);
                        adapter.notifyDataSetChanged();
                        if (error != null) {
                            emptyText.setText("No se puede leer esta carpeta: " + error);
                            emptyText.setVisibility(View.VISIBLE);
                        } else if (items.isEmpty()) {
                            emptyText.setText(path.indexOf('/') < 0
                                    ? "Aún no hay fotos. Súbelas desde el móvil con la app Súbelo."
                                    : "Carpeta vacía.");
                            emptyText.setVisibility(View.VISIBLE);
                        }
                        grid.requestFocus();
                        grid.setSelection(Math.min(focus, Math.max(0, items.size() - 1)));
                    }
                });
            }
        });
    }

    // ---------- foto a pantalla completa ----------

    private void openViewer(int index) {
        if (index < 0 || photos.isEmpty()) return;
        viewer.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show(index);
    }

    private void closeViewer() {
        slideshow = false;
        ui.removeCallbacks(nextSlide);
        viewer.setVisibility(View.GONE);
        photoView.setImageBitmap(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pos = 0;
        for (int i = 0; i < items.size(); i++) if (!items.get(i).folder && items.get(i).name.equals(photos.get(photoIndex))) pos = i;
        grid.requestFocus();
        grid.setSelection(pos);
    }

    private void show(final int index) {
        photoIndex = index;
        final String name = photos.get(index);
        final String path = rel;
        final int seq = ++loadSeq;
        Bitmap small = thumbs.get(path + "/" + name);
        if (small != null) photoView.setImageBitmap(small); // mientras carga la grande
        photoText.setText((index + 1) + " / " + photos.size() + "   " + name + (slideshow ? "   ▶ pase" : ""));
        final int w = Math.max(1280, root.getWidth()), h = Math.max(720, root.getHeight());
        bg.execute(new Runnable() {
            @Override
            public void run() {
                if (seq != loadSeq) return; // ya ha pasado a otra
                Bitmap b = null;
                try {
                    Object dir = store.dir(path, false);
                    if (dir != null) b = Thumbs.full(store, dir, name, w, h);
                } catch (Throwable ignored) {
                }
                final Bitmap bmp = b;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq || viewer.getVisibility() != View.VISIBLE) {
                            if (bmp != null) bmp.recycle();
                            return;
                        }
                        if (bmp != null) photoView.setImageBitmap(bmp);
                        else photoText.setText(photoText.getText() + "   (no se puede abrir)");
                    }
                });
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (viewer == null || viewer.getVisibility() != View.VISIBLE) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                restartSlide();
                show(photoIndex + 1 < photos.size() ? photoIndex + 1 : 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                restartSlide();
                show(photoIndex > 0 ? photoIndex - 1 : photos.size() - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                slideshow = !slideshow;
                ui.removeCallbacks(nextSlide);
                if (slideshow) ui.postDelayed(nextSlide, SLIDE_MS);
                show(photoIndex);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                closeViewer();
                return true;
            default:
                return true;
        }
    }

    private void restartSlide() {
        if (!slideshow) return;
        ui.removeCallbacks(nextSlide);
        ui.postDelayed(nextSlide, SLIDE_MS);
    }

    @Override
    public void onBackPressed() {
        if (rel != null && user != null && !rel.equals(user)) {
            int pos = positions.isEmpty() ? 0 : positions.remove(positions.size() - 1);
            open(rel.substring(0, rel.lastIndexOf('/')), pos);
            return;
        }
        super.onBackPressed();
    }

    private void toastAndFinish(String msg) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Vale", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    // ---------- cuadrícula ----------

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout cell = (FrameLayout) convert;
            ImageView img;
            TextView label;
            if (cell == null) {
                cell = new FrameLayout(GalleryActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(-1, Brutal.dp(GalleryActivity.this, 130)));
                img = new ImageView(GalleryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                label = Brutal.mono(GalleryActivity.this, 13, Brutal.SNOW);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                int q = Brutal.dp(GalleryActivity.this, 6);
                label.setPadding(q, q / 2, q, q / 2);
                cell.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                cell.setTag(new Object[]{img, label});
            } else {
                Object[] t = (Object[]) cell.getTag();
                img = (ImageView) t[0];
                label = (TextView) t[1];
            }
            Item it = items.get(pos);
            img.setTag(it.name);
            if (it.folder) {
                img.setImageBitmap(null);
                img.setBackgroundColor(Brutal.PURPLE);
                label.setText("📁 " + it.name);
                label.setBackgroundColor(0x00000000);
                label.setTextSize(16);
                ((FrameLayout.LayoutParams) label.getLayoutParams()).gravity = Gravity.CENTER;
            } else {
                img.setBackgroundColor(0xFF2A2A2A);
                label.setText("");
                label.setBackgroundColor(0x00000000);
                ((FrameLayout.LayoutParams) label.getLayoutParams()).gravity = Gravity.BOTTOM;
                Bitmap b = thumbs.get(rel + "/" + it.name);
                img.setImageBitmap(b);
                if (b == null) loadThumb(img, rel, it.name);
            }
            label.requestLayout();
            return cell;
        }
    }

    private void loadThumb(final ImageView img, final String path, final String name) {
        thumbsPool.execute(new Runnable() {
            @Override
            public void run() {
                if (!name.equals(img.getTag()) || !path.equals(rel)) return; // ya no se ve
                Bitmap b = null;
                try {
                    Object dir = store.dir(path, false);
                    byte[] jpg = dir == null ? null : Thumbs.get(GalleryActivity.this, store, dir, path, name);
                    if (jpg != null) b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                } catch (Throwable ignored) {
                }
                final Bitmap bmp = b;
                if (bmp == null) return;
                thumbs.put(path + "/" + name, bmp);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (name.equals(img.getTag())) img.setImageBitmap(bmp);
                    }
                });
            }
        });
    }
}
