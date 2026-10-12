package es.subelo.box;

import java.net.Inet4Address;
import java.net.InetAddress;
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
}
