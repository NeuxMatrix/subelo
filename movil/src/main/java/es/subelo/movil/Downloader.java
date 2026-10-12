package es.subelo.movil;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guarda en el móvil ficheros del disco del box:
 * fotos en Imágenes/Subelo, vídeos en Películas/Subelo y el resto en Descargas/Subelo.
 * (La subida automática ignora estas carpetas para no volver a subirlas.)
 */
final class Downloader {
    static final String FOLDER = "Subelo";

    enum Result { SAVED, SKIPPED }

    private Downloader() {}

    static Result save(Context ctx, BoxClient bc, BoxClient.RemoteFile f, BoxClient.Progress p,
                       AtomicBoolean cancel) throws IOException {
        String mime = mime(f.name);
        String baseDir = mime.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                : mime.startsWith("video/") ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
        if (Build.VERSION.SDK_INT >= 29) return saveMediaStore(ctx, bc, f, mime, baseDir, p, cancel);
        return saveLegacy(ctx, bc, f, baseDir, p, cancel);
    }

    private static Result saveMediaStore(Context ctx, BoxClient bc, BoxClient.RemoteFile f, String mime,
                                         String baseDir, BoxClient.Progress p, AtomicBoolean cancel) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = mime.startsWith("image/") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : mime.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String relPath = baseDir + "/" + FOLDER + "/";
        if (existsMediaStore(cr, collection, relPath, f)) return Result.SKIPPED;

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (f.mtime > 0 && !mime.startsWith("application/")) v.put(MediaStore.MediaColumns.DATE_TAKEN, f.mtime);
        Uri item = cr.insert(collection, v);
        if (item == null) throw new IOException("No se pudo crear el fichero en el móvil");
        boolean ok = false;
        try {
            OutputStream out = cr.openOutputStream(item, "w");
            if (out == null) throw new IOException("No se pudo escribir en el móvil");
            try {
                bc.download(f.dir, f.name, out, p, cancel);
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            ok = true;
            return Result.SAVED;
        } finally {
            if (!ok) {
                try {
                    cr.delete(item, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean existsMediaStore(ContentResolver cr, Uri collection, String relPath, BoxClient.RemoteFile f) {
        Cursor c = null;
        try {
            c = cr.query(collection, new String[]{MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relPath, f.name}, null);
            while (c != null && c.moveToNext()) {
                if (f.size < 0 || c.getLong(0) == f.size) return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static Result saveLegacy(Context ctx, BoxClient bc, BoxClient.RemoteFile f, String baseDir,
                                     BoxClient.Progress p, AtomicBoolean cancel) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(baseDir), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se pudo crear la carpeta " + dir);
        File dest = new File(dir, f.name);
        if (dest.isFile() && (f.size < 0 || dest.length() == f.size)) return Result.SKIPPED;
        if (dest.exists()) {
            int dot = f.name.lastIndexOf('.');
            String base = dot > 0 ? f.name.substring(0, dot) : f.name, ext = dot > 0 ? f.name.substring(dot) : "";
            for (int i = 1; dest.exists(); i++) dest = new File(dir, base + " (" + i + ")" + ext);
        }
        File tmp = new File(dir, "." + dest.getName() + ".part");
        boolean ok = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bc.download(f.dir, f.name, out, p, cancel);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(dest)) throw new IOException("No se pudo guardar " + dest.getName());
            if (f.mtime > 0) dest.setLastModified(f.mtime);
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
        MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
        return Result.SAVED;
    }

    static String mime(String name) {
        int i = name.lastIndexOf('.');
        String e = i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.US);
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("png")) return "image/png";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("heic") || e.equals("heif")) return "image/heic";
        if (e.equals("bmp")) return "image/bmp";
        if (e.equals("mp4") || e.equals("m4v")) return "video/mp4";
        if (e.equals("mov")) return "video/quicktime";
        if (e.equals("3gp")) return "video/3gpp";
        if (e.equals("webm")) return "video/webm";
        if (e.equals("mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    static boolean isImage(String n) {
        return mime(n).startsWith("image/");
    }

    static boolean isVideo(String n) {
        return mime(n).startsWith("video/");
    }
}
