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
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.decoder.AudioDecoder
import com.example.decoder.VideoDecoder
import com.example.model.ReceiverConfig
import com.example.model.StreamStats
import com.example.net.LanDiscovery
import com.example.net.UdpReceiver

class QuestReceiverService : Service() {

    private val binder = LocalBinder()
    val udpReceiver = UdpReceiver()
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

    inner class LocalBinder : Binder() {
        fun getService(): QuestReceiverService = this@QuestReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        udpReceiver.onFrameAssembled = { frameBytes, isKeyframe, isCodecConfig, isHevc, _, seq ->
            videoDecoder.decodeFrame(frameBytes, isKeyframe, isCodecConfig, isHevc, seq)
        }

        udpReceiver.onAudioFrame = { frameBytes, isCodecConfig, _ ->
            audioDecoder.decodeFrame(frameBytes, isCodecConfig)
        }

        videoDecoder.onVideoSizeChanged = { w, h ->
            onVideoSizeChanged?.invoke(w, h)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_LISTEN_PORT, 8888) ?: 8888
        val autoAnnounce = intent?.getBooleanExtra(EXTRA_AUTO_ANNOUNCE, true) ?: true
        val jitterBufferMs = intent?.getIntExtra(EXTRA_JITTER_BUFFER_MS, 30) ?: 30

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(port),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(port))
        }

        startListening(port, autoAnnounce, jitterBufferMs)

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

    fun startListening(port: Int = 8888, autoAnnounce: Boolean = true, jitterBufferMs: Int = 30) {
        stopListening()
        udpReceiver.jitterBufferMs = jitterBufferMs
        udpReceiver.start(port)
        if (autoAnnounce) {
            lanDiscovery.startAnnouncing(port)
        }
        isListening = true
        Log.d(TAG, "QuestReceiverService listening started on port $port, jitterBufferMs=$jitterBufferMs")
    }

    fun stopListening() {
        isListening = false
        udpReceiver.stop()
        videoDecoder.stop()
        audioDecoder.stop()
        lanDiscovery.stopAnnouncing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "QuestReceiverService listening stopped")
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
            .setContentText("已在 UDP 端口 $port 启动监听...")
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
    }
}
