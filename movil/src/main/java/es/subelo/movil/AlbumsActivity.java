package es.subelo.movil;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Subir álbumes enteros de la galería del móvil (Cámara, WhatsApp, Descargas…). Cada álbum va a
 * una carpeta con su nombre dentro de la carpeta de destino del disco.
 */
public class AlbumsActivity extends Activity {
    private static final int REQ_PERMS = 1;

    /** Un álbum de la galería (en Android, «bucket»). */
    static final class Album {
        long id;
        String name;
        int photos, videos;
        long size;
        Uri cover;
    }

    private final ExecutorService bg = Executors.newFixedThreadPool(2);
    private final List<Album> albums = new ArrayList<Album>();
    private final Set<Long> selected = new HashSet<Long>();
    private final LruCache<Long, Bitmap> covers = new LruCache<Long, Bitmap>(60);
    private BaseAdapter adapter;
    private TextView info;
    private Button go;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Brutal.CREAM);
        col.setPadding(pad, pad, pad - dp(6), pad);

        TextView title = Brutal.title(this, "Subir álbumes", 24, Brutal.INK);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        info = Brutal.mono(this, 12, Brutal.PAPER);
        info.setBackground(Brutal.block(this, Brutal.BLACK, 4, 3));
        info.setPadding(dp(12), dp(10), dp(16), dp(14));
        col.addView(info, Brutal.full(this));

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Brutal.INK));
        list.setDividerHeight(dp(2));
        list.setBackground(Brutal.block(this, Brutal.WHITE, 6, 3));
        list.setPadding(dp(3), dp(3), dp(9), dp(9));
        list.setClipToPadding(true);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                long b = albums.get(pos).id;
                if (!selected.remove(b)) selected.add(b);
                refresh();
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        go = Brutal.button(this, "", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
        col.addView(go, Brutal.full(this));
        setContentView(col);

        if (Uploader.hasMediaPermission(this)) load();
        else askPermission();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    private void askPermission() {
        info.setText("NECESITO PERMISO PARA VER TU GALERÍA");
        List<String> perms = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 29) perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (Uploader.hasMediaPermission(this)) {
            load();
        } else {
            info.setText("SIN PERMISO NO PUEDO VER LOS ÁLBUMES");
            new AlertDialog.Builder(this)
                    .setMessage("Para subir álbumes enteros, Súbelo necesita ver tus fotos. "
                            + "Puedes darle permiso en Ajustes › Aplicaciones › Súbelo › Permisos › Fotos y vídeos › Permitir todo.")
                    .setPositiveButton("Vale", null)
                    .show();
        }
        refresh();
    }

    private void load() {
        info.setText("BUSCANDO ÁLBUMES…");
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<Album> found = query(AlbumsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        albums.clear();
                        albums.addAll(found);
                        refresh();
                    }
                });
            }
        });
    }

    /** Álbumes de la galería, del más reciente al más antiguo (sin lo descargado desde el box). */
    static List<Album> query(Context ctx) {
        Map<Long, Album> map = new LinkedHashMap<Long, Album>();
        String[] proj = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                "bucket_id", "bucket_display_name", MediaStore.MediaColumns.SIZE};
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"), proj,
                    mediaSelection(), null, "date_added DESC, _id DESC");
            while (c != null && c.moveToNext()) {
                if (c.isNull(2)) continue;
                long bucket = c.getLong(2);
                String name = c.getString(3);
                if (Downloader.FOLDER.equals(name)) continue;
                Album a = map.get(bucket);
                if (a == null) {
                    a = new Album();
                    a.id = bucket;
                    a.name = name == null || name.isEmpty() ? "Sin nombre" : name;
                    a.cover = uri(c.getLong(0), c.getInt(1));
                    map.put(bucket, a);
                }
                if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) a.videos++;
                else a.photos++;
                if (!c.isNull(4)) a.size += c.getLong(4);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new ArrayList<Album>(map.values());
    }

    /** Fotos y vídeos de un álbum, de la más antigua a la más nueva. */
    static ArrayList<Uri> items(Context ctx, long bucket) {
        ArrayList<Uri> out = new ArrayList<Uri>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    new String[]{MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE},
                    "(" + mediaSelection() + ") AND bucket_id=?", new String[]{String.valueOf(bucket)},
                    "date_added ASC, _id ASC");
            while (c != null && c.moveToNext()) out.add(uri(c.getLong(0), c.getInt(1)));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static String mediaSelection() {
        return MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                + " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
    }

    private static Uri uri(long id, int type) {
        return ContentUris.withAppendedId(type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    private void refresh() {
        int n = 0;
        long size = 0;
        for (Album a : albums) {
            if (!selected.contains(a.id)) continue;
            n += a.photos + a.videos;
            size += a.size;
        }
        if (Uploader.hasMediaPermission(this)) {
            info.setText(albums.isEmpty() ? "NO HAY ÁLBUMES CON FOTOS"
                    : albums.size() + " ÁLBUMES · TOCA PARA ELEGIR\nVAN A DISCO:/" + Prefs.user(this) + "/"
                    + destBase(this) + (destBase(this).isEmpty() ? "" : "/") + "<ÁLBUM>/");
        }
        go.setEnabled(n > 0);
        go.setAlpha(n > 0 ? 1f : 0.5f);
        go.setText(selected.isEmpty() ? "Elige uno o varios álbumes"
                : "⬆ Subir " + selected.size() + (selected.size() == 1 ? " álbum" : " álbumes")
                + " · " + n + " · " + gb(size));
        adapter.notifyDataSetChanged();
    }

    private void confirm() {
        final List<Album> chosen = new ArrayList<Album>();
        int n = 0;
        for (Album a : albums) {
            if (selected.contains(a.id)) {
                chosen.add(a);
                n += a.photos + a.videos;
            }
        }
        if (chosen.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Subir " + n + " archivos")
                .setMessage("Cada álbum irá a su propia carpeta en el disco. Lo que ya esté subido se salta, "
                        + "así que puedes repetirlo más adelante para subir solo lo nuevo.")
                .setPositiveButton("Subir", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        for (Album a : chosen) UploadService.uploadAlbum(AlbumsActivity.this, a.id, folderFor(AlbumsActivity.this, a.name));
                        android.widget.Toast.makeText(AlbumsActivity.this, "Subiendo… mira la notificación",
                                android.widget.Toast.LENGTH_LONG).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static String destBase(Context c) {
        return Prefs.dest(c);
    }

    /** Carpeta del disco para un álbum: «<destino>/<nombre del álbum>». */
    static String folderFor(Context c, String album) {
        String name = album.replace('/', '-').replace('\\', '-').trim();
        if (name.isEmpty() || name.startsWith(".")) name = "Album " + name;
        String base = destBase(c);
        return base.isEmpty() ? name : base + "/" + name;
    }

    private static String gb(long bytes) {
        return bytes >= 1e9 ? String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
                : String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6);
    }

    // ---------- lista ----------

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return albums.size();
        }

        @Override
        public Object getItem(int i) {
            return albums.get(i);
        }

        @Override
        public long getItemId(int i) {
            return albums.get(i).id;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView img;
            TextView name, meta, check;
            Context c = AlbumsActivity.this;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                img = (ImageView) row.getChildAt(0);
                LinearLayout txt = (LinearLayout) row.getChildAt(1);
                name = (TextView) txt.getChildAt(0);
                meta = (TextView) txt.getChildAt(1);
                check = (TextView) row.getChildAt(2);
            } else {
                row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Brutal.block(c, Brutal.CREAM, 0, 3));
                img.setPadding(dp(3), dp(3), dp(3), dp(3));
                row.addView(img, new LinearLayout.LayoutParams(dp(64), dp(64)));
                LinearLayout txt = new LinearLayout(c);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(dp(12), 0, dp(8), 0);
                name = Brutal.text(c, 17, Brutal.INK);
                name.setTypeface(Brutal.bodyBold(c));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(name);
                meta = Brutal.mono(c, 11, Brutal.INK);
                txt.addView(meta);
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
                check = Brutal.mono(c, 18, Brutal.BLACK);
                check.setGravity(Gravity.CENTER);
                row.addView(check, new LinearLayout.LayoutParams(dp(34), dp(34)));
            }
            Album a = albums.get(pos);
            boolean sel = selected.contains(a.id);
            row.setBackgroundColor(sel ? Brutal.LILAC : Brutal.WHITE);
            name.setText(a.name);
            StringBuilder m = new StringBuilder();
            if (a.photos > 0) m.append(a.photos).append(a.photos == 1 ? " FOTO" : " FOTOS");
            if (a.videos > 0) m.append(m.length() > 0 ? " · " : "").append(a.videos).append(a.videos == 1 ? " VÍDEO" : " VÍDEOS");
            m.append(" · ").append(gb(a.size));
            meta.setText(m);
            check.setText(sel ? "✓" : "");
            check.setBackground(Brutal.block(c, sel ? Brutal.ORANGE : Brutal.WHITE, 0, 3));
            loadCover(img, a);
            return row;
        }
    }

    private void loadCover(final ImageView img, final Album a) {
        img.setTag(a.id);
        Bitmap b = covers.get(a.id);
        img.setImageBitmap(b);
        if (b != null) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                if (!Long.valueOf(a.id).equals(img.getTag())) return;
                Bitmap bm = null;
                try {
                    ContentResolver cr = getContentResolver();
                    if (Build.VERSION.SDK_INT >= 29) {
                        bm = cr.loadThumbnail(a.cover, new Size(dp(64) * 2, dp(64) * 2), null);
                    } else {
                        long id = ContentUris.parseId(a.cover);
                        bm = a.cover.toString().contains("/video/")
                                ? MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                                : MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    }
                } catch (Exception ignored) {
                }
                if (bm == null) return;
                covers.put(a.id, bm);
                final Bitmap fb = bm;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (Long.valueOf(a.id).equals(img.getTag())) img.setImageBitmap(fb);
                    }
                });
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
