package es.subelo.box;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** Miniaturas de fotos y vídeos, guardadas en la caché del box para no recalcularlas. */
final class Thumbs {
    /** Lado corto de la miniatura (el largo, como mucho el triple): así las fotos altas no salen borrosas. */
    private static final int MAX = 400;
    private static final Semaphore SEM = new Semaphore(2);
    private static int writes;

    private Thumbs() {}

    static boolean isImage(String n) {
        String e = ext(n);
        return e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("gif") || e.equals("webp")
                || e.equals("heic") || e.equals("heif") || e.equals("bmp");
    }

    static boolean isVideo(String n) {
        String e = ext(n);
        return e.equals("mp4") || e.equals("mov") || e.equals("3gp") || e.equals("mkv") || e.equals("webm")
                || e.equals("m4v") || e.equals("avi");
    }

    static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.US);
    }

    /** JPEG pequeño o null si no se puede generar. */
    static byte[] get(Context ctx, Store st, Object dir, String rel, String name) throws IOException {
        if (!isImage(name) && !isVideo(name)) return null;
        Store.Readable r = st.read(dir, name);
        if (r == null) return null;
        String key;
        try {
            key = rel + "/" + name + "|" + r.size() + "|" + r.mtime() + "|2"; // «|2»: miniaturas más grandes (2.17)
        } finally {
            r.close();
        }
        File cacheDir = new File(ctx.getCacheDir(), "thumbs");
        File cache = new File(cacheDir, sha1(key) + ".jpg");
        if (cache.isFile()) return readFile(cache);
        try {
            SEM.acquire();
        } catch (InterruptedException e) {
            throw new IOException("Interrumpido");
        }
        try {
            Bitmap b = isVideo(name) ? videoFrame(st, dir, name) : image(st, dir, name);
            if (b == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            b.recycle();
            byte[] bytes = bos.toByteArray();
            if (cacheDir.isDirectory() || cacheDir.mkdirs()) {
                FileOutputStream fo = new FileOutputStream(cache);
                fo.write(bytes);
                fo.close();
                if (++writes % 200 == 0) trim(cacheDir);
            }
            return bytes;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            SEM.release();
        }
    }

    private static Bitmap image(Store st, Object dir, String name) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(st, dir, name, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= MAX && o.outHeight / (sample * 2) >= MAX) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = decode(st, dir, name, o2);
        if (b == null) return null;
        int rotation = 0;
        if (Build.VERSION.SDK_INT >= 24) {
            Store.Readable r = st.read(dir, name);
            if (r != null) {
                try {
                    InputStream in = r.open(0);
                    try {
                        int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                        if (or == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                        else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                        else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                    } finally {
                        in.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    r.close();
                }
            }
        }
        return scale(b, rotation);
    }

    private static Bitmap decode(Store st, Object dir, String name, BitmapFactory.Options o) throws IOException {
        Store.Readable r = st.read(dir, name);
        if (r == null) return null;
        try {
            InputStream in = new BufferedInputStream(r.open(0), 65536);
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } finally {
            r.close();
        }
    }

    private static Bitmap videoFrame(Store st, Object dir, String name) throws IOException {
        Store.Readable r = st.read(dir, name);
        if (r == null) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(r.fd());
            Bitmap b = mmr.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = mmr.getFrameAtTime();
            return b == null ? null : scale(b, 0);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            r.close();
        }
    }

    private static Bitmap scale(Bitmap b, int rotation) {
        int small = Math.min(b.getWidth(), b.getHeight()), big = Math.max(b.getWidth(), b.getHeight());
        float f = Math.min(1f, Math.min((float) MAX / small, (float) MAX * 3 / big));
        Matrix m = new Matrix();
        m.postScale(f, f);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }

    /** Si hay demasiadas miniaturas, borra las más antiguas. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < 10000) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length / 2; i++) files[i].delete();
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return b;
        } finally {
            in.close();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
