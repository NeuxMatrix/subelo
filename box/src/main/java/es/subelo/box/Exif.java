package es.subelo.box;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lee del EXIF de una foto JPEG dónde y cuándo se hizo. Java puro, sin depender de la versión de
 * Android del box: solo mira la cabecera (los primeros KB del fichero).
 */
final class Exif {
    static final int HEAD = 192 * 1024;

    /** Coordenadas (NaN si no tiene) y fecha «AAAA-MM-DD» (null si no tiene). */
    static final class Info {
        double lat = Double.NaN, lon = Double.NaN;
        String date;

        boolean hasGps() {
            return !Double.isNaN(lat);
        }
    }

    private final byte[] b;
    private final int len;
    private int tiff;
    private boolean little;

    private Exif(byte[] b, int len) {
        this.b = b;
        this.len = len;
    }

    static Info read(InputStream in) throws IOException {
        byte[] buf = new byte[HEAD];
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        Info info = new Info();
        try {
            new Exif(buf, n).parse(info);
        } catch (RuntimeException ignored) {
            // cabecera rara o cortada: lo que se haya leído vale
        }
        return info;
    }

    private void parse(Info info) {
        if (len < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return;
        int p = 2;
        while (p + 4 <= len) {
            if ((b[p] & 0xFF) != 0xFF) return;
            int marker = b[p + 1] & 0xFF;
            if (marker == 0xFF) {
                p++;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) return; // fin de cabeceras
            int segLen = u16be(p + 2);
            if (marker == 0xE1 && segLen > 8 && p + 10 <= len
                    && b[p + 4] == 'E' && b[p + 5] == 'x' && b[p + 6] == 'i' && b[p + 7] == 'f' && b[p + 8] == 0) {
                tiff = p + 10;
                tiff(info);
                return;
            }
            p += 2 + segLen;
        }
    }

    private void tiff(Info info) {
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') little = true;
        else if (b[tiff] == 'M' && b[tiff + 1] == 'M') little = false;
        else return;
        if (u16(tiff + 2) != 42) return;
        int ifd0 = tiff + (int) u32(tiff + 4);
        int gps = -1, exif = -1;
        String dateTime = null;
        int count = u16(ifd0);
        for (int i = 0; i < count; i++) {
            int e = ifd0 + 2 + i * 12;
            int tag = u16(e);
            if (tag == 0x8825) gps = tiff + (int) u32(e + 8);
            else if (tag == 0x8769) exif = tiff + (int) u32(e + 8);
            else if (tag == 0x0132) dateTime = ascii(e);
        }
        if (exif > 0) {
            int c = u16(exif);
            for (int i = 0; i < c; i++) {
                int e = exif + 2 + i * 12;
                if (u16(e) == 0x9003) {
                    String d = ascii(e);
                    if (d != null) dateTime = d;
                    break;
                }
            }
        }
        info.date = date(dateTime);
        if (gps > 0) gps(gps, info);
    }

    private void gps(int ifd, Info info) {
        char latRef = 0, lonRef = 0;
        double lat = Double.NaN, lon = Double.NaN;
        int count = u16(ifd);
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + i * 12;
            int tag = u16(e);
            if (tag == 1) latRef = (char) b[e + 8];
            else if (tag == 3) lonRef = (char) b[e + 8];
            else if (tag == 2) lat = dms(e);
            else if (tag == 4) lon = dms(e);
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        if (latRef == 'S') lat = -lat;
        if (lonRef == 'W') lon = -lon;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) return;
        info.lat = lat;
        info.lon = lon;
    }

    /** Grados, minutos y segundos (3 RATIONAL) a grados decimales. */
    private double dms(int e) {
        if (u16(e + 2) != 5 || u32(e + 4) < 3) return Double.NaN;
        int at = tiff + (int) u32(e + 8);
        double v = 0, div = 1;
        for (int k = 0; k < 3; k++) {
            long num = u32(at + k * 8), den = u32(at + k * 8 + 4);
            if (den == 0) {
                if (num != 0) return Double.NaN;
            } else {
                v += (double) num / den / div;
            }
            div *= 60;
        }
        return v;
    }

    private String ascii(int e) {
        if (u16(e + 2) != 2) return null;
        int n = (int) u32(e + 4);
        if (n <= 0 || n > 64) return null;
        int at = n <= 4 ? e + 8 : tiff + (int) u32(e + 8);
        if (at + n > len) return null;
        int end = at;
        while (end < at + n && b[end] != 0) end++;
        try {
            return new String(b, at, end - at, "US-ASCII").trim();
        } catch (java.io.UnsupportedEncodingException x) {
            return null;
        }
    }

    /** «2024:05:03 18:22:10» → «2024-05-03». */
    private static String date(String s) {
        if (s == null || s.length() < 10) return null;
        String d = s.substring(0, 10).replace(':', '-');
        if (!d.matches("(19|20)\\d\\d-[01]\\d-[0-3]\\d")) return null;
        return d;
    }

    private int u16be(int p) {
        check(p + 2);
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private int u16(int p) {
        check(p + 2);
        return little ? (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) : u16be(p);
    }

    private long u32(int p) {
        check(p + 4);
        long v = little
                ? (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((long) (b[p + 3] & 0xFF) << 24)
                : ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
        return v & 0xFFFFFFFFL;
    }

    private void check(int end) {
        if (end > len) throw new IndexOutOfBoundsException();
    }
}
