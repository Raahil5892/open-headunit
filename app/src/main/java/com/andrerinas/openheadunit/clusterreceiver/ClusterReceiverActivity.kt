package com.andrerinas.openheadunit.clusterreceiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Companion Spare Phone Instrument Cluster Receiver (`ClusterReceiverActivity.kt`)
 *
 * Connects to the Open Headunit TCP Annex-B Cluster Relay on `<HEADUNIT_IP>:5289`,
 * auto-detects the Wi-Fi hotspot gateway IP via `ConnectivityManager.getLinkProperties()` /
 * `WifiManager.dhcpInfo.gateway`, displays a floating status overlay that auto-hides on the
 * first decoded H.264 frame, opens an IP/Port + Stretch/Fit configuration dialog on single-tap
 * or long-press, and stretches across camera notches on 20:9 phones (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`).
 */
class ClusterReceiverActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ClusterReceiverActivity"
        const val EXTRA_HEADUNIT_IP = "headunit_ip"
        const val EXTRA_HEADUNIT_PORT = "headunit_port"
        const val DEFAULT_HEADUNIT_IP = "192.168.43.1"
        const val DEFAULT_CLUSTER_PORT = 5289
        private const val STREAM_WIDTH = 1280
        private const val STREAM_HEIGHT = 720
        private const val RECONNECT_DELAY_MS = 1000L

        private const val PREFS_NAME = "cluster_receiver_prefs"
        private const val PREF_SAVED_IP = "saved_headunit_ip"
        private const val PREF_SAVED_PORT = "saved_headunit_port"
        private const val PREF_USER_SET_IP = "user_set_headunit_ip"
        private const val PREF_SCALE_STRETCH = "scale_mode_stretch"
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: TextView
    private lateinit var gestureDetector: GestureDetector

    private val running = AtomicBoolean(false)
    private val firstFrameRendered = AtomicBoolean(false)
    private var workerThread: Thread? = null
    @Volatile private var activeSocket: Socket? = null

    @Volatile private var headunitIp: String = DEFAULT_HEADUNIT_IP
    @Volatile private var headunitPort: Int = DEFAULT_CLUSTER_PORT
    @Volatile private var stretchToFullScreen: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        loadEndpointConfig()
        buildUi()
        enableImmersiveFullscreen()
    }

    private fun loadEndpointConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        stretchToFullScreen = prefs.getBoolean(PREF_SCALE_STRETCH, true)

        val intentIp = intent?.getStringExtra(EXTRA_HEADUNIT_IP)?.trim()
        val intentPort = intent?.getIntExtra(EXTRA_HEADUNIT_PORT, -1) ?: -1

        val autoGatewayIp = detectGatewayIp()
        val userSetIp = prefs.getBoolean(PREF_USER_SET_IP, false)
        val savedIp = prefs.getString(PREF_SAVED_IP, null)?.trim()

        headunitIp = when {
            !intentIp.isNullOrEmpty() -> intentIp
            userSetIp && !savedIp.isNullOrEmpty() -> savedIp
            else -> autoGatewayIp
        }

        val savedPort = prefs.getInt(PREF_SAVED_PORT, DEFAULT_CLUSTER_PORT)
        headunitPort = when {
            intentPort in 1025..65535 -> intentPort
            savedPort in 1025..65535 && savedPort != 5288 -> savedPort
            else -> DEFAULT_CLUSTER_PORT
        }
    }

    private fun detectGatewayIp(): String {
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = buildList {
                    cm.activeNetwork?.let { add(it) }
                    @Suppress("DEPRECATION")
                    cm.allNetworks.forEach { if (!contains(it)) add(it) }
                }
                for (network in networks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    for (route in linkProps.routes) {
                        val gw = route.gateway
                        if (gw is Inet4Address && !gw.isLoopbackAddress && !gw.isAnyLocalAddress) {
                            val host = gw.hostAddress
                            if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager gateway detection failed: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                val detected = String.format(
                    "%d.%d.%d.%d",
                    g and 0xff,
                    (g shr 8) and 0xff,
                    (g shr 16) and 0xff,
                    (g shr 24) and 0xff
                )
                if (detected != "0.0.0.0") {
                    return detected
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager dhcpInfo gateway detection failed: ${e.message}")
        }

        return DEFAULT_HEADUNIT_IP
    }

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        rootLayout.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(24f).toFloat()
            setColor(Color.parseColor("#CC12161F"))
            setStroke(dpToPx(1.5f), Color.parseColor("#664FC3F7"))
        }

        statusOverlay = TextView(this).apply {
            background = pillBg
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val padH = dpToPx(22f)
            val padV = dpToPx(12f)
            setPadding(padH, padV, padH, padV)
            text = formatOverlayMessage("Connecting to Cluster at $headunitIp:$headunitPort...")
            setOnClickListener { showEndpointConfigDialog() }
        }

        val overlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dpToPx(28f)
            leftMargin = dpToPx(24f)
            rightMargin = dpToPx(24f)
        }
        rootLayout.addView(statusOverlay, overlayParams)

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    statusOverlay.visibility = View.VISIBLE
                    showEndpointConfigDialog()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    statusOverlay.visibility = View.VISIBLE
                    showEndpointConfigDialog()
                }
            }
        )

        val touchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        rootLayout.setOnTouchListener(touchListener)
        surfaceView.setOnTouchListener(touchListener)

        rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applySurfaceScaleMode()
        }

        setContentView(rootLayout)
        applySurfaceScaleMode()
    }

    private fun applySurfaceScaleMode() {
        val parentW = rootLayout.width
        val parentH = rootLayout.height
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (stretchToFullScreen || parentW <= 0 || parentH <= 0) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val targetAspect = STREAM_WIDTH.toFloat() / STREAM_HEIGHT.toFloat()
            val screenAspect = parentW.toFloat() / parentH.toFloat()
            if (screenAspect > targetAspect) {
                lp.height = parentH
                lp.width = (parentH * targetAspect).toInt()
            } else {
                lp.width = parentW
                lp.height = (parentW / targetAspect).toInt()
            }
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    private fun formatOverlayMessage(prefix: String): String =
        "$prefix Tap screen to change IP"

    private fun updateOverlayStatus(prefix: String, forceVisible: Boolean = false) {
        runOnUiThread {
            statusOverlay.text = formatOverlayMessage(prefix)
            if (forceVisible || !firstFrameRendered.get()) {
                statusOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun showEndpointConfigDialog() {
        val detectedGw = detectGatewayIp()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20f)
            setPadding(pad, dpToPx(12f), pad, dpToPx(4f))
        }

        val gwHint = TextView(this).apply {
            text = "Auto-detected Wi-Fi Gateway: $detectedGw:$DEFAULT_CLUSTER_PORT"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 0, 0, dpToPx(10f))
        }
        container.addView(gwHint)

        val ipLabel = TextView(this).apply {
            text = "Head Unit IP Address"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        container.addView(ipLabel)

        val ipInput = EditText(this).apply {
            setText(headunitIp)
            hint = detectedGw
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        container.addView(ipInput)

        val portLabel = TextView(this).apply {
            text = "Cluster Stream TCP Port (Default: $DEFAULT_CLUSTER_PORT)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpToPx(8f), 0, 0)
        }
        container.addView(portLabel)

        val portInput = EditText(this).apply {
            setText(headunitPort.toString())
            hint = DEFAULT_CLUSTER_PORT.toString()
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        container.addView(portInput)

        val scaleLabel = TextView(this).apply {
            text = "Display Aspect & Cutout Scaling"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dpToPx(10f), 0, dpToPx(4f))
        }
        container.addView(scaleLabel)

        val scaleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbStretch = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Stretch (Full 20:9)"
        }
        val rbFit = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Fit (16:9 Aspect)"
        }
        scaleGroup.addView(rbStretch)
        scaleGroup.addView(rbFit)
        scaleGroup.check(if (stretchToFullScreen) rbStretch.id else rbFit.id)
        container.addView(scaleGroup)

        AlertDialog.Builder(this)
            .setTitle("Virtual Cockpit Cluster Connection")
            .setView(container)
            .setPositiveButton("Save & Reconnect") { _, _ ->
                val newIp = ipInput.text?.toString()?.trim().orEmpty().ifEmpty { detectedGw }
                val newPort = portInput.text?.toString()?.trim()?.toIntOrNull()
                    ?.takeIf { it in 1025..65535 } ?: DEFAULT_CLUSTER_PORT
                val newStretch = scaleGroup.checkedRadioButtonId == rbStretch.id

                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_SAVED_IP, newIp)
                    .putInt(PREF_SAVED_PORT, newPort)
                    .putBoolean(PREF_USER_SET_IP, true)
                    .putBoolean(PREF_SCALE_STRETCH, newStretch)
                    .apply()

                headunitIp = newIp
                headunitPort = newPort
                stretchToFullScreen = newStretch
                applySurfaceScaleMode()
                restartActiveConnection()
                enableImmersiveFullscreen()
            }
            .setNeutralButton("Use Auto-Gateway ($detectedGw)") { _, _ ->
                val newStretch = scaleGroup.checkedRadioButtonId == rbStretch.id
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_SAVED_IP, detectedGw)
                    .putInt(PREF_SAVED_PORT, DEFAULT_CLUSTER_PORT)
                    .putBoolean(PREF_USER_SET_IP, false)
                    .putBoolean(PREF_SCALE_STRETCH, newStretch)
                    .apply()

                headunitIp = detectedGw
                headunitPort = DEFAULT_CLUSTER_PORT
                stretchToFullScreen = newStretch
                applySurfaceScaleMode()
                restartActiveConnection()
                enableImmersiveFullscreen()
            }
            .setNegativeButton("Exit to Head Unit Home") { _, _ ->
                startActivity(Intent(this, com.andrerinas.openheadunit.main.MainActivity::class.java))
                finish()
            }
            .setOnDismissListener {
                enableImmersiveFullscreen()
            }
            .show()
    }

    private fun restartActiveConnection() {
        firstFrameRendered.set(false)
        updateOverlayStatus("Connecting to Cluster at $headunitIp:$headunitPort...", forceVisible = true)
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
        workerThread = Thread({ runAutoReconnectLoop(surface) }, "ClusterReceiver-$DEFAULT_CLUSTER_PORT").apply {
            isDaemon = true
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applySurfaceScaleMode()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running.set(false)
        try { activeSocket?.close() } catch (_: Exception) {}
        workerThread?.interrupt()
        workerThread = null
    }

    private fun runAutoReconnectLoop(surface: Surface) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var socket: Socket? = null
            var codec: MediaCodec? = null
            val targetIp = headunitIp
            val targetPort = headunitPort
            try {
                firstFrameRendered.set(false)
                updateOverlayStatus("Connecting to Cluster at $targetIp:$targetPort...", forceVisible = true)
                Log.i(TAG, "Connecting to head unit cluster stream at $targetIp:$targetPort ...")

                socket = Socket()
                activeSocket = socket
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 256 * 1024
                socket.connect(InetSocketAddress(targetIp, targetPort), 2500)
                Log.i(TAG, "Connected to $targetIp:$targetPort — starting H.264 Annex-B hardware decode")
                updateOverlayStatus("Connected to $targetIp:$targetPort — Awaiting cluster video...", forceVisible = false)

                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    STREAM_WIDTH,
                    STREAM_HEIGHT
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                codec.configure(format, surface, null, 0)
                codec.start()

                streamAnnexBToCodec(socket.getInputStream(), codec)
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Cluster stream interrupted (${e.message}), retrying in ${RECONNECT_DELAY_MS}ms...")
                    firstFrameRendered.set(false)
                    updateOverlayStatus("Connecting to Cluster at $headunitIp:$headunitPort...", forceVisible = true)
                }
            } finally {
                activeSocket = null
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
            val inBuf = codec.getInputBuffer(inIdx)
            inBuf?.clear()
            inBuf?.put(nalUnit)
            val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            codec.queueInputBuffer(inIdx, 0, nalUnit.size, System.nanoTime() / 1000, flags)
        }

        var outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outIdx >= 0) {
            codec.releaseOutputBuffer(outIdx, true)
            if (firstFrameRendered.compareAndSet(false, true)) {
                runOnUiThread {
                    statusOverlay.visibility = View.GONE
                }
            }
            outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }
}
