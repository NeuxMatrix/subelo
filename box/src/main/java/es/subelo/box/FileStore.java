package es.subelo.box;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Guarda en una ruta normal del sistema de ficheros (p. ej. /storage/1234-ABCD). */
final class FileStore extends Store {
    private final File root;

    FileStore(File root) {
        this.root = root;
    }

    @Override
    boolean available() {
        return root.isDirectory() || root.mkdirs();
    }

    @Override
    synchronized Object dir(String rel, boolean create) throws IOException {
        File f = rel.isEmpty() ? root : new File(root, rel);
        if (f.isDirectory()) return f;
        if (!create) return null;
        if (!f.mkdirs() && !f.isDirectory()) throw new IOException("No se pudo crear la carpeta " + rel);
        return f;
    }

    @Override
    List<String> listDirs(Object dir) {
        List<String> out = new ArrayList<String>();
        File[] files = ((File) dir).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f.getName());
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    List<FileInfo> listFiles(Object dir) {
        List<FileInfo> out = new ArrayList<FileInfo>();
        File[] files = ((File) dir).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !hidden(f.getName())) out.add(new FileInfo(f.getName(), f.length(), f.lastModified()));
            }
        }
        sortFiles(out);
        return out;
    }

    @Override
    Readable read(Object dir, String name) throws IOException {
        final File f = new File((File) dir, name);
        if (!f.isFile()) return null;
        return new Readable() {
            private RandomAccessFile raf;

            @Override
            public long size() {
                return f.length();
            }

            @Override
            public long mtime() {
                return f.lastModified();
            }

            @Override
            public InputStream open(long offset) throws IOException {
                FileInputStream in = new FileInputStream(f);
                in.getChannel().position(offset);
                return in;
            }

            @Override
            public FileDescriptor fd() throws IOException {
                if (raf == null) raf = new RandomAccessFile(f, "r");
                return raf.getFD();
            }

            @Override
            public void close() {
                try {
                    if (raf != null) raf.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    @Override
    long fileSize(Object dir, String name) {
        File f = new File((File) dir, name);
        return f.isFile() ? f.length() : -1;
    }

    @Override
    Upload begin(Object dir, final String name) throws IOException {
        final File d = (File) dir;
        final File tmp = new File(d, tempName(name));
        final OutputStream os = new FileOutputStream(tmp);
        return new Upload() {
            @Override
            public OutputStream out() {
                return os;
            }

            @Override
            public Committed commit(long mtime, long size) throws IOException {
                synchronized (FileStore.this) {
                    File dest = new File(d, name);
                    if (dest.exists()) {
                        if (dest.isFile() && dest.length() == size) {
                            tmp.delete();
                            return new Committed(name, true);
                        }
                        Set<String> names = new HashSet<String>();
                        String[] list = d.list();
                        if (list != null) Collections.addAll(names, list);
                        dest = new File(d, uniqueName(names, name));
                    }
                    if (!tmp.renameTo(dest)) throw new IOException("No se pudo renombrar " + tmp.getName());
                    if (mtime > 0) dest.setLastModified(mtime);
                    return new Committed(dest.getName(), false);
                }
            }

            @Override
            public void abort() {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                tmp.delete();
            }
        };
    }

    @Override
    synchronized void delete(Object dir, String name) throws IOException {
        File f = new File((File) dir, name);
        if (!f.exists()) throw new IOException("No existe " + name);
        deleteTree(f);
        if (f.exists()) throw new IOException("No se pudo borrar " + name);
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    @Override
    synchronized String move(Object from, String name, Object to) throws IOException {
        File src = new File((File) from, name);
        if (!src.exists()) throw new IOException("No existe " + name);
        File d = (File) to;
        String target = name;
        if (new File(d, target).exists()) {
            Set<String> names = new HashSet<String>();
            String[] list = d.list();
            if (list != null) Collections.addAll(names, list);
            target = uniqueName(names, name);
        }
        if (!src.renameTo(new File(d, target))) throw new IOException("No se pudo mover " + name);
        return target;
    }

    @Override
    synchronized boolean rename(Object dir, String name, String newName) throws IOException {
        File src = new File((File) dir, name), dst = new File((File) dir, newName);
        if (!src.exists()) throw new IOException("No existe " + name);
        if (dst.exists() && !name.equalsIgnoreCase(newName)) return false;
        if (!src.renameTo(dst)) throw new IOException("No se pudo renombrar " + name);
        return true;
    }

    @Override
    long free() {
        return root.getUsableSpace();
    }

    @Override
    long total() {
        return root.getTotalSpace();
    }

    @Override
    String describe() {
        return root.getAbsolutePath();
    }
}
