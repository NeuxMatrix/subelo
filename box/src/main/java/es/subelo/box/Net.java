package es.subelo.box;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Net {
    private Net() {}

    /** Direcciones IPv4 de la red de casa (Ethernet o Wi-Fi). */
    static List<String> localIps() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String mainUrl() {
        List<String> ips = localIps();
        return ips.isEmpty() ? null : "https://" + ips.get(0) + ":" + Config.PORT;
    }

    // Redes en las que está el box ahora mismo (dirección + prefijo), refrescadas como mucho cada 30 s.
    private static volatile List<InterfaceAddress> nets = Collections.emptyList();
    private static volatile long netsAt;

    /**
     * ¿Viene la conexión de dentro de casa? El box solo atiende a la red local: aunque el router
     * abriera el puerto (UPnP, IPv6 sin cortafuegos…), una conexión desde internet se corta sin contestar.
     * Vale: el propio box, direcciones privadas (192.168.x, 10.x, 172.16-31.x), de enlace local,
     * IPv6 privadas (fc00::/7) y cualquier dirección de la misma red que el box (p. ej. su IPv6 de casa).
     */
    static boolean fromHome(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet4Address && a.isSiteLocalAddress()) return true; // 10/8, 172.16/12, 192.168/16
        if (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc) return true; // fc00::/7
        return onLink(a);
    }

    private static boolean onLink(InetAddress a) {
        long now = System.currentTimeMillis();
        if (now - netsAt > 30000) {
            List<InterfaceAddress> l = new ArrayList<InterfaceAddress>();
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    l.addAll(ni.getInterfaceAddresses());
                }
            } catch (Exception ignored) {
            }
            nets = l;
            netsAt = now;
        }
        byte[] b = a.getAddress();
        for (InterfaceAddress ia : nets) {
            InetAddress mine = ia.getAddress();
            if (mine == null || mine.getClass() != a.getClass()) continue;
            int bits = ia.getNetworkPrefixLength();
            // una red demasiado grande no es «casa» (y /0 lo dejaría pasar todo)
            if (bits < (a instanceof Inet4Address ? 16 : 48) || bits > b.length * 8) continue;
            if (samePrefix(b, mine.getAddress(), bits)) return true;
        }
        return false;
    }

    static boolean samePrefix(byte[] x, byte[] y, int bits) {
        if (x.length != y.length) return false;
        int full = bits / 8, rest = bits % 8;
        for (int i = 0; i < full; i++) if (x[i] != y[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (x[full] & mask) == (y[full] & mask);
    }
}
