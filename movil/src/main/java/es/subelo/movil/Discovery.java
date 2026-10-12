package es.subelo.movil;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Busca el box en la red de casa con mDNS (servicio _subelo._tcp). Llamar desde un hilo de fondo. */
final class Discovery {
    static final String SERVICE_TYPE = "_subelo._tcp";

    static final class Found {
        final String name;
        final String host;
        final int port;

        Found(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private Discovery() {}

    static List<Found> discover(Context ctx, long timeoutMs) {
        Context app = ctx.getApplicationContext();
        final NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        final List<NsdServiceInfo> seen = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
        List<Found> out = new ArrayList<Found>();
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo si) { seen.add(si); }
            @Override public void onServiceLost(NsdServiceInfo si) {}
        };
        try {
            if (wm != null) {
                lock = wm.createMulticastLock("subelo:discover");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
            Thread.sleep(timeoutMs);
        } catch (Exception ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(dl);
            } catch (Exception ignored) {
            }
        }
        Map<String, NsdServiceInfo> unique = new LinkedHashMap<String, NsdServiceInfo>();
        synchronized (seen) {
            for (NsdServiceInfo si : seen) unique.put(si.getServiceName(), si);
        }
        for (NsdServiceInfo si : unique.values()) {
            NsdServiceInfo r = resolve(nsd, si);
            if (r == null) continue;
            String host = pickHost(r);
            if (host != null) out.add(new Found(r.getServiceName(), host, r.getPort()));
        }
        if (lock != null && lock.isHeld()) lock.release();
        return out;
    }

    @SuppressWarnings("deprecation")
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo si) {
        final NsdServiceInfo[] res = new NsdServiceInfo[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo s, int e) {
                    latch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo s) {
                    res[0] = s;
                    latch.countDown();
                }
            });
            latch.await(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return res[0];
    }

    @SuppressWarnings("deprecation")
    private static String pickHost(NsdServiceInfo r) {
        List<InetAddress> addrs = new ArrayList<InetAddress>();
        if (Build.VERSION.SDK_INT >= 34) addrs.addAll(r.getHostAddresses());
        if (r.getHost() != null) addrs.add(r.getHost());
        for (InetAddress a : addrs) if (a instanceof Inet4Address) return a.getHostAddress();
        for (InetAddress a : addrs) {
            String h = a.getHostAddress();
            int pct = h.indexOf('%');
            if (pct > 0) h = h.substring(0, pct) + "%25" + h.substring(pct + 1);
            return h;
        }
        return null;
    }
}
