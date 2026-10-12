package es.subelo.movil;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Lógica común de subida (manual y automática). */
final class Uploader {
    /** Evita que la subida automática y la manual suban lo mismo a la vez. */
    static final ReentrantLock SCAN_LOCK = new ReentrantLock();

    static final class Item {
        Uri uri;
        String name;
        long size = -1;
        long mtime;
        long mediaId = -1;
        long dateAdded = -1;
    }

    enum Result { UPLOADED, SKIPPED }

    private Uploader() {}

    // ---------- datos de cada foto ----------

    static Item describe(ContentResolver cr, Uri uri) {
        Item it = new Item();
        it.uri = uri;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                it.name = c.getString(0);
                if (!c.isNull(1)) it.size = c.getLong(1);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        it.mtime = queryLong(cr, uri, MediaStore.MediaColumns.DATE_TAKEN, 1);
        if (it.mtime <= 0) it.mtime = queryLong(cr, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1);
        if (it.mtime <= 0) it.mtime = queryLong(cr, uri, MediaStore.MediaColumns.DATE_MODIFIED, 1000);
        if (it.name == null || it.name.isEmpty()) {
            String last = uri.getLastPathSegment();
            it.name = "foto_" + (last == null ? System.currentTimeMillis() : last.replaceAll("[^A-Za-z0-9_.-]", "_"));
            String type = cr.getType(uri);
            if (type != null && type.startsWith("image/") && !it.name.contains(".")) it.name += ".jpg";
            if (type != null && type.startsWith("video/") && !it.name.contains(".")) it.name += ".mp4";
        }
        return it;
    }

    private static long queryLong(ContentResolver cr, Uri uri, String col, long mult) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{col}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0) * mult;
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /** Carpeta final en el disco: la elegida, más año/mes si está activado «Organizar por año y mes». */
    static String destFor(Context c, long mtime) {
        String base = Prefs.dest(c);
        if (!Prefs.organize(c)) return base;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mtime > 0 ? mtime : System.currentTimeMillis());
        String sub = String.format(Locale.US, "%04d/%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
        return base.isEmpty() ? sub : base + "/" + sub;
    }

    // ---------- subida ----------

    static Result upload(Context ctx, BoxClient bc, Item it, String dir, BoxClient.Progress p,
                         AtomicBoolean cancel) throws IOException {
        long existing = bc.exists(dir, it.name);
        if (existing >= 0 && (it.size < 0 || existing == it.size)) return Result.SKIPPED;
        InputStream in = openOriginal(ctx, it.uri);
        try {
            return bc.upload(dir, it.name, it.size, it.mtime, in, p, cancel).optBoolean("duplicate")
                    ? Result.SKIPPED : Result.UPLOADED;
        } finally {
            in.close();
        }
    }

    /**
     * Abre la foto original. Con el permiso ACCESS_MEDIA_LOCATION pedimos el original para no perder
     * la ubicación GPS de la foto (Android la quita si no).
     */
    static InputStream openOriginal(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29
                && ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Uri media = toMediaStoreUri(uri);
            if (media != null) {
                try {
                    InputStream in = cr.openInputStream(MediaStore.setRequireOriginal(media));
                    if (in != null) return in;
                } catch (Exception ignored) {
                    // sin permiso de lectura de fotos: usamos la URI tal cual
                }
            }
        }
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new FileNotFoundException("No se puede leer " + uri);
        return in;
    }

    private static Uri toMediaStoreUri(Uri uri) {
        if (MediaStore.AUTHORITY.equals(uri.getAuthority())) return uri;
        if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
            try {
                String[] parts = DocumentsContract.getDocumentId(uri).split(":");
                long id = Long.parseLong(parts[1]);
                if ("image".equals(parts[0])) return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                if ("video".equals(parts[0])) return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---------- subida automática: fotos nuevas en la galería ----------

    static boolean hasMediaPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission(
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED);
        }
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Fotos (y vídeos) añadidos a la galería después de la marca guardada, de la más antigua a la más nueva. */
    static List<Item> pending(Context ctx, int limit) {
        List<Item> out = new ArrayList<Item>();
        if (!hasMediaPermission(ctx)) return out;
        ContentResolver cr = ctx.getContentResolver();
        long wmDate = Prefs.wmDate(ctx), wmId = Prefs.wmId(ctx);
        StringBuilder sel = new StringBuilder();
        sel.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=")
                .append(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
        if (Prefs.videos(ctx)) {
            sel.append(" OR ").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=")
                    .append(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
        }
        sel.append(") AND (date_added > ? OR (date_added = ? AND _id > ?))");
        List<String> args = new ArrayList<String>();
        args.add(String.valueOf(wmDate));
        args.add(String.valueOf(wmDate));
        args.add(String.valueOf(wmId));
        // Lo descargado desde el box (carpetas Subelo) no se vuelve a subir.
        if (Build.VERSION.SDK_INT >= 29) {
            sel.append(" AND ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" NOT LIKE ?");
            args.add("%/" + Downloader.FOLDER + "/%");
        } else {
            sel.append(" AND _data NOT LIKE ?");
            args.add("%/" + Downloader.FOLDER + "/%");
        }
        if (Prefs.onlyCamera(ctx)) {
            if (Build.VERSION.SDK_INT >= 29) {
                sel.append(" AND ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" LIKE ?")
                        .append(" AND ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" NOT LIKE ?");
                args.add("DCIM/%");
                args.add("DCIM/Screenshots%");
            } else {
                sel.append(" AND _data LIKE ? AND _data NOT LIKE ?");
                args.add("%/DCIM/%");
                args.add("%/DCIM/Screenshots/%");
            }
        }
        String[] proj = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, "datetaken"};
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Files.getContentUri("external"), proj, sel.toString(),
                    args.toArray(new String[0]), "date_added ASC, _id ASC");
            while (c != null && c.moveToNext() && out.size() < limit) {
                Item it = new Item();
                it.mediaId = c.getLong(0);
                boolean video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                it.uri = ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.mediaId);
                it.name = c.getString(2);
                it.size = c.isNull(3) ? -1 : c.getLong(3);
                it.dateAdded = c.getLong(4);
                long taken = c.isNull(6) ? 0 : c.getLong(6);
                it.mtime = taken > 0 ? taken : c.getLong(5) * 1000;
                if (it.name == null) it.name = (video ? "video_" : "foto_") + it.mediaId + (video ? ".mp4" : ".jpg");
                out.add(it);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Marca para «solo fotos nuevas a partir de ahora»: la última foto que ya hay en el móvil. */
    static void markEverythingAsDone(Context ctx) {
        long date = System.currentTimeMillis() / 1000, id = 0;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    new String[]{MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATE_ADDED},
                    null, null, "date_added DESC, _id DESC");
            if (c != null && c.moveToFirst()) {
                id = c.getLong(0);
                date = c.getLong(1);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        Prefs.setWatermark(ctx, date, id);
    }

    static final class Stats {
        int uploaded, skipped, failed;
        String error;
        boolean more;
    }

    interface Listener {
        void onItem(Item it, int done, Stats s);
        void onBytes(Item it, long sent, long total);
    }

    /**
     * Sube todo lo pendiente de la galería. Se para si se cancela, si se pasa el tiempo límite (more=true)
     * o si el box deja de responder (error != null; se reintentará más tarde sin perder nada).
     */
    static Stats runPending(Context ctx, BoxClient bc, AtomicBoolean cancel, long deadline, final Listener l) {
        Stats s = new Stats();
        int done = 0;
        while (true) {
            List<Item> batch = pending(ctx, 50);
            if (batch.isEmpty()) return s;
            for (final Item it : batch) {
                if ((cancel != null && cancel.get()) || System.currentTimeMillis() > deadline) {
                    s.more = true;
                    return s;
                }
                if (l != null) l.onItem(it, done, s);
                try {
                    Result r = upload(ctx, bc, it, destFor(ctx, it.mtime), l == null ? null : new BoxClient.Progress() {
                        @Override
                        public void onProgress(long sent, long total) {
                            l.onBytes(it, sent, total);
                        }
                    }, cancel);
                    if (r == Result.UPLOADED) s.uploaded++;
                    else s.skipped++;
                } catch (BoxClient.BoxException e) {
                    if (e.code == 401 || e.code >= 500) {
                        s.error = e.getMessage();
                        return s;
                    }
                    s.failed++;
                } catch (FileNotFoundException e) {
                    s.failed++;
                } catch (SecurityException e) {
                    s.failed++;
                } catch (IOException e) {
                    s.error = "Se cortó la conexión con el box: " + e.getMessage();
                    return s;
                }
                done++;
                Prefs.setWatermark(ctx, it.dateAdded, it.mediaId);
            }
        }
    }
}
