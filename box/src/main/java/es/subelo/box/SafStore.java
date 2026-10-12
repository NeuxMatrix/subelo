package es.subelo.box;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Guarda dentro de una carpeta elegida con el selector de Android (Storage Access Framework). */
final class SafStore extends Store {
    private static final long CACHE_MS = 30000;

    private final ContentResolver cr;
    private final Uri tree;
    private final String rootId;
    private final Map<String, Listing> cache = new HashMap<String, Listing>();

    private static final class Entry {
        String id;
        boolean dir;
        long size;
        long mtime;
    }

    private static final class Listing {
        long time;
        final Map<String, Entry> byName = new HashMap<String, Entry>();
    }

    SafStore(Context c, Uri tree) {
        this.cr = c.getContentResolver();
        this.tree = tree;
        this.rootId = DocumentsContract.getTreeDocumentId(tree);
    }

    private Uri docUri(String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    @Override
    boolean available() {
        Cursor c = null;
        try {
            c = cr.query(docUri(rootId), new String[]{Document.COLUMN_MIME_TYPE}, null, null, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private synchronized Listing list(String id, boolean fresh) throws IOException {
        Listing l = cache.get(id);
        long now = SystemClock.elapsedRealtime();
        if (l != null && !fresh && now - l.time < CACHE_MS) return l;
        l = new Listing();
        l.time = now;
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        Cursor c = null;
        try {
            c = cr.query(u, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED}, null, null, null);
            if (c == null) throw new IOException("No se pudo leer la carpeta del disco");
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getString(0);
                e.dir = Document.MIME_TYPE_DIR.equals(c.getString(2));
                e.size = c.isNull(3) ? 0 : c.getLong(3);
                e.mtime = c.isNull(4) ? 0 : c.getLong(4);
                l.byName.put(c.getString(1), e);
            }
        } catch (RuntimeException e) {
            throw new IOException("No se pudo leer la carpeta del disco: " + e.getMessage(), e);
        } finally {
            if (c != null) c.close();
        }
        if (cache.size() > 200) cache.clear();
        cache.put(id, l);
        return l;
    }

    private synchronized void invalidate(String id) {
        cache.remove(id);
    }

    @Override
    synchronized Object dir(String rel, boolean create) throws IOException {
        String id = rootId;
        if (rel.isEmpty()) return id;
        for (String seg : rel.split("/")) {
            Entry e = list(id, false).byName.get(seg);
            if (e == null) e = list(id, true).byName.get(seg);
            if (e == null) {
                if (!create) return null;
                Uri nu;
                try {
                    nu = DocumentsContract.createDocument(cr, docUri(id), Document.MIME_TYPE_DIR, seg);
                } catch (Exception ex) {
                    throw new IOException("No se pudo crear la carpeta " + seg, ex);
                }
                if (nu == null) throw new IOException("No se pudo crear la carpeta " + seg);
                invalidate(id);
                id = DocumentsContract.getDocumentId(nu);
            } else if (!e.dir) {
                throw new IOException("Ya existe un archivo llamado " + seg);
            } else {
                id = e.id;
            }
        }
        return id;
    }

    @Override
    List<String> listDirs(Object dir) throws IOException {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Entry> e : list((String) dir, true).byName.entrySet()) {
            if (e.getValue().dir && !e.getKey().startsWith(".")) out.add(e.getKey());
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    List<FileInfo> listFiles(Object dir) throws IOException {
        List<FileInfo> out = new ArrayList<FileInfo>();
        for (Map.Entry<String, Entry> e : list((String) dir, true).byName.entrySet()) {
            if (!e.getValue().dir && !hidden(e.getKey())) {
                out.add(new FileInfo(e.getKey(), e.getValue().size, e.getValue().mtime));
            }
        }
        sortFiles(out);
        return out;
    }

    @Override
    Readable read(Object dir, String name) throws IOException {
        Entry e = list((String) dir, false).byName.get(name);
        if (e == null) e = list((String) dir, true).byName.get(name);
        if (e == null || e.dir) return null;
        final Entry entry = e;
        final ParcelFileDescriptor pfd;
        try {
            pfd = cr.openFileDescriptor(docUri(e.id), "r");
        } catch (Exception ex) {
            throw new IOException("No se pudo abrir " + name, ex);
        }
        if (pfd == null) throw new IOException("No se pudo abrir " + name);
        return new Readable() {
            @Override
            public long size() {
                long s = pfd.getStatSize();
                return s >= 0 ? s : entry.size;
            }

            @Override
            public long mtime() {
                return entry.mtime;
            }

            @Override
            public InputStream open(long offset) throws IOException {
                FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                in.getChannel().position(offset);
                return in;
            }

            @Override
            public FileDescriptor fd() {
                return pfd.getFileDescriptor();
            }

            @Override
            public void close() {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    @Override
    long fileSize(Object dir, String name) throws IOException {
        Entry e = list((String) dir, false).byName.get(name);
        return e == null || e.dir ? -1 : e.size;
    }

    @Override
    Upload begin(Object dir, final String name) throws IOException {
        final String dirId = (String) dir;
        final Uri tmp;
        try {
            tmp = DocumentsContract.createDocument(cr, docUri(dirId), "application/octet-stream", tempName(name));
        } catch (Exception e) {
            throw new IOException("No se pudo crear el fichero en el disco", e);
        }
        if (tmp == null) throw new IOException("No se pudo crear el fichero en el disco");
        final OutputStream os = cr.openOutputStream(tmp, "w");
        if (os == null) throw new IOException("No se pudo abrir el fichero en el disco");
        return new Upload() {
            @Override
            public OutputStream out() {
                return os;
            }

            @Override
            public Committed commit(long mtime, long size) throws IOException {
                synchronized (SafStore.this) {
                    Listing l = list(dirId, true);
                    Entry existing = l.byName.get(name);
                    String finalName = name;
                    if (existing != null) {
                        if (!existing.dir && existing.size == size) {
                            delete(tmp);
                            invalidate(dirId);
                            return new Committed(name, true);
                        }
                        finalName = uniqueName(l.byName.keySet(), name);
                    }
                    Uri renamed;
                    try {
                        renamed = DocumentsContract.renameDocument(cr, tmp, finalName);
                    } catch (Exception e) {
                        throw new IOException("No se pudo renombrar el fichero", e);
                    }
                    invalidate(dirId);
                    if (renamed == null && list(dirId, true).byName.get(finalName) == null) {
                        throw new IOException("No se pudo renombrar el fichero");
                    }
                    return new Committed(finalName, false);
                }
            }

            @Override
            public void abort() {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                delete(tmp);
                invalidate(dirId);
            }
        };
    }

    private void delete(Uri u) {
        try {
            DocumentsContract.deleteDocument(cr, u);
        } catch (Exception ignored) {
        }
    }

    private File volumeRoot() {
        int c = rootId.indexOf(':');
        String vol = c >= 0 ? rootId.substring(0, c) : rootId;
        if ("primary".equalsIgnoreCase(vol)) return Environment.getExternalStorageDirectory();
        return new File("/storage/" + vol);
    }

    @Override
    long free() {
        try {
            return new StatFs(volumeRoot().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    long total() {
        try {
            return new StatFs(volumeRoot().getPath()).getTotalBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    String describe() {
        int c = rootId.indexOf(':');
        String vol = c >= 0 ? rootId.substring(0, c) : rootId;
        String path = c >= 0 ? rootId.substring(c + 1) : "";
        return "Disco " + vol + " / " + path;
    }
}
