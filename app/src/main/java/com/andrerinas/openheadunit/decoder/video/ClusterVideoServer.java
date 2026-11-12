package com.andrerinas.openheadunit.decoder.video;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.andrerinas.openheadunit.utils.ToastUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-Pipeline Instrument Cluster Video Relay.
 *
 * Receives real-time H.264 Annex-B NAL units from Android Auto's secondary video channel
 * (Channel.ID_CLU = 14, display_id = 1) and broadcasts cached SPS/PPS configuration + live
 * H.264 IDR/P-frames over a non-blocking TCP ServerSocket on 0.0.0.0:5289 (configurable via
 * "cluster_port" in the "settings" SharedPreferences store) to ClusterReceiverActivity.
 *
 * Does not allocate a local MediaCodec or attach to DisplayManager.getDisplays(), ensuring
 * zero interference with Android Auto's VirtualDisplay or hardware codecs in Self Mode.
 */
public final class ClusterVideoServer {

    private static final String TAG = "ClusterVideoServer";
    public static final String SETTINGS_PREFS_NAME = "settings";
    public static final int DEFAULT_CLUSTER_TCP_PORT = 5289;
    public static final int CLUSTER_TCP_PORT = DEFAULT_CLUSTER_TCP_PORT;

    public static final String PREF_ENABLE_CLUSTER_DISPLAY = "enable_cluster_display";
    public static final String PREF_CLUSTER_RESOLUTION = "cluster_resolution";
    public static final String PREF_CLUSTER_PORT = "cluster_port";

    private static final ClusterVideoServer INSTANCE = new ClusterVideoServer();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();
    private final Object codecConfigLock = new Object();

    private volatile byte[] cachedSpsPpsAnnexB = null;
    private volatile int activeTcpPort = DEFAULT_CLUSTER_TCP_PORT;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService senderPool;

    private Context appContext;

    private ClusterVideoServer() {}

    public static ClusterVideoServer getInstance() {
        return INSTANCE;
    }

    public int getListenPort() {
        return activeTcpPort;
    }

    /**
     * Entry point invoked from JNI (`Java_com_andrerinas_openheadunit_decoder_video_ClusterVideoServer_nativeOnClusterFrame`)
     * or directly from the Kotlin AAP cluster video handler (`AapClusterVideo`).
     */
    public static void onNativeClusterH264Frame(ByteBuffer directBuf, int size, boolean isCodecConfig) {
        if (directBuf == null || size <= 0) return;
        byte[] payload = new byte[size];
        ByteBuffer dup = directBuf.duplicate();
        dup.position(0);
        dup.limit(Math.min(size, dup.capacity()));
        dup.get(payload, 0, dup.remaining());
        INSTANCE.onClusterH264Frame(payload, 0, payload.length, isCodecConfig);
    }

    /**
     * Starts the TCP Annex-B relay on 0.0.0.0:<cluster_port> (default 5289).
     */
    public synchronized void start(Context context) {
        this.appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        int configuredPort = prefs.getInt(PREF_CLUSTER_PORT, DEFAULT_CLUSTER_TCP_PORT);
        if (configuredPort <= 1024 || configuredPort > 65535 || configuredPort == 5288) {
            configuredPort = DEFAULT_CLUSTER_TCP_PORT;
        }
        start(context, configuredPort);
    }

    public synchronized void start(Context context, int port) {
        this.appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(PREF_ENABLE_CLUSTER_DISPLAY, true);
        if (!enabled) {
            Log.i(TAG, "Cluster display disabled in preferences (enable_cluster_display=false)");
            return;
        }

        int targetPort = (port <= 1024 || port > 65535 || port == 5288) ? DEFAULT_CLUSTER_TCP_PORT : port;
        if (running.get() && activeTcpPort == targetPort && serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        if (running.get()) {
            stop();
        }

        activeTcpPort = targetPort;
        String res = prefs.getString(PREF_CLUSTER_RESOLUTION, "800x480");

        running.set(true);
        senderPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ClusterTcpRelaySender");
            t.setDaemon(true);
            return t;
        });

        acceptThread = new Thread(() -> runAcceptLoop(targetPort), "ClusterTcpAccept-" + targetPort);
        acceptThread.setDaemon(true);
        acceptThread.start();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            String localIp = resolveLocalIpAddress(appContext);
            ToastUtils.showToast(
                    appContext,
                    "OpenHeadunit Cluster Stream Ready -> " + localIp + ":" + targetPort + " (" + res + ")",
                    Toast.LENGTH_LONG,
                    false
            );
        });
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        serverSocket = null;

        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
        clients.clear();

        if (senderPool != null) {
            senderPool.shutdownNow();
            senderPool = null;
        }
    }

    private void runAcceptLoop(int listenPort) {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", listenPort));
            Log.i(TAG, "Cluster TCP Annex-B Server listening on 0.0.0.0:" + listenPort);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setSendBufferSize(256 * 1024);
                Log.i(TAG, "Spare cluster phone connected from " + client.getRemoteSocketAddress());

                synchronized (codecConfigLock) {
                    if (cachedSpsPpsAnnexB != null && cachedSpsPpsAnnexB.length > 0) {
                        try {
                            OutputStream os = client.getOutputStream();
                            os.write(cachedSpsPpsAnnexB);
                            os.flush();
                            Log.i(TAG, "Sent cached SPS/PPS Annex-B header (" + cachedSpsPpsAnnexB.length + " bytes) to new client");
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to write initial SPS/PPS to cluster client", e);
                            try { client.close(); } catch (IOException ignored) {}
                            continue;
                        }
                    }
                }
                clients.add(client);
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "Cluster TCP ServerSocket error on port " + listenPort, e);
            }
        }
    }

    /**
     * Processes each incoming H.264 frame from CLUSTER_VIDEO_CHANNEL_ID.
     * Ensures Annex-B start codes (`0x00 0x00 0x00 0x01`), caches SPS/PPS NAL units,
     * and broadcasts non-blockingly to all connected TCP port 5289 receivers.
     */
    public void onClusterH264Frame(byte[] data, int offset, int length, boolean isCodecConfig) {
        if (data == null || length <= 0) return;

        byte[] annexB = normalizeToAnnexB(data, offset, length);
        if (isCodecConfig || containsSpsOrPps(annexB)) {
            synchronized (codecConfigLock) {
                cachedSpsPpsAnnexB = extractOrCopySpsPps(annexB, isCodecConfig);
            }
        }

        if (running.get() && !clients.isEmpty() && senderPool != null && !senderPool.isShutdown()) {
            final byte[] frameCopy = annexB;
            senderPool.execute(() -> broadcastToTcpClients(frameCopy));
        }
    }

    private void broadcastToTcpClients(byte[] annexBFrame) {
        for (Socket client : clients) {
            if (client.isClosed() || !client.isConnected()) {
                clients.remove(client);
                continue;
            }
            try {
                OutputStream os = client.getOutputStream();
                os.write(annexBFrame);
                os.flush();
            } catch (IOException e) {
                Log.w(TAG, "Cluster client disconnected (" + client.getRemoteSocketAddress() + "): " + e.getMessage());
                clients.remove(client);
                try {
                    client.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static byte[] normalizeToAnnexB(byte[] data, int offset, int length) {
        if (length >= 4
                && data[offset] == 0x00
                && data[offset + 1] == 0x00
                && data[offset + 2] == 0x00
                && data[offset + 3] == 0x01) {
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            return copy;
        }
        if (length >= 3
                && data[offset] == 0x00
                && data[offset + 1] == 0x00
                && data[offset + 2] == 0x01) {
            byte[] with4ByteStart = new byte[length + 1];
            with4ByteStart[0] = 0x00;
            with4ByteStart[1] = 0x00;
            with4ByteStart[2] = 0x00;
            with4ByteStart[3] = 0x01;
            System.arraycopy(data, offset + 3, with4ByteStart, 4, length - 3);
            return with4ByteStart;
        }
        byte[] prefixed = new byte[length + 4];
        prefixed[0] = 0x00;
        prefixed[1] = 0x00;
        prefixed[2] = 0x00;
        prefixed[3] = 0x01;
        System.arraycopy(data, offset, prefixed, 4, length);
        return prefixed;
    }

    private static boolean containsSpsOrPps(byte[] annexB) {
        for (int i = 0; i <= annexB.length - 5; i++) {
            if (annexB[i] == 0x00 && annexB[i + 1] == 0x00 && annexB[i + 2] == 0x00 && annexB[i + 3] == 0x01) {
                int nalType = annexB[i + 4] & 0x1F;
                if (nalType == 7 || nalType == 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] extractOrCopySpsPps(byte[] annexB, boolean isPureConfig) {
        if (isPureConfig) {
            return annexB.clone();
        }
        for (int i = 4; i <= annexB.length - 5; i++) {
            if (annexB[i] == 0x00 && annexB[i + 1] == 0x00 && annexB[i + 2] == 0x00 && annexB[i + 3] == 0x01) {
                int nalType = annexB[i + 4] & 0x1F;
                if (nalType == 5 || nalType == 1) {
                    byte[] configOnly = new byte[i];
                    System.arraycopy(annexB, 0, configOnly, 0, i);
                    return configOnly;
                }
            }
        }
        return annexB.clone();
    }

    public static String resolveLocalIpAddress(Context context) {
        String fallbackIp = null;
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (host == null) continue;
                        if (name.startsWith("wlan") || name.startsWith("ap")
                                || name.startsWith("swlan") || name.startsWith("p2p")
                                || name.startsWith("rndis") || name.startsWith("eth")) {
                            return host;
                        }
                        if (fallbackIp == null) {
                            fallbackIp = host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getIpAddress() != 0) {
                        int ip = info.getIpAddress();
                        return String.format("%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            } catch (Exception ignored) {}
        }
        return fallbackIp != null ? fallbackIp : "192.168.43.1";
    }
}
