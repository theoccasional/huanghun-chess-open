package com.pikafish.chess.heavy;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ★★★ 局域网自动发现（mDNS / Bonjour）：让「辎重包主机谁在谁当」不必手输 IP。★★★
 *
 * <p>本类只负责「找到对方」，不涉及任何文件传输。找到之后，上层照旧用
 * {@link HeavyStore#attachRemote} 走既有的 HTTP Range 直读通道 —— 也就是说：
 * 协议仍是 HTTP，这里只是补上了「地址从哪儿来」这一环。</p>
 *
 * <p>工作机制：</p>
 * <ol>
 *   <li><b>共享方</b>：本机开启共享（{@link HeavyHttpServer}）时，以 {@code _heavy._tcp}
 *       服务名对外广播「我这有辎重包可直读 + 端口号」。</li>
 *   <li><b>直读方</b>：扫描同一局域网内的 {@code _heavy._tcp} 服务，解析出各自主机的
 *       {@code IP:端口}，拼成 {@link Peer#url()}，直接喂给 {@link HeavyStore#attachRemote}。</li>
 * </ol>
 *
 * <p>覆盖范围：手机 ↔ 手机（双方都装了本 App，可自动发现）。电脑侧若也在跑支持 mDNS 的
 * 文件服务（装了 Bonjour/Avahi 广播），同样能被发现；否则电脑侧仍需手填一次地址，
 * 或直接下载到本机。</p>
 */
public final class HeavyDiscovery {

    /** 服务类型：本 App 的辎重包直读服务。Android 的 NsdManager 要求带结尾点。 */
    public static final String SERVICE_TYPE = "_heavy._tcp.";

    /** 一次扫描持续多久（毫秒）后自动收尾。 */
    private static final long SCAN_TIMEOUT_MS = 6000L;

    private static NsdManager.RegistrationListener registrationListener;

    private HeavyDiscovery() {}

    /** 发现到的一台可直读设备。 */
    public static final class Peer {
        public final String name;
        public final String host;
        public final int port;

        Peer(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        /** 可直接用于远程直读的地址。 */
        public String url() {
            return "http://" + host + ":" + port;
        }
    }

    /** 发现回调；可被多次调用（每发现一台就回调一次，最后再回调一次完整列表）。 */
    public interface PeerListener {
        void onFound(List<Peer> peers);

        void onFailed(String message);
    }

    // ═════════════════ 对外广播（共享方） ═════════════════

    /** 以指定名称在局域网广播「本机可直读辎重包」。重复调用会先撤销旧的广播。 */
    public static void advertise(Context context, String serviceName, int port) {
        unadvertise(context);
        if (port <= 0) {
            return;
        }
        final NsdManager manager = nsd(context);
        if (manager == null) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            registrationListener = listener;
        } catch (Exception ignored) {
            // 广播失败仅影响自动发现，不影响直读本身
        }
    }

    /** 撤销之前对外的广播。 */
    public static void unadvertise(Context context) {
        NsdManager.RegistrationListener listener = registrationListener;
        registrationListener = null;
        if (listener == null) {
            return;
        }
        NsdManager manager = nsd(context);
        if (manager == null) {
            return;
        }
        try {
            manager.unregisterService(listener);
        } catch (Exception ignored) {
        }
    }

    // ═════════════════ 扫描（直读方） ═════════════════

    /** 扫描局域网内可直读的辎重包主机，结果通过 {@link PeerListener} 回调（含超时收尾）。 */
    public static void discover(final Context context, final PeerListener listener) {
        final NsdManager manager = nsd(context);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Set<Peer> found = new LinkedHashSet<>();

        if (manager == null) {
            handler.post(() -> listener.onFailed("本机不支持自动发现，请手动填写对方地址。"));
            return;
        }

        final NsdManager.DiscoveryListener[] discoveryRef = new NsdManager.DiscoveryListener[1];
        discoveryRef[0] = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                // 每个服务各用一个解析监听器：NsdManager 不允许同一个监听器并发展开多次解析。
                NsdManager.ResolveListener resolve = new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        InetAddress address = info.getHost();
                        if (address == null) {
                            return;
                        }
                        String host = address.getHostAddress();
                        if (host == null || host.isEmpty()) {
                            return;
                        }
                        boolean added;
                        synchronized (found) {
                            added = found.add(new Peer(info.getServiceName(), host, info.getPort()));
                        }
                        if (added) {
                            handler.post(() -> listener.onFound(snapshot(found)));
                        }
                    }
                };
                try {
                    manager.resolveService(serviceInfo, resolve);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                handler.post(() -> listener.onFailed("无法开始自动发现（错误码 " + errorCode + "）"));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryRef[0]);
        } catch (Exception e) {
            handler.post(() -> listener.onFailed("自动发现失败：" + e.getMessage()));
            return;
        }

        handler.postDelayed(() -> {
            try {
                manager.stopServiceDiscovery(discoveryRef[0]);
            } catch (Exception ignored) {
            }
            listener.onFound(snapshot(found));
        }, SCAN_TIMEOUT_MS);
    }

    private static List<Peer> snapshot(Set<Peer> found) {
        synchronized (found) {
            return new ArrayList<>(found);
        }
    }

    private static NsdManager nsd(Context context) {
        if (context == null) {
            return null;
        }
        Object service = context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        return service instanceof NsdManager ? (NsdManager) service : null;
    }
}
