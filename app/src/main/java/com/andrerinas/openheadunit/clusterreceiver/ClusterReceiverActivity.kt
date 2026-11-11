package com.andrerinas.openheadunit.clusterreceiver

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Companion Spare Phone Instrument Cluster Receiver (`ClusterReceiverActivity.kt`)
 *
 * Connects to the Open Headunit TCP Annex-B Cluster Relay on `<HEADUNIT_IP>:5288`,
 * parses H.264 Annex-B start codes (`00 00 00 01`), feeds complete Access Units into
 * a low-latency hardware `MediaCodec.createDecoderByType("video/avc")`, and renders
 * full-screen button-free Waze / Google Maps in immersive landscape mode with auto-reconnect.
 */
class ClusterReceiverActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ClusterReceiverActivity"
        const val EXTRA_HEADUNIT_IP = "headunit_ip"
        const val EXTRA_HEADUNIT_PORT = "headunit_port"
        const val DEFAULT_HEADUNIT_IP = "192.168.43.1"
        const val DEFAULT_CLUSTER_PORT = 5288
        private const val STREAM_WIDTH = 1280
        private const val STREAM_HEIGHT = 720
        private const val RECONNECT_DELAY_MS = 1000L
    }

    private lateinit var surfaceView: SurfaceView
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var headunitIp: String = DEFAULT_HEADUNIT_IP
    private var headunitPort: Int = DEFAULT_CLUSTER_PORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        headunitIp = intent?.getStringExtra(EXTRA_HEADUNIT_IP) ?: DEFAULT_HEADUNIT_IP
        headunitPort = intent?.getIntExtra(EXTRA_HEADUNIT_PORT, DEFAULT_CLUSTER_PORT) ?: DEFAULT_CLUSTER_PORT

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        enableImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running.set(true)
        val surface = holder.surface
        workerThread = Thread({ runAutoReconnectLoop(surface) }, "ClusterReceiver-5288").apply {
            isDaemon = true
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running.set(false)
        workerThread?.interrupt()
        workerThread = null
    }

    private fun runAutoReconnectLoop(surface: Surface) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var socket: Socket? = null
            var codec: MediaCodec? = null
            try {
                Log.i(TAG, "Connecting to head unit cluster stream at $headunitIp:$headunitPort ...")
                socket = Socket()
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 256 * 1024
                socket.connect(InetSocketAddress(headunitIp, headunitPort), 2500)
                Log.i(TAG, "Connected to $headunitIp:$headunitPort — starting H.264 Annex-B hardware decode")

                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    STREAM_WIDTH,
                    STREAM_HEIGHT
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                }
                codec.configure(format, surface, null, 0)
                codec.start()

                streamAnnexBToCodec(socket.getInputStream(), codec)
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Cluster stream interrupted (${e.message}), retrying in ${RECONNECT_DELAY_MS}ms...")
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
            }

            if (running.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun streamAnnexBToCodec(input: InputStream, codec: MediaCodec) {
        val readBuf = ByteArray(65536)
        val accum = ByteArrayOutputStream(262144)
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get()) {
            val n = input.read(readBuf)
            if (n < 0) throw java.io.EOFException("Cluster TCP socket closed by head unit")
            if (n == 0) continue

            accum.write(readBuf, 0, n)
            val bytes = accum.toByteArray()

            var nalStart = findStartCode(bytes, 0)
            if (nalStart < 0) continue

            while (true) {
                val nextStart = findStartCode(bytes, nalStart + 4)
                if (nextStart < 0) break

                val nalUnit = bytes.copyOfRange(nalStart, nextStart)
                feedNalToDecoder(codec, nalUnit, bufferInfo)
                nalStart = nextStart
            }

            accum.reset()
            accum.write(bytes, nalStart, bytes.size - nalStart)
        }
    }

    private fun findStartCode(data: ByteArray, fromIndex: Int): Int {
        var i = fromIndex
        val limit = data.size - 4
        while (i <= limit) {
            if (data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 &&
                data[i + 3].toInt() == 1
            ) {
                return i
            }
            i++
        }
        return -1
    }

    @Suppress("DEPRECATION")
    private fun feedNalToDecoder(
        codec: MediaCodec,
        nalUnit: ByteArray,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        if (nalUnit.size <= 4) return
        val nalType = nalUnit[4].toInt() and 0x1F
        val isConfig = (nalType == 7 || nalType == 8)

        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getInputBuffer(inIdx)
            } else {
                codec.inputBuffers[inIdx]
            }
            inBuf?.clear()
            inBuf?.put(nalUnit)
            val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            codec.queueInputBuffer(inIdx, 0, nalUnit.size, System.nanoTime() / 1000, flags)
        }

        var outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outIdx >= 0) {
            codec.releaseOutputBuffer(outIdx, true)
            outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }
}
