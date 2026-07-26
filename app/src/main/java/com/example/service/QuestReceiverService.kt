package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.example.log.AppLogger
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.decoder.AudioDecoder
import com.example.decoder.VideoDecoder
import com.example.model.StreamStats
import com.example.model.TransportProtocol
import com.example.net.IReceiver
import com.example.net.LanDiscovery
import com.example.net.TcpReceiver
import com.example.net.UdpFecReceiver

class QuestReceiverService : Service() {

    private val binder = LocalBinder()
    private var receiver: IReceiver? = null
    val videoDecoder = VideoDecoder()
    val audioDecoder = AudioDecoder()
    val lanDiscovery = LanDiscovery()
    private var muxerManager: com.example.encoder.MuxerManager? = null
    @Volatile
    private var recordingStartNs: Long = 0L

    @Volatile
    var isListening = false
        private set

    @Volatile
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    var currentSurface: Surface? = null
        private set

    var onStatsUpdated: ((StreamStats) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null

    private var lastPort = 8888
    private var lastAutoAnnounce = true
    private var lastJitterBufferMs = 50
    private var lastProtocol = TransportProtocol.UDP
    
    private var lastStreamStopMs = 0L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var hasReceivedFirstFrame = false
    @Volatile
    private var lastDataFrameTimeMs = 0L
    @Volatile
    private var isTimeoutCountdownActive = false
    @Volatile
    private var timeoutStartTimeMs = 0L
    
    private var latestStats = StreamStats()

    private val timeoutCheckRunnable = object : Runnable {
        override fun run() {
            if (!isListening) return

            val now = System.currentTimeMillis()

            if (hasReceivedFirstFrame) {
                val timeSinceLastData = now - lastDataFrameTimeMs

                if (timeSinceLastData >= STREAM_STALL_THRESHOLD_MS || isTimeoutCountdownActive) {
                    if (!isTimeoutCountdownActive) {
                        isTimeoutCountdownActive = true
                        timeoutStartTimeMs = if (lastDataFrameTimeMs > 0) lastDataFrameTimeMs else now
                        AppLogger.w(TAG, "Stream stalled (no data for ${timeSinceLastData}ms). Started 20s disconnect countdown.")
                    }

                    val elapsedTimeout = now - timeoutStartTimeMs
                    val remainingSec = ((TIMEOUT_DURATION_MS - elapsedTimeout) / 1000L).coerceAtLeast(0).toInt()

                    if (elapsedTimeout >= TIMEOUT_DURATION_MS) {
                        AppLogger.e(TAG, "Stream lost for 20 seconds. Considering offline and stopping listening.")
                        stopListening()
                        return
                    } else {
                        val updatedStats = latestStats.copy(
                            isTimeoutCounting = true,
                            timeoutRemainingSec = remainingSec
                        )
                        onStatsUpdated?.invoke(updatedStats)
                    }
                } else {
                    if (isTimeoutCountdownActive) {
                        isTimeoutCountdownActive = false
                        AppLogger.i(TAG, "Stream data resumed. Cancelled 20s timeout timer.")
                    }
                }
            }

            mainHandler.postDelayed(this, 1000L)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): QuestReceiverService = this@QuestReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        videoDecoder.onVideoSizeChanged = { w, h ->
            onVideoSizeChanged?.invoke(w, h)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_LISTEN_PORT, 8888) ?: 8888
        val autoAnnounce = intent?.getBooleanExtra(EXTRA_AUTO_ANNOUNCE, true) ?: true
        val jitterBufferMs = intent?.getIntExtra(EXTRA_JITTER_BUFFER_MS, 50) ?: 50
        val protocolName = intent?.getStringExtra(EXTRA_PROTOCOL) ?: TransportProtocol.UDP.name
        val protocol = try { TransportProtocol.valueOf(protocolName) } catch (e: Exception) { TransportProtocol.UDP }
        val isRecordEnabled = intent?.getBooleanExtra(EXTRA_RECORD_ENABLED, false) ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(port),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(port))
        }

        startListening(port, autoAnnounce, jitterBufferMs, protocol, isRecordEnabled)

        return START_STICKY
    }

    fun bindSurface(surface: Surface) {
        currentSurface = surface
        videoDecoder.setSurface(surface)
    }

    fun unbindSurface() {
        currentSurface = null
        videoDecoder.setSurface(null)
        videoDecoder.stop()
        audioDecoder.stop()
    }

    fun startListening(
        port: Int = 8888,
        autoAnnounce: Boolean = true,
        jitterBufferMs: Int = 50,
        protocol: TransportProtocol = TransportProtocol.UDP,
        isRecordEnabled: Boolean = false
    ) {
        lastPort = port
        lastAutoAnnounce = autoAnnounce
        lastJitterBufferMs = jitterBufferMs
        lastProtocol = protocol
        
        stopListening()
        
        if (isRecordEnabled) {
            try {
                recordingStartNs = System.nanoTime()
                val mgr = com.example.encoder.MuxerManager(
                    context = this,
                    codecName = "Receiver",
                    expectAudio = true
                )
                // Pre-set standard AAC format (AAC-LC 48kHz Stereo csd-0: 0x11, 0x90) so audio track is never skipped
                val defaultAudioFormat = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0x11.toByte(), 0x90.toByte())))
                }
                mgr.setAudioFormat(defaultAudioFormat)
                muxerManager = mgr
                AppLogger.i(TAG, "QuestReceiverService initialized MuxerManager with AAC audio pre-set for MP4 recording.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize MuxerManager in QuestReceiverService", e)
                muxerManager = null
            }
        } else {
            muxerManager = null
        }
        
        lanDiscovery.deviceNameProvider = {
            var customName: String? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    customName = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                } else {
                    customName = android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get device name", e)
            }
            customName ?: ""
        }

        receiver = if (protocol == TransportProtocol.TCP) {
            TcpReceiver()
        } else {
            UdpFecReceiver(port)
        }
        val currentReceiver = receiver!!

        // Reset stream timeout state
        hasReceivedFirstFrame = false
        lastDataFrameTimeMs = 0L
        isTimeoutCountdownActive = false
        timeoutStartTimeMs = 0L
        mainHandler.removeCallbacks(timeoutCheckRunnable)
        mainHandler.postDelayed(timeoutCheckRunnable, 1000L)
        
        currentReceiver.onFrameAssembled = { frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq ->
            val now = System.currentTimeMillis()
            hasReceivedFirstFrame = true
            lastDataFrameTimeMs = now
            if (isTimeoutCountdownActive) {
                isTimeoutCountdownActive = false
                AppLogger.i(TAG, "Frame received, cancelling disconnect countdown.")
            }

            muxerManager?.let { mgr ->
                try {
                    val ptsUs = (timestampMs * 1000L).coerceAtLeast(0L)
                    if (isCodecConfig) {
                        val mime = if (isHevc) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                        val w = if (videoDecoder.videoWidth > 0) videoDecoder.videoWidth else 1280
                        val h = if (videoDecoder.videoHeight > 0) videoDecoder.videoHeight else 720
                        val format = android.media.MediaFormat.createVideoFormat(mime, w, h)
                        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(frameBytes))
                        mgr.setVideoFormat(format)
                    } else {
                        if (!mgr.isStarted) {
                            val mime = if (isHevc) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                            val w = if (videoDecoder.videoWidth > 0) videoDecoder.videoWidth else 1280
                            val h = if (videoDecoder.videoHeight > 0) videoDecoder.videoHeight else 720
                            val format = android.media.MediaFormat.createVideoFormat(mime, w, h)
                            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(frameBytes))
                            mgr.setVideoFormat(format)
                        }
                        val buffer = java.nio.ByteBuffer.wrap(frameBytes)
                        val bufferInfo = android.media.MediaCodec.BufferInfo().apply {
                            set(0, frameBytes.size, ptsUs, if (isKeyframe) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        }
                        mgr.writeVideoSample(buffer, bufferInfo)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error writing video sample to receiver muxer", e)
                }
            }

            videoDecoder.decodeFrame(frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq)
        }

        currentReceiver.onAudioFrame = { frameBytes, isCodecConfig, timestampMs ->
            val now = System.currentTimeMillis()
            hasReceivedFirstFrame = true
            lastDataFrameTimeMs = now
            if (isTimeoutCountdownActive) {
                isTimeoutCountdownActive = false
            }

            muxerManager?.let { mgr ->
                try {
                    val ptsUs = (timestampMs * 1000L).coerceAtLeast(0L)
                    if (isCodecConfig) {
                        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
                        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(frameBytes))
                        mgr.setAudioFormat(format)
                    } else {
                        val buffer = java.nio.ByteBuffer.wrap(frameBytes)
                        val bufferInfo = android.media.MediaCodec.BufferInfo().apply {
                            set(0, frameBytes.size, ptsUs, 0)
                        }
                        mgr.writeAudioSample(buffer, bufferInfo)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error writing audio sample to receiver muxer", e)
                }
            }

            audioDecoder.decodeFrame(frameBytes, isCodecConfig, timestampMs)
        }

        currentReceiver.onReferenceLost = {
            videoDecoder.notifyReferenceLost()
        }

        currentReceiver.onStreamStop = {
            val now = System.currentTimeMillis()
            AppLogger.i(TAG, "Stream stop / interrupt signal received.")
            if (hasReceivedFirstFrame) {
                if (!isTimeoutCountdownActive) {
                    isTimeoutCountdownActive = true
                    timeoutStartTimeMs = now
                    AppLogger.w(TAG, "Stream interrupted signal received. Starting 20s timeout timer...")
                }
            } else {
                AppLogger.i(TAG, "Stream stop received before initial frame; per requirements, 20s timer is not started.")
            }
        }

        currentReceiver.onStatsUpdated = { stats ->
            val remainingSec = if (isTimeoutCountdownActive) {
                ((TIMEOUT_DURATION_MS - (System.currentTimeMillis() - timeoutStartTimeMs)) / 1000L).coerceAtLeast(0).toInt()
            } else 0

            val updatedStats = stats.copy(
                codecName = videoDecoder.activeDecoderName,
                isTimeoutCounting = isTimeoutCountdownActive,
                timeoutRemainingSec = remainingSec
            )
            latestStats = updatedStats
            onStatsUpdated?.invoke(updatedStats)
        }
        
        currentReceiver.jitterBufferMs = jitterBufferMs
        currentReceiver.start(port)
        if (autoAnnounce) {
            lanDiscovery.startAnnouncing(port)
        }
        isListening = true
        onListeningStateChanged?.invoke(true)
        AppLogger.i(TAG, "QuestReceiverService listening started on port $port, jitterBufferMs=$jitterBufferMs, protocol=${protocol.name}")
    }

    fun stopListening() {
        mainHandler.removeCallbacks(timeoutCheckRunnable)
        isListening = false
        hasReceivedFirstFrame = false
        isTimeoutCountdownActive = false
        
        try {
            muxerManager?.stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping receiver muxerManager", e)
        }
        muxerManager = null

        receiver?.stop()
        videoDecoder.stop()
        audioDecoder.stop()
        lanDiscovery.stopAnnouncing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // Reset stats and video size
        onStatsUpdated?.invoke(com.example.model.StreamStats())
        onVideoSizeChanged?.invoke(0, 0)

        onListeningStateChanged?.invoke(false)
        AppLogger.i(TAG, "QuestReceiverService listening stopped")
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QuestCast Screen Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuestCast 接收端运行中")
            .setContentText("已在 TCP 端口 $port 启动监听...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "QuestReceiverService"
        private const val CHANNEL_ID = "quest_receiver_channel"
        private const val NOTIFICATION_ID = 1002

        private const val STREAM_STALL_THRESHOLD_MS = 2000L
        private const val TIMEOUT_DURATION_MS = 20000L

        const val EXTRA_LISTEN_PORT = "EXTRA_LISTEN_PORT"
        const val EXTRA_AUTO_ANNOUNCE = "EXTRA_AUTO_ANNOUNCE"
        const val EXTRA_JITTER_BUFFER_MS = "EXTRA_JITTER_BUFFER_MS"
        const val EXTRA_PROTOCOL = "EXTRA_PROTOCOL"
        const val EXTRA_RECORD_ENABLED = "EXTRA_RECORD_ENABLED"
    }
}
