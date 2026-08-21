package com.cath.screencast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.view.Display
import androidx.core.app.NotificationCompat
import com.cath.screencast.MainActivity
import com.cath.screencast.encoder.AudioEncoder
import com.cath.screencast.encoder.VideoEncoder
import com.cath.screencast.log.AppLogger
import com.cath.screencast.model.BitrateMode
import com.cath.screencast.model.DeviceUtils
import com.cath.screencast.model.EyeCrop
import com.cath.screencast.model.StreamConfig
import com.cath.screencast.model.StreamStats
import com.cath.screencast.model.VideoCodec
import com.cath.screencast.model.VideoResolution
import com.cath.screencast.model.TransportProtocol
import com.cath.screencast.net.IStreamer
import com.cath.screencast.net.RttProbe
import com.cath.screencast.net.TcpStreamer
import com.cath.screencast.net.UdpFecStreamer

class QuestSenderService : Service() {

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var streamer: IStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val rttProbe = RttProbe()
    @Volatile
    private var latestRttStats: RttProbe.ProbeStats? = null

    @Volatile
    var isStreaming = false
        private set(value) {
            field = value
            onStreamingStateChanged?.invoke(value)
        }

    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    private var savedConfig: StreamConfig? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRotation = 0
    private var currentEncoderWidth = 0
    private var currentEncoderHeight = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    var onStatsUpdate: ((StreamStats) -> Unit)? = null
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): QuestSenderService = this@QuestSenderService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.i(TAG, "QuestSenderService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.i(TAG, "Received ACTION_STOP command in QuestSenderService.")
            stopStreaming()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val resWidth = intent?.getIntExtra(EXTRA_RES_WIDTH, 0) ?: 0
        val resHeight = intent?.getIntExtra(EXTRA_RES_HEIGHT, 0) ?: 0
        val eyeCropName = intent?.getStringExtra(EXTRA_EYE_CROP) ?: EyeCrop.BOTH.name
        val codecName = intent?.getStringExtra(EXTRA_CODEC) ?: VideoCodec.H265.name
        val bitrateModeName = intent?.getStringExtra(EXTRA_BITRATE_MODE) ?: BitrateMode.VBR.name
        val protocolName = intent?.getStringExtra(EXTRA_PROTOCOL) ?: TransportProtocol.UDP.name
        val crop = try { EyeCrop.valueOf(eyeCropName) } catch (e: Exception) { EyeCrop.BOTH }
        val codec = try { VideoCodec.valueOf(codecName) } catch (e: Exception) { VideoCodec.H265 }
        val bitrateMode = try { BitrateMode.valueOf(bitrateModeName) } catch (e: Exception) { BitrateMode.VBR }
        val protocol = try { TransportProtocol.valueOf(protocolName) } catch (e: Exception) { TransportProtocol.UDP }
        val resEnum = if (resWidth == 0 || resHeight == 0) {
            VideoResolution.DEFAULT
        } else {
            VideoResolution("${resWidth}x${resHeight}", resWidth, resHeight)
        }

        val config = StreamConfig(
            targetIp = intent?.getStringExtra(EXTRA_TARGET_IP) ?: "192.168.1.100",
            targetPort = intent?.getIntExtra(EXTRA_TARGET_PORT, 8888) ?: 8888,
            bitrateKbps = intent?.getIntExtra(EXTRA_BITRATE, 16000) ?: 16000,
            bitrateMode = bitrateMode,
            frameRate = intent?.getIntExtra(EXTRA_FRAMERATE, 0) ?: 0,
            resolution = resEnum,
            eyeCrop = crop,
            codec = codec,
            protocol = protocol
        )

        AppLogger.i(TAG, "onStartCommand: resultCode=$resultCode, Target=${config.targetIp}:${config.targetPort}, Bitrate=${config.bitrateKbps}K, Codec=${config.codec.name}")

        if (resultCode != 0 && resultData != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            startStreaming(resultCode, resultData, config)
        } else {
            AppLogger.e(TAG, "Cannot start streaming: Invalid resultCode ($resultCode) or null resultData")
        }

        return START_STICKY
    }

    private fun getRealDisplayMetrics(): Pair<Int, Int> {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val dm = android.util.DisplayMetrics()
            display.getRealMetrics(dm)
            return Pair(dm.widthPixels, dm.heightPixels)
        }
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun getDisplayRotation(): Int {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Display.DEFAULT_DISPLAY
    }

    private fun computeEffectiveResolution(config: StreamConfig, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val rawWidth = if (config.resolution.width > 0) config.resolution.width else screenWidth
        val rawHeight = if (config.resolution.height > 0) config.resolution.height else screenHeight

        var effWidth: Int
        var effHeight: Int

        if (DeviceUtils.isQuestDevice) {
            // For Quest devices (VR headset), the capture FOV is determined by user selection (1:1, 16:9, 2:1)
            // and must NOT be inverted based on the 2D window's display metrics.
            effWidth = if (config.resolution.width > 0) config.resolution.width else 1920
            effHeight = if (config.resolution.height > 0) config.resolution.height else 1080
        } else if (rawWidth == rawHeight) {
            // If it's a 1:1 aspect ratio (e.g. square crop), keep width == height
            effWidth = rawWidth
            effHeight = rawHeight
        } else {
            val isPhysicalLandscape = screenWidth >= screenHeight
            if (isPhysicalLandscape) {
                effWidth = maxOf(rawWidth, rawHeight)
                effHeight = minOf(rawWidth, rawHeight)
            } else {
                effWidth = minOf(rawWidth, rawHeight)
                effHeight = maxOf(rawWidth, rawHeight)
            }
        }

        // Align to 16 pixels
        effWidth = ((effWidth / 16) * 16).coerceAtLeast(128)
        effHeight = ((effHeight / 16) * 16).coerceAtLeast(128)

        // Query capabilities and clamp dynamically to avoid crash
        val caps = com.cath.screencast.model.EncoderCapabilities.query(config.codec, config.bitrateMode)
        if (effWidth > caps.maxWidth) {
            val clampedWidth = (caps.maxWidth / 16) * 16
            AppLogger.w(TAG, "Width $effWidth exceeds encoder capability maximum ${caps.maxWidth}, clamping to $clampedWidth")
            effWidth = clampedWidth
        }
        if (effHeight > caps.maxHeight) {
            val clampedHeight = (caps.maxHeight / 16) * 16
            AppLogger.w(TAG, "Height $effHeight exceeds encoder capability maximum ${caps.maxHeight}, clamping to $clampedHeight")
            effHeight = clampedHeight
        }

        return Pair(effWidth, effHeight)
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, config: StreamConfig) {
        stopStreaming()

        savedResultCode = resultCode
        savedResultData = resultData
        savedConfig = config

        val (screenWidth, screenHeight) = getRealDisplayMetrics()
        lastWidth = screenWidth
        lastHeight = screenHeight
        lastRotation = getDisplayRotation()

        AppLogger.i(TAG, "Requesting MediaProjection from projectionManager...")

        // Acquire WakeLock and Full Low-Latency WifiLock to prevent Qualcomm Wi-Fi chip from entering power-save DTIM sleep
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenLiveStream:SenderWakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ScreenLiveStream:SenderWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenLiveStream:SenderWifiLock")
            }
            wifiLock?.acquire()
            AppLogger.i(TAG, "Acquired WakeLock and Low-Latency WifiLock successfully.")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire WakeLock or WifiLock: ${e.message}")
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            AppLogger.e(TAG, "MediaProjection is null! Permission denied or token expired.")
            return
        }
        mediaProjection = projection

        // Register callback for projection stop
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AppLogger.w(TAG, "MediaProjection onStop triggered by system or user.")
                val stopIntent = Intent(this@QuestSenderService, QuestSenderService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stopIntent)
            }
        }, null)

        streamer = if (config.protocol == TransportProtocol.TCP) {
            TcpStreamer()
        } else {
            UdpFecStreamer()
        }
        val currentStreamer = streamer!!

        currentStreamer.onRequestKeyframe = {
            encoder?.requestKeyFrame()
        }
        rttProbe.onIdrRequest = {
            AppLogger.i(TAG, "Instant IDR requested via RTT control channel! Forcing keyframe...")
            encoder?.requestKeyFrame()
        }
        currentStreamer.start(config.targetIp, config.targetPort)
        // RTT probe uses its own UDP socket (separate from streamer
        // so media traffic and probe traffic don't compete for buffers).
        // Hits the same targetIp:port — receiver's PacketProtocol.read
        // path sniffs the FLAG_PING bit and bounces it back.
        rttProbe.onStats = { stats ->
            latestRttStats = stats
            // Periodic log dump so we can correlate RTT/loss with
            // visible artifacts on the sender side. Tag is "RttProbe"
            // so it can be filtered with `adb logcat -s RttProbe`.
            AppLogger.i(
                "RttProbe",
                "rtt=${stats.lastRttMs}ms avg=${stats.avgRttMs}ms p95=${stats.p95RttMs}ms " +
                    "jitter=${stats.jitterMs}ms loss=${"%.1f".format(stats.lossPercent)}% " +
                    "(sent=${stats.sentCount} lost=${stats.lostCount})"
            )
            // Mirror RTT into the StreamStats callback so the sender UI
            // (when it grows stats) can read the same field names.
            // We publish avgRttMs (not lastRttMs) because the *last*
            // sample is dominated by cold-start jitter for the first
            // ~6 windows after start(); avgRttMs is the steady-state
            // value the user actually wants to see on the HUD.
            val publishedRttMs = if (stats.avgRttMs > 0) stats.avgRttMs else stats.lastRttMs
            onStatsUpdate?.invoke(
                StreamStats(
                    isStreaming = true,
                    isReceiving = true,
                    latencyMs = publishedRttMs.toLong(),
                    lossNetworkPercent = 0f,
                    statsTimestampMs = System.currentTimeMillis()
                )
            )
            // Broadcast rolling RTT and 0.0% loss (TCP is loss-free) to receiver HUD
            currentStreamer.sendStatsBeacon(publishedRttMs, 0f)
        }
        rttProbe.start(config.targetIp, config.targetPort)

        val (effWidth, effHeight) = computeEffectiveResolution(config, screenWidth, screenHeight)

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val effFps = if (config.frameRate > 0) {
            config.frameRate
        } else {
            defaultDisplay?.refreshRate?.toInt() ?: 90
        }

        // Apply Resolution & FPS to System Properties for Quest
        setSystemProperty("debug.oculus.screenCaptureEye", config.eyeCrop.sysPropValue.toString())
        setSystemProperty("debug.oculus.capture.width", effWidth.toString())
        setSystemProperty("debug.oculus.capture.height", effHeight.toString())
        setSystemProperty("debug.oculus.fullRateCapture", "1")
        if (config.frameRate > 0) {
            setSystemProperty("debug.oculus.capture.fps", effFps.toString())
        }
        setSystemProperty("debug.oculus.capture.bitrate", (config.bitrateKbps * 1000).toString())

        AppLogger.i(TAG, "Stream parameters computed: $effWidth x $effHeight @ $effFps FPS (Screen: ${screenWidth}x${screenHeight}, Rotation: $lastRotation)")

        val enc = VideoEncoder(
            config = config,
            tcpStreamer = currentStreamer,
            overrideWidth = effWidth,
            overrideHeight = effHeight,
            overrideFps = effFps,
            context = this
        )
        enc.start()
        encoder = enc
        currentEncoderWidth = effWidth
        currentEncoderHeight = effHeight

        // Start System Internal Audio Encoder (AudioPlaybackCaptureConfiguration)
        try {
            val audioEnc = AudioEncoder(
                mediaProjection = projection,
                tcpStreamer = currentStreamer
            )
            enc.onFirstFrameCaptured = {
                audioEnc.videoStartPtsUs = enc.firstFramePtsUs
                audioEnc.videoStartRealNs = enc.firstFrameRealNs
                AppLogger.i(
                    TAG,
                    "Audio encoder rebased to video anchor: " +
                        "videoStartPtsUs=${enc.firstFramePtsUs}, " +
                        "videoStartRealNs=${enc.firstFrameRealNs}"
                )
            }
            audioEnc.start()
            audioEncoder = audioEnc
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start internal AudioEncoder", e)
        }

        val surface = enc.inputSurface
        if (surface == null) {
            AppLogger.e(TAG, "Encoder inputSurface is null! Cannot create VirtualDisplay.")
            return
        }

        val dm = android.util.DisplayMetrics()
        defaultDisplay?.getRealMetrics(dm)
        val densityDpi = if (dm.densityDpi > 0) dm.densityDpi else resources.displayMetrics.densityDpi

        AppLogger.i(TAG, "Creating VirtualDisplay with resolution $effWidth x $effHeight, DPI $densityDpi")
        virtualDisplay = projection.createVirtualDisplay(
            "Quest3ScreenCast",
            effWidth,
            effHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            surface,
            null,
            null
        )

        // Register DisplayListener for orientation/resolution change sensing
        val dispMgr = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    checkScreenSizeChange()
                }
            }
        }
        dispMgr?.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        displayListener = listener

        isStreaming = true
        AppLogger.i(TAG, "QuestSenderService streaming started successfully.")
    }

    fun stopStreaming() {
        AppLogger.i(TAG, "stopStreaming requested.")
        isStreaming = false

        displayListener?.let { listener ->
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            try {
                displayManager?.unregisterDisplayListener(listener)
                AppLogger.i(TAG, "Display listener unregistered.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error unregistering display listener", e)
            }
        }
        displayListener = null
        try {
            virtualDisplay?.release()
            AppLogger.i(TAG, "VirtualDisplay released.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null

        try {
            audioEncoder?.stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping audioEncoder", e)
        }
        audioEncoder = null

        try {
            encoder?.stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping encoder", e)
        }
        encoder = null
        currentEncoderWidth = 0
        currentEncoderHeight = 0

        try {
            streamer?.sendStreamStopSignal()
            streamer?.stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping streamer", e)
        }
        streamer = null

        try {
            rttProbe.stop()
            AppLogger.i(TAG, "rttProbe stopped.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping rttProbe", e)
        }

        try {
            mediaProjection?.stop()
            AppLogger.i(TAG, "MediaProjection stopped.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        AppLogger.i(TAG, "QuestSenderService streaming stopped completed.")
    }

    private fun checkScreenSizeChange() {
        if (!isStreaming) return
        // Quest 3 is a VR headset. The VR compositor stream resolution is determined
        // by debug.oculus.capture.width / height and does not change when the 2D window is resized/moved.
        if (DeviceUtils.isQuestDevice) return
        val config = savedConfig ?: return

        val (currentScreenWidth, currentScreenHeight) = getRealDisplayMetrics()
        val currentRotation = getDisplayRotation()

        if (currentScreenWidth != lastWidth || currentScreenHeight != lastHeight || currentRotation != lastRotation) {
            AppLogger.i(TAG, "Screen rotation/size change detected: from ${lastWidth}x${lastHeight} (rot=$lastRotation) to ${currentScreenWidth}x${currentScreenHeight} (rot=$currentRotation)")
            lastWidth = currentScreenWidth
            lastHeight = currentScreenHeight
            lastRotation = currentRotation

            recreateEncoderAndVirtualDisplay(currentScreenWidth, currentScreenHeight)
        }
    }

    private fun recreateEncoderAndVirtualDisplay(screenWidth: Int, screenHeight: Int) {
        val config = savedConfig ?: return
        val projection = mediaProjection ?: return

        val (effWidth, effHeight) = computeEffectiveResolution(config, screenWidth, screenHeight)

        if (effWidth == currentEncoderWidth && effHeight == currentEncoderHeight) {
            AppLogger.i(TAG, "Effective resolution $effWidth x $effHeight unchanged on rotation event.")
            return
        }

        AppLogger.i(TAG, "Recreating VideoEncoder for new screen dimensions ${screenWidth}x${screenHeight} -> target resolution ${effWidth}x${effHeight}...")

        val oldEncoder = encoder

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val effFps = if (config.frameRate > 0) {
            config.frameRate
        } else {
            defaultDisplay?.refreshRate?.toInt() ?: 90
        }

        AppLogger.i(TAG, "Recreating stream parameters: $effWidth x $effHeight @ $effFps FPS, BitrateMode: ${config.bitrateMode.name}, Bitrate: ${config.bitrateKbps}Kbps")

        // 1. Initialize and start new VideoEncoder
        val currentStreamer = streamer ?: return
        val enc = VideoEncoder(
            config = config,
            tcpStreamer = currentStreamer,
            overrideWidth = effWidth,
            overrideHeight = effHeight,
            overrideFps = effFps,
            context = this
        )
        try {
            enc.start()
            encoder = enc
            currentEncoderWidth = effWidth
            currentEncoderHeight = effHeight
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start new VideoEncoder", e)
            stopStreaming()
            return
        }

        // 2. Update VirtualDisplay with new input surface without releasing MediaProjection token
        val surface = enc.inputSurface
        if (surface == null) {
            AppLogger.e(TAG, "New Encoder inputSurface is null! Cannot update VirtualDisplay.")
            stopStreaming()
            return
        }

        val dm = android.util.DisplayMetrics()
        defaultDisplay?.getRealMetrics(dm)
        val densityDpi = if (dm.densityDpi > 0) dm.densityDpi else resources.displayMetrics.densityDpi

        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "Quest3ScreenCast",
                effWidth,
                effHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                surface,
                null,
                null
            )
        } else {
            virtualDisplay?.setSurface(surface)
            virtualDisplay?.resize(effWidth, effHeight, densityDpi)
        }
        AppLogger.i(TAG, "VirtualDisplay updated successfully with size: $effWidth x $effHeight, DPI $densityDpi.")

        // 3. Stop old VideoEncoder safely after new surface is connected
        try {
            oldEncoder?.stop()
            AppLogger.i(TAG, "Old VideoEncoder stopped.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping old VideoEncoder", e)
        }

        // 4. Request keyframe for immediate video output
        enc.requestKeyFrame()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.i(TAG, "onConfigurationChanged triggered: orientation=${newConfig.orientation}")
        checkScreenSizeChange()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QuestCast Screen Sender Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, QuestSenderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quest 3 画面投屏中")
            .setContentText("正在低延迟推流画面至接收端...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止投屏", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setSystemProperty(key: String, value: String) {
        try {
            val process = Runtime.getRuntime().exec("setprop $key $value")
            process.waitFor()
            AppLogger.i(TAG, "Successfully executed setprop for $key: $value")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to execute setprop. Attempting reflection fallback for $key.", e)
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val setMethod = clazz.getMethod("set", String::class.java, String::class.java)
                setMethod.invoke(null, key, value)
                AppLogger.i(TAG, "Successfully set $key via reflection: $value")
            } catch (ex: Exception) {
                AppLogger.e(TAG, "Reflection fallback failed for $key", ex)
            }
        }
    }

    companion object {
        private const val TAG = "QuestSenderService"
        private const val CHANNEL_ID = "quest_sender_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.cath.screencast.service.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_TARGET_IP = "EXTRA_TARGET_IP"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"
        const val EXTRA_FRAMERATE = "EXTRA_FRAMERATE"
        const val EXTRA_RES_WIDTH = "EXTRA_RES_WIDTH"
        const val EXTRA_RES_HEIGHT = "EXTRA_RES_HEIGHT"
        const val EXTRA_EYE_CROP = "EXTRA_EYE_CROP"
        const val EXTRA_CODEC = "EXTRA_CODEC"
        const val EXTRA_BITRATE_MODE = "EXTRA_BITRATE_MODE"
        const val EXTRA_PROTOCOL = "EXTRA_PROTOCOL"
    }
}
