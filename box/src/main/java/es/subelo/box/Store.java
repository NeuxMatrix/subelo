package es.subelo.box;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

/** Destino donde se guardan las fotos (disco USB). */
abstract class Store {

    static final class Committed {
        final String name;
        final boolean duplicate;

        Committed(String name, boolean duplicate) {
            this.name = name;
            this.duplicate = duplicate;
        }
    }

    static final class FileInfo {
        final String name;
        final long size;
        final long mtime;

        FileInfo(String name, long size, long mtime) {
            this.name = name;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** Fichero abierto para leer (descargas, miniaturas). Hay que cerrarlo. */
    interface Readable {
        long size();

        long mtime();

        /** Flujo de lectura empezando en la posición indicada. Llamar una sola vez. */
        InputStream open(long offset) throws IOException;

        FileDescriptor fd() throws IOException;

        void close();
    }

    interface Upload {
        OutputStream out();

        /** Da el fichero por terminado. Si ya existía uno igual (mismo nombre y tamaño) lo descarta. */
        Committed commit(long mtime, long size) throws IOException;

        void abort();
    }

    abstract boolean available();

    /** Devuelve un identificador de la carpeta relativa, creándola si hace falta; null si no existe. */
    abstract Object dir(String rel, boolean create) throws IOException;

    abstract List<String> listDirs(Object dir) throws IOException;

    /** Ficheros (no carpetas) de una carpeta, ordenados por nombre. */
    abstract List<FileInfo> listFiles(Object dir) throws IOException;

    /** Abre un fichero para leer; null si no existe. */
    abstract Readable read(Object dir, String name) throws IOException;

    /** Tamaño del fichero o -1 si no existe. */
    abstract long fileSize(Object dir, String name) throws IOException;

    abstract Upload begin(Object dir, String name) throws IOException;

    abstract long free();

    abstract long total();

    abstract String describe();

    static String uniqueName(Collection<String> existing, String name) {
        if (!existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 100000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!existing.contains(cand)) return cand;
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    static void sortFiles(List<FileInfo> l) {
        java.util.Collections.sort(l, new java.util.Comparator<FileInfo>() {
            @Override
            public int compare(FileInfo a, FileInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    static boolean hidden(String name) {
        return name.startsWith(".");
    }

    static String tempName(String name) {
        return "." + name + "." + Long.toHexString(Double.doubleToLongBits(Math.random())) + ".part";
    }
}
