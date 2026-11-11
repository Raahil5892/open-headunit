package com.andrerinas.openheadunit.decoder.video;

import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-Output Instrument Cluster Video Pipeline for Open Headunit (`com.andrerinas.openheadunit`).
 *
 * Receives real H.264 Annex-B NAL units from `CLUSTER_VIDEO_CHANNEL_ID` (`DISPLAY_TYPE_CLUSTER = 1`)
 * and distributes them across two zero-latency paths:
 *   1. Direct TCP Annex-B Streamer on `0.0.0.0:5288` with instant cached SPS/PPS replay
 *      upon client connection so a spare Android phone in the gauge cluster starts decoding immediately.
 *   2. Optional local Android `Presentation` window on `DisplayManager.getDisplays()[1]` backed
 *      by a dedicated secondary `MediaCodec.createDecoderByType("video/avc")` instance.
 */
public final class ClusterVideoServer {

    private static final String TAG = "ClusterVideoServer";
    public static final int CLUSTER_TCP_PORT = 5288;

    public static final String PREFS_NAME = "open-headunit";
    public static final String PREF_ENABLE_CLUSTER_DISPLAY = "enable_cluster_display";
    public static final String PREF_CLUSTER_RESOLUTION = "cluster_resolution";

    static {
        try {
            System.loadLibrary("hur_aap_cluster");
        } catch (Throwable t) {
            Log.w(TAG, "Optional libhur_aap_cluster.so not loaded: " + t.getMessage());
        }
    }

    public static native void nativeConfigureCluster(boolean enableCluster, int resolutionCode, boolean nightMode);

    private static volatile ClusterVideoServer sInstance;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private final ExecutorService mClientWritePool = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Socket> mClients = new CopyOnWriteArrayList<>();

    private final Object mConfigLock = new Object();
    private byte[] mCachedSpsPpsHeader = null;

    private ClusterPresentation mClusterPresentation;
    private MediaCodec mClusterCodec;
    private final Object mCodecLock = new Object();
    private int mClusterWidth = 800;
    private int mClusterHeight = 480;

    private ClusterVideoServer() {}

    public static ClusterVideoServer getInstance() {
        if (sInstance == null) {
            synchronized (ClusterVideoServer.class) {
                if (sInstance == null) {
                    sInstance = new ClusterVideoServer();
                }
            }
        }
        return sInstance;
    }

    public static void onClusterH264FrameFromNative(byte[] annexBPayload, boolean isCodecConfig) {
        ClusterVideoServer server = sInstance;
        if (server != null && annexBPayload != null && annexBPayload.length > 0) {
            server.onClusterH264Frame(annexBPayload, 0, annexBPayload.length, isCodecConfig);
        }
    }

    public void start(final Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(PREF_ENABLE_CLUSTER_DISPLAY, true);
        if (!enabled) {
            Log.i(TAG, "Cluster display disabled in preferences (enable_cluster_display=false)");
            return;
        }

        String resPref = prefs.getString(PREF_CLUSTER_RESOLUTION, "800x480");
        if ("1280x720".equals(resPref)) {
            mClusterWidth = 1280;
            mClusterHeight = 720;
        } else {
            mClusterWidth = 800;
            mClusterHeight = 480;
        }

        try {
            nativeConfigureCluster(true, "1280x720".equals(resPref) ? 2 : 1, false);
        } catch (UnsatisfiedLinkError ignored) {}

        if (!mRunning.compareAndSet(false, true)) {
            return;
        }

        mAcceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTcpAcceptLoop();
            }
        }, "ClusterVideo-TCP5288");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();

        final String localIp = getLocalWifiIpAddress();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                setupSecondaryPresentationIfPresent(context);
                String msg = "Cluster Stream Active: " + localIp + ":" + CLUSTER_TCP_PORT
                        + " (" + mClusterWidth + "x" + mClusterHeight + ")";
                Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                Log.i(TAG, msg);
            }
        });
    }

    private void runTcpAcceptLoop() {
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress("0.0.0.0", CLUSTER_TCP_PORT));
            Log.i(TAG, "Cluster TCP Annex-B Server listening on 0.0.0.0:" + CLUSTER_TCP_PORT);

            while (mRunning.get() && !mServerSocket.isClosed()) {
                final Socket client = mServerSocket.accept();
                client.setTcpNoDelay(true);
                client.setSendBufferSize(256 * 1024);
                Log.i(TAG, "Spare cluster phone connected from " + client.getRemoteSocketAddress());

                final byte[] cachedConfig;
                synchronized (mConfigLock) {
                    cachedConfig = (mCachedSpsPpsHeader != null)
                            ? Arrays.copyOf(mCachedSpsPpsHeader, mCachedSpsPpsHeader.length)
                            : null;
                }

                mClientWritePool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (cachedConfig != null) {
                                OutputStream os = client.getOutputStream();
                                os.write(cachedConfig);
                                os.flush();
                                Log.i(TAG, "Sent cached SPS/PPS (" + cachedConfig.length
                                        + " bytes) to cluster client " + client.getRemoteSocketAddress());
                            }
                            mClients.add(client);
                        } catch (IOException e) {
                            Log.w(TAG, "Failed initial SPS/PPS write to cluster client", e);
                            closeQuietly(client);
                        }
                    }
                });
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                Log.e(TAG, "Cluster TCP ServerSocket error on port " + CLUSTER_TCP_PORT, e);
            }
        }
    }

    public void onClusterH264Frame(byte[] data, int offset, int length, boolean isCodecConfig) {
        if (!mRunning.get() || data == null || length <= 0) {
            return;
        }

        final byte[] frameCopy = Arrays.copyOfRange(data, offset, offset + length);

        if (isCodecConfig || containsSpsNalUnit(frameCopy)) {
            synchronized (mConfigLock) {
                mCachedSpsPpsHeader = frameCopy;
                Log.d(TAG, "Updated cached Cluster SPS/PPS header (" + frameCopy.length + " bytes)");
            }
        }

        if (!mClients.isEmpty()) {
            mClientWritePool.execute(new Runnable() {
                @Override
                public void run() {
                    for (Socket client : mClients) {
                        try {
                            OutputStream os = client.getOutputStream();
                            os.write(frameCopy);
                            os.flush();
                        } catch (IOException e) {
                            Log.w(TAG, "Cluster TCP client disconnected: " + client.getRemoteSocketAddress());
                            mClients.remove(client);
                            closeQuietly(client);
                        }
                    }
                }
            });
        }

        decodeToSecondarySurface(frameCopy, isCodecConfig);
    }

    public static boolean containsSpsNalUnit(byte[] buf) {
        if (buf == null || buf.length < 5) return false;
        for (int i = 0; i < buf.length - 4; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0) {
                int nalHeaderIdx = -1;
                if (buf[i + 2] == 1) {
                    nalHeaderIdx = i + 3;
                } else if (buf[i + 2] == 0 && buf[i + 3] == 1 && (i + 4) < buf.length) {
                    nalHeaderIdx = i + 4;
                }
                if (nalHeaderIdx != -1) {
                    int nalType = buf[nalHeaderIdx] & 0x1F;
                    if (nalType == 7) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void setupSecondaryPresentationIfPresent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return;
        Display[] displays = dm.getDisplays();
        if (displays != null && displays.length > 1) {
            Display clusterDisplay = displays[1];
            Log.i(TAG, "Detected secondary display: " + clusterDisplay.getName()
                    + " (id=" + clusterDisplay.getDisplayId() + "), attaching ClusterPresentation");
            try {
                mClusterPresentation = new ClusterPresentation(context, clusterDisplay);
                mClusterPresentation.show();
            } catch (WindowManager.InvalidDisplayException | SecurityException e) {
                Log.w(TAG, "Unable to show ClusterPresentation on displays[1]", e);
            }
        }
    }

    private void initSecondaryMediaCodec(Surface surface) {
        synchronized (mCodecLock) {
            releaseSecondaryMediaCodec();
            try {
                mClusterCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, mClusterWidth, mClusterHeight);
                synchronized (mConfigLock) {
                    if (mCachedSpsPpsHeader != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(mCachedSpsPpsHeader));
                    }
                }
                mClusterCodec.configure(format, surface, null, 0);
                mClusterCodec.start();
                Log.i(TAG, "Secondary MediaCodec (video/avc) started for ClusterPresentation ("
                        + mClusterWidth + "x" + mClusterHeight + ")");
            } catch (IOException | IllegalStateException e) {
                Log.e(TAG, "Failed to initialize secondary cluster MediaCodec", e);
                releaseSecondaryMediaCodec();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void decodeToSecondarySurface(byte[] frame, boolean isConfig) {
        synchronized (mCodecLock) {
            if (mClusterCodec == null) return;
            try {
                int inIndex = mClusterCodec.dequeueInputBuffer(0);
                if (inIndex >= 0) {
                    ByteBuffer inputBuffer = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                            ? mClusterCodec.getInputBuffer(inIndex)
                            : mClusterCodec.getInputBuffers()[inIndex];
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(frame);
                        int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                        mClusterCodec.queueInputBuffer(
                                inIndex, 0, frame.length, System.nanoTime() / 1000, flags);
                    }
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIndex = mClusterCodec.dequeueOutputBuffer(info, 0);
                while (outIndex >= 0) {
                    mClusterCodec.releaseOutputBuffer(outIndex, true);
                    outIndex = mClusterCodec.dequeueOutputBuffer(info, 0);
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "Secondary cluster MediaCodec decode exception", e);
            }
        }
    }

    private void releaseSecondaryMediaCodec() {
        synchronized (mCodecLock) {
            if (mClusterCodec != null) {
                try {
                    mClusterCodec.stop();
                } catch (Exception ignored) {}
                try {
                    mClusterCodec.release();
                } catch (Exception ignored) {}
                mClusterCodec = null;
            }
        }
    }

    public void stop() {
        if (!mRunning.compareAndSet(true, false)) {
            return;
        }
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException ignored) {}
        for (Socket client : mClients) {
            closeQuietly(client);
        }
        mClients.clear();
        releaseSecondaryMediaCodec();
        if (mClusterPresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                mClusterPresentation.dismiss();
            } catch (Exception ignored) {}
            mClusterPresentation = null;
        }
    }

    private static void closeQuietly(Socket sock) {
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {}
        }
    }

    public static String getLocalWifiIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private final class ClusterPresentation extends Presentation implements SurfaceHolder.Callback {
        private SurfaceView mSurfaceView;

        ClusterPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mSurfaceView = new SurfaceView(getContext());
            mSurfaceView.getHolder().addCallback(this);
            setContentView(mSurfaceView);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            initSecondaryMediaCodec(holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            releaseSecondaryMediaCodec();
        }
    }
}
