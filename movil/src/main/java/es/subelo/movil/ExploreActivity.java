package es.subelo.movil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mis fotos en el disco del box, por carpetas (cada carpeta con su portada y cuántas fotos tiene):
 * verlas, subir a una carpeta y descargar al móvil.
 */
public class ExploreActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_WRITE = 2;
    private static final int REQ_MOVE = 3;

    private static LruCache<String, Bitmap> thumbCache;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final List<Object> entries = new ArrayList<Object>(); // String = carpeta, RemoteFile = fichero
    private final List<BoxClient.RemoteFile> files = new ArrayList<BoxClient.RemoteFile>();
    private final Set<String> selected = new HashSet<String>();
    private final Map<String, BoxClient.Album> albums = new HashMap<String, BoxClient.Album>();
    private boolean selecting;
    private String path = "";
    private BoxClient bc;
    private TextView pathText;
    private Button downloadButton, uploadButton, folderButton, moveButton, deleteButton, renameButton, shareButton, echoButton;
    private LinearLayout echoRow;
    private BoxClient.Listing current = new BoxClient.Listing();
    private Adapter adapter;
    private List<BoxClient.RemoteFile> pendingDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        if (thumbCache == null) {
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
            thumbCache = new LruCache<String, Bitmap>(kb) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount() / 1024;
                }
            };
        }
        if (savedInstanceState != null) path = savedInstanceState.getString("path", "");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Brutal.CREAM);
        pathText = Brutal.mono(this, 13, Brutal.PAPER);
        pathText.setBackgroundColor(Brutal.BLACK);
        pathText.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.addView(pathText);

        GridView grid = new GridView(this);
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
                onTap(pos);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                Object e = entries.get(pos);
                if ("virtual".equals(current.access) || BoxClient.SHARED_WITH_ME.equals(e) && path.isEmpty()) {
                    Toast.makeText(ExploreActivity.this, "Aquí está lo que te han compartido", Toast.LENGTH_SHORT).show();
                    return true;
                }
                selecting = true;
                toggle(key(entries.get(pos)));
                return true;
            }
        });
        col.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        echoRow = new LinearLayout(this);
        echoRow.setBackgroundColor(Brutal.BLACK);
        echoRow.setPadding(dp(10), dp(6), dp(4), 0);
        echoButton = Brutal.button(this, "📺 Enviar al Echo", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Echo.send(ExploreActivity.this, selectedFiles());
            }
        });
        echoRow.addView(echoButton, Brutal.half(this));
        col.addView(echoRow);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Brutal.BLACK);
        bar.setPadding(dp(10), dp(6), dp(4), dp(6));
        uploadButton = Brutal.button(this, "⬆ Subir", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickToUpload();
            }
        });
        bar.addView(uploadButton, Brutal.half(this));
        downloadButton = Brutal.button(this, "⬇ Todo", Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadClicked();
            }
        });
        bar.addView(downloadButton, Brutal.half(this));
        folderButton = Brutal.button(this, "+ Carpeta", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        bar.addView(folderButton, Brutal.half(this));
        shareButton = Brutal.button(this, "👥", Brutal.LILAC, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareDialog();
            }
        });
        bar.addView(shareButton, Brutal.spaced(this, dp(56), LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        moveButton = Brutal.button(this, "↦ Mover", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(ExploreActivity.this, FolderActivity.class)
                        .putExtra(FolderActivity.EXTRA_PICK, path), REQ_MOVE);
            }
        });
        bar.addView(moveButton, Brutal.half(this));
        renameButton = Brutal.button(this, "✎", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameSelected();
            }
        });
        bar.addView(renameButton, Brutal.spaced(this, dp(56), LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        deleteButton = Brutal.button(this, "🗑 Borrar", 0xFFE5322D, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        bar.addView(deleteButton, Brutal.half(this));
        col.addView(bar);
        setContentView(col);
        refreshSelection();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("path", path);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            selecting = false;
            selected.clear();
            refreshSelection();
        } else if (!path.isEmpty()) {
            int i = path.lastIndexOf('/');
            go(i > 0 ? path.substring(0, i) : "");
        } else {
            super.onBackPressed();
        }
    }

    private void go(String p) {
        path = p;
        selecting = false;
        selected.clear();
        load();
    }

    private void load() {
        pathText.setText("DISCO:/" + Prefs.user(this) + "/" + path + "\nCARGANDO…");
        final String p = path;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bc == null) bc = BoxClient.connect(ExploreActivity.this);
                    final BoxClient.Listing l = bc.list(p);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!p.equals(path)) return;
                            entries.clear();
                            files.clear();
                            albums.clear();
                            current = l;
                            entries.addAll(l.folders);
                            entries.addAll(l.files);
                            files.addAll(l.files);
                            adapter.notifyDataSetChanged();
                            refreshSelection();
                            if (!l.folders.isEmpty()) loadAlbums(p);
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (e instanceof BoxClient.BoxException && ((BoxClient.BoxException) e).code == 404 && !path.isEmpty()) {
                                go("");
                                return;
                            }
                            pathText.setText("No se pudo leer el disco: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** Portadas y número de fotos de las carpetas (llega después del listado para no hacerlo esperar). */
    private void loadAlbums(final String p) {
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<BoxClient.Album> list;
                try {
                    list = bc.albums(p);
                } catch (IOException e) {
                    return; // box antiguo o sin conexión: las carpetas se quedan sin portada
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!p.equals(path)) return;
                        for (BoxClient.Album a : list) albums.put(a.name, a);
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private void refreshSelection() {
        String where = "DISCO:/" + Prefs.user(this) + "/" + path;
        if ("virtual".equals(current.access)) {
            where = "👥 LO QUE TE HAN COMPARTIDO";
        } else if (!current.own()) {
            where += "\nDE " + current.owner + ("subir".equals(current.access) ? " · PUEDES VER Y AÑADIR" : " · SOLO VER");
        } else if (!current.sharedWith.isEmpty()) {
            where += "\n👥 COMPARTIDA CON " + android.text.TextUtils.join(", ", current.sharedWith.keySet());
        }
        if (selecting) {
            pathText.setText(where + "\n■ " + selected.size() + " ELEGIDOS · TOCA PARA AÑADIR · ATRÁS PARA SALIR");
            int nFiles = selectedFiles().size(), nPhotos = 0;
            for (BoxClient.RemoteFile f : selectedFiles()) if (Downloader.isImage(f.name)) nPhotos++;
            downloadButton.setText("⬇ " + nFiles);
            downloadButton.setVisibility(nFiles > 0 ? View.VISIBLE : View.GONE);
            echoButton.setText(("📺 Enviar " + (nPhotos == 1 ? "1 foto" : nPhotos + " fotos") + " al Echo").toUpperCase(java.util.Locale.getDefault()));
            echoRow.setVisibility(nPhotos > 0 ? View.VISIBLE : View.GONE);
        } else {
            pathText.setText(where + "\n" + files.size() + " ARCHIVOS"
                    + ("virtual".equals(current.access) ? ""
                    : current.own() ? " · MANTÉN PULSADO PARA BORRAR, MOVER O ENVIAR AL ECHO"
                    : " · MANTÉN PULSADO PARA DESCARGAR O ENVIAR AL ECHO"));
            downloadButton.setText("⬇ TODO");
            downloadButton.setVisibility(View.VISIBLE);
            echoRow.setVisibility(View.GONE);
        }
        boolean add = !selecting && current.canAdd();
        uploadButton.setVisibility(add ? View.VISIBLE : View.GONE);
        folderButton.setVisibility(add ? View.VISIBLE : View.GONE);
        shareButton.setVisibility(!selecting && current.own() && !path.isEmpty() ? View.VISIBLE : View.GONE);
        boolean edit = selecting && current.own();
        moveButton.setVisibility(edit ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(edit ? View.VISIBLE : View.GONE);
        renameButton.setVisibility(edit && selected.size() == 1 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void toggle(String name) {
        if (!selected.remove(name)) selected.add(name);
        if (selected.isEmpty()) selecting = false;
        refreshSelection();
    }

    /** Clave de selección: el nombre del fichero, o «nombre/» si es carpeta. */
    private static String key(Object e) {
        return e instanceof String ? e + "/" : ((BoxClient.RemoteFile) e).name;
    }

    private void onTap(int pos) {
        Object e = entries.get(pos);
        if (e instanceof String) {
            if (selecting) toggle(key(e));
            else go(path.isEmpty() ? (String) e : path + "/" + e);
            return;
        }
        BoxClient.RemoteFile f = (BoxClient.RemoteFile) e;
        if (selecting) {
            toggle(f.name);
            return;
        }
        ViewerActivity.open(this, files, files.indexOf(f));
    }

    // ---------- subir aquí ----------

    private void pickToUpload() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Elige las fotos"), REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay galería", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MOVE && resultCode == RESULT_OK && data != null) {
            moveSelected(data.getStringExtra("path"));
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) if (cd.getItemAt(i).getUri() != null) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        UploadService.upload(this, uris, path);
        Toast.makeText(this, "Subiendo " + uris.size() + " a /" + path + ". Mira la notificación.", Toast.LENGTH_LONG).show();
    }

    // ---------- descargar al móvil ----------

    private void downloadClicked() {
        final List<BoxClient.RemoteFile> list = new ArrayList<BoxClient.RemoteFile>();
        for (BoxClient.RemoteFile f : files) if (!selecting || selected.contains(f.name)) list.add(f);
        if (selecting && list.isEmpty()) {
            Toast.makeText(this, "Para descargar, elige fotos (no carpetas)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "No hay archivos en esta carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        long bytes = 0;
        for (BoxClient.RemoteFile f : list) bytes += Math.max(0, f.size);
        new AlertDialog.Builder(this)
                .setMessage("¿Descargar " + list.size() + (list.size() == 1 ? " archivo" : " archivos")
                        + " (" + String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1e6)
                        + ") al móvil?\n\nSe guardan en la galería, en la carpeta «Subelo».")
                .setPositiveButton("Descargar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startDownload(list);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void startDownload(List<BoxClient.RemoteFile> list) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = list;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        UploadService.download(this, list);
        selecting = false;
        selected.clear();
        refreshSelection();
        Toast.makeText(this, "Descargando… mira la notificación", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (Echo.onPermission(this, requestCode, results)) return;
        if (requestCode == REQ_WRITE && pendingDownload != null) {
            List<BoxClient.RemoteFile> l = pendingDownload;
            pendingDownload = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startDownload(l);
            else Toast.makeText(this, "Sin permiso no puedo guardar en el móvil", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- borrar, mover, renombrar ----------

    private List<BoxClient.RemoteFile> selectedFiles() {
        List<BoxClient.RemoteFile> l = new ArrayList<BoxClient.RemoteFile>();
        for (BoxClient.RemoteFile f : files) if (selected.contains(f.name)) l.add(f);
        return l;
    }

    /** Nombres reales de lo elegido (sin la «/» de las carpetas). */
    private List<String> selectedNames() {
        List<String> l = new ArrayList<String>();
        for (Object e : entries) if (selected.contains(key(e))) l.add(e instanceof String ? (String) e : ((BoxClient.RemoteFile) e).name);
        return l;
    }

    static String describe(int nFiles, int nDirs) {
        StringBuilder sb = new StringBuilder();
        if (nFiles > 0) sb.append(nFiles).append(nFiles == 1 ? " archivo" : " archivos");
        if (nDirs > 0) {
            if (sb.length() > 0) sb.append(" y ");
            sb.append(nDirs).append(nDirs == 1 ? " carpeta con todo lo que tiene" : " carpetas con todo lo que tienen");
        }
        return sb.toString();
    }

    private void confirmDelete() {
        final List<String> names = selectedNames();
        if (names.isEmpty()) return;
        int nFiles = selectedFiles().size();
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar del disco?")
                .setMessage("Vas a borrar " + describe(nFiles, names.size() - nFiles) + ".\n\nNo se puede deshacer.")
                .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        run("Borrado", new Op() {
                            @Override
                            public void go(BoxClient c) throws IOException {
                                c.delete(path, names);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void moveSelected(final String to) {
        final List<String> names = selectedNames();
        if (names.isEmpty() || to == null) return;
        if (to.equals(path)) {
            Toast.makeText(this, "Ya está en esa carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        int nFiles = selectedFiles().size();
        new AlertDialog.Builder(this)
                .setTitle("¿Mover?")
                .setMessage("Vas a mover " + describe(nFiles, names.size() - nFiles) + " a DISCO:/"
                        + Prefs.user(this) + "/" + to)
                .setPositiveButton("Mover", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        run("Movido", new Op() {
                            @Override
                            public void go(BoxClient c) throws IOException {
                                c.move(path, names, to);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void renameSelected() {
        List<String> names = selectedNames();
        if (names.size() != 1) return;
        final String old = names.get(0);
        final EditText et = new EditText(this);
        et.setText(old);
        int dot = old.lastIndexOf('.');
        et.setSelection(0, dot > 0 && selectedFiles().size() == 1 ? dot : old.length());
        new AlertDialog.Builder(this)
                .setTitle("Cambiar nombre")
                .setView(et)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String nn = et.getText().toString().trim();
                        if (nn.isEmpty() || nn.equals(old)) return;
                        run("Nombre cambiado", new Op() {
                            @Override
                            public void go(BoxClient c) throws IOException {
                                c.rename(path, old, nn);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    interface Op {
        void go(BoxClient c) throws IOException;
    }

    /** Hace la operación en segundo plano, sale del modo selección y recarga. */
    private void run(final String done, final Op op) {
        pathText.setText("DISCO:/" + Prefs.user(this) + "/" + path + "\nUN MOMENTO…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (bc == null) bc = BoxClient.connect(ExploreActivity.this);
                    op.go(bc);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err == null ? done : err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ExploreActivity.this, msg, Toast.LENGTH_LONG).show();
                        selecting = false;
                        selected.clear();
                        load();
                    }
                });
            }
        });
    }

    // ---------- compartir ----------

    private void shareDialog() {
        final String folder = path;
        final String name = folder.substring(folder.lastIndexOf('/') + 1);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<String> others;
                try {
                    if (bc == null) bc = BoxClient.connect(ExploreActivity.this);
                    others = bc.others();
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ExploreActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (others.isEmpty() || !folder.equals(path)) return;
                        final String who = others.get(0); // solo hay dos usuarios: el otro
                        final String now = current.sharedWith.get(who);
                        List<String> opts = new ArrayList<String>();
                        opts.add(("ver".equals(now) ? "✓ " : "") + who + " puede verla y descargar");
                        opts.add(("subir".equals(now) ? "✓ " : "") + who + " puede verla y añadir fotos");
                        if (now != null) opts.add("✕ Dejar de compartir");
                        new AlertDialog.Builder(ExploreActivity.this)
                                .setTitle("Compartir «" + name + "» con " + who)
                                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, final int which) {
                                        ExploreActivity.this.run(which == 2 ? "Ya no la compartes con " + who : "Compartida con " + who, new Op() {
                                            @Override
                                            public void go(BoxClient c) throws IOException {
                                                if (which == 2) c.unshare(folder, who);
                                                else c.share(folder, who, which == 0 ? "ver" : "subir");
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                });
            }
        });
    }

    // ---------- nueva carpeta ----------

    private void newFolder() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("Nueva carpeta")
                .setView(et)
                .setPositiveButton("Crear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim().replace("/", "-");
                        if (name.isEmpty()) return;
                        final String np = path.isEmpty() ? name : path + "/" + name;
                        bg.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (bc == null) bc = BoxClient.connect(ExploreActivity.this);
                                    bc.mkdir(np);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            go(np);
                                        }
                                    });
                                } catch (final IOException e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(ExploreActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
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

    // ---------- cuadrícula ----------

    /** Celda cuadrada. */
    static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int i) {
            return entries.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            SquareFrame cell;
            ImageView img;
            TextView icon, label, check;
            if (convert instanceof SquareFrame) {
                cell = (SquareFrame) convert;
                img = (ImageView) cell.getChildAt(0);
                icon = (TextView) cell.getChildAt(1);
                label = (TextView) cell.getChildAt(2);
                check = (TextView) cell.getChildAt(3);
            } else {
                Context c = ExploreActivity.this;
                cell = new SquareFrame(c);
                img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
                ip.setMargins(dp(3), dp(3), dp(3), dp(3));
                cell.addView(img, ip);
                icon = new TextView(c);
                icon.setGravity(Gravity.CENTER);
                icon.setPadding(dp(8), dp(8), dp(8), dp(22));
                cell.addView(icon, new FrameLayout.LayoutParams(-1, -1));
                label = Brutal.mono(c, 10, Brutal.PAPER);
                label.setBackgroundColor(Brutal.BLACK);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                label.setPadding(dp(6), dp(3), dp(6), dp(3));
                cell.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                check = Brutal.mono(c, 14, Brutal.BLACK);
                check.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.END);
                cp.setMargins(0, dp(6), dp(6), 0);
                cell.addView(check, cp);
            }
            Object e = entries.get(pos);
            img.setImageDrawable(null);
            img.setTag(null);
            if (e instanceof String) {
                String name = (String) e;
                BoxClient.Album al = albums.get(name);
                cell.setBackground(cellBg(Brutal.PURPLE));
                icon.setTypeface(Brutal.display(ExploreActivity.this));
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                icon.setTextColor(Brutal.PAPER);
                label.setSingleLine(false);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                boolean fsel = selected.contains(name + "/");
                cell.setBackground(cellBg(fsel ? Brutal.ORANGE : Brutal.PURPLE));
                boolean special = path.isEmpty() && BoxClient.SHARED_WITH_ME.equals(name);
                if (special) cell.setBackground(cellBg(Brutal.ORANGE));
                if (al != null && al.cover != null) {
                    icon.setText("");
                    label.setText("▸ " + name.toUpperCase(java.util.Locale.getDefault()) + "\n" + count(al)
                            + (al.shared ? " · 👥" : ""));
                    loadThumb(img, al.cover);
                } else {
                    icon.setText(name.toUpperCase(java.util.Locale.getDefault()));
                    label.setText(al == null ? (special ? "▸ DE OTROS" : "▸ CARPETA")
                            : "▸ " + count(al) + (al.shared ? " · 👥" : ""));
                }
                check.setText(selecting ? (fsel ? "✓" : "") : "");
                check.setBackground(selecting ? cellBg(fsel ? Brutal.ORANGE : Brutal.WHITE) : null);
                cell.setAlpha(selecting && !fsel ? 0.55f : 1f);
                return cell;
            }
            BoxClient.RemoteFile f = (BoxClient.RemoteFile) e;
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            label.setText(f.name);
            boolean sel = selected.contains(f.name);
            cell.setBackground(cellBg(sel ? Brutal.ORANGE : Brutal.WHITE));
            check.setText(selecting ? (sel ? "✓" : "") : "");
            check.setBackground(selecting ? cellBg(sel ? Brutal.ORANGE : Brutal.WHITE) : null);
            cell.setAlpha(selecting && !sel ? 0.55f : 1f);
            boolean media = Downloader.isImage(f.name) || Downloader.isVideo(f.name);
            icon.setTypeface(Brutal.display(ExploreActivity.this));
            icon.setTextColor(Brutal.INK);
            if (media) {
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                icon.setText(Downloader.isVideo(f.name) ? "▶" : "");
                if (Downloader.isVideo(f.name)) icon.setTextColor(Brutal.ORANGE);
                loadThumb(img, f);
            } else {
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                int dot = f.name.lastIndexOf('.');
                icon.setText(dot > 0 ? f.name.substring(dot + 1).toUpperCase(java.util.Locale.getDefault()) : "?");
            }
            return cell;
        }
    }

    private static String count(BoxClient.Album a) {
        if (a.media > 0) return a.media + (a.media == 1 ? " FOTO" : " FOTOS")
                + (a.folders > 0 ? " · " + a.folders + " CARP." : "");
        if (a.folders > 0) return a.folders + (a.folders == 1 ? " CARPETA" : " CARPETAS");
        return "VACÍA";
    }

    private GradientDrawable cellBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(3), Brutal.INK);
        return g;
    }

    private void loadThumb(final ImageView img, final BoxClient.RemoteFile f) {
        final String key = f.dir + "/" + f.name + "|" + f.size;
        img.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            img.setImageBitmap(cached);
            return;
        }
        final BoxClient client = bc;
        if (client == null) return;
        thumbs.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(img.getTag())) return;
                Bitmap b = null;
                try {
                    byte[] data = client.thumb(f.dir, f.name);
                    if (data != null) b = BitmapFactory.decodeByteArray(data, 0, data.length);
                } catch (IOException ignored) {
                }
                if (b == null) return;
                thumbCache.put(key, b);
                final Bitmap bm = b;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(img.getTag())) img.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    // ---------- utilidades ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
