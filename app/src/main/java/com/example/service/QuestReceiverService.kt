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

    @Volatile
    var isListening = false
        private set

    @Volatile
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    var currentSurface: Surface? = null
        private set

    var onStatsUpdated: ((StreamStats) -> Unit)? = null

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(port),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(port))
        }

        startListening(port, autoAnnounce, jitterBufferMs, protocol)

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

    fun startListening(port: Int = 8888, autoAnnounce: Boolean = true, jitterBufferMs: Int = 50, protocol: TransportProtocol = TransportProtocol.UDP) {
        stopListening()
        
        receiver = if (protocol == TransportProtocol.TCP) {
            TcpReceiver()
        } else {
            UdpFecReceiver(port)
        }
        val currentReceiver = receiver!!
        
        currentReceiver.onFrameAssembled = { frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq ->
            videoDecoder.decodeFrame(frameBytes, isKeyframe, isCodecConfig, isHevc, timestampMs, seq)
        }

        currentReceiver.onAudioFrame = { frameBytes, isCodecConfig, _ ->
            audioDecoder.decodeFrame(frameBytes, isCodecConfig)
        }

        currentReceiver.onReferenceLost = {
            videoDecoder.notifyReferenceLost()
        }

        currentReceiver.onStatsUpdated = { stats ->
            // Populate active decoder name into stats
            val updatedStats = stats.copy(codecName = videoDecoder.activeDecoderName)
            onStatsUpdated?.invoke(updatedStats)
        }
        
        currentReceiver.jitterBufferMs = jitterBufferMs
        currentReceiver.start(port)
        if (autoAnnounce) {
            lanDiscovery.startAnnouncing(port)
        }
        isListening = true
        AppLogger.i(TAG, "QuestReceiverService listening started on port $port, jitterBufferMs=$jitterBufferMs, protocol=${protocol.name}")
    }

    fun stopListening() {
        isListening = false
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

        const val EXTRA_LISTEN_PORT = "EXTRA_LISTEN_PORT"
        const val EXTRA_AUTO_ANNOUNCE = "EXTRA_AUTO_ANNOUNCE"
        const val EXTRA_JITTER_BUFFER_MS = "EXTRA_JITTER_BUFFER_MS"
        const val EXTRA_PROTOCOL = "EXTRA_PROTOCOL"
    }
}
