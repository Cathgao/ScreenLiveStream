package com.cath.screencast.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.cath.screencast.log.AppLogger
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class AudioDecoder {
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isDecoderReady = false
    private var lastCodecConfigData: ByteArray? = null

    @Volatile
    private var isFeeding = false
    private var feedThread: Thread? = null

    @Volatile
    private var isDraining = false
    private var drainThread: Thread? = null

    private class DecodeTask {
        var data: ByteArray = ByteArray(0)
        var size: Int = 0
        var isCodecConfig: Boolean = false
        // Sender-provided relative-millisecond timestamp used to drive
        // the AAC decoder input PTS. See [decodeFrame].
        var timestampMs: Long = 0L
    }

    @Volatile
    var isLowLatencyMode: Boolean = false

    @Volatile
    var lastRenderedAudioPtsMs: Long = -1L
        private set
    @Volatile
    private var lastSyncTimeMs: Long = 0L

    private val taskQueue = ArrayBlockingQueue<DecodeTask>(250)
    private val taskPool = ConcurrentLinkedQueue<DecodeTask>()

    private fun obtainTask(minSize: Int): DecodeTask {
        val task = taskPool.poll() ?: DecodeTask()
        if (task.data.size < minSize) {
            task.data = ByteArray(Math.max(minSize, 16 * 1024))
        }
        return task
    }

    private fun recycleTask(task: DecodeTask) {
        taskPool.offer(task)
    }

    fun syncWithVideoPts(videoPtsMs: Long) {
        if (!isDecoderReady || videoPtsMs <= 0) return
        val currentAudioPts = lastRenderedAudioPtsMs
        if (currentAudioPts <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastSyncTimeMs < 100) return // Check sync every 100ms
        lastSyncTimeMs = now

        val diffMs = currentAudioPts - videoPtsMs

        // If audio is lagging significantly behind video (> 200ms), fast-forward backlog tasks
        if (diffMs < -200) {
            var droppedTasks = 0
            while (taskQueue.isNotEmpty()) {
                val head = taskQueue.peek() ?: break
                if (head.timestampMs in 1 until (videoPtsMs - 50)) {
                    val dropped = taskQueue.poll()
                    if (dropped != null) {
                        recycleTask(dropped)
                        droppedTasks++
                    }
                } else {
                    break
                }
            }

            if (droppedTasks > 0) {
                AppLogger.w(TAG, "[AV_SYNC] Audio lagging behind video by ${-diffMs}ms. Fast-forwarded $droppedTasks tasks to align with video PTS=$videoPtsMs")
            }
        }

        // Dynamic seamless pitch-preserving speed adjustment (Android 6.0+ PlaybackParams) for minor drift
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val currentSpeed = track.playbackParams.speed
                    val targetSpeed = when {
                        diffMs < -100 -> 1.08f   // Audio lagging slightly (> 100ms) -> catch up smoothly
                        diffMs > 100 -> 0.92f    // Audio ahead slightly (> 100ms) -> slow down smoothly
                        else -> 1.0f             // In-sync (within ±100ms) -> normal 1.0x speed
                    }
                    if (Math.abs(currentSpeed - targetSpeed) > 0.01f) {
                        val params = track.playbackParams
                        params.speed = targetSpeed
                        track.playbackParams = params
                        AppLogger.d(TAG, "[AV_SYNC] Adjusted audio playback speed to ${targetSpeed}x (diff=${diffMs}ms, audioPts=$currentAudioPts, videoPts=$videoPtsMs)")
                    }
                }
            } catch (e: Exception) {
                // Ignore any playback param exceptions on legacy / OEM-specific audio drivers
            }
        }
    }

    @Synchronized
    fun start(sampleRate: Int = 48000, channelCount: Int = 2, codecConfigData: ByteArray? = null) {
        stop()
        lastCodecConfigData = codecConfigData
        taskQueue.clear()
        lastRenderedAudioPtsMs = -1L
        lastSyncTimeMs = 0L

        try {
            val mimeType = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
            
            val configBytes = codecConfigData ?: byteArrayOf(0x11.toByte(), 0x90.toByte())
            format.setByteBuffer("csd-0", ByteBuffer.wrap(configBytes))
            AppLogger.d(TAG, "Configuring AudioDecoder with csd-0 size: ${configBytes.size}")

            val mc = MediaCodec.createDecoderByType(mimeType)
            mc.configure(format, null, null, 0)
            mc.start()

            decoder = mc

            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Low-latency mode: 1x minBufSize (~20-30ms) to prevent audio latency accumulation
            // Large buffer mode: 4x (~80-120ms)
            val multiplier = if (isLowLatencyMode) 1 else 4
            val bufferSize = Math.max(minBufSize * multiplier, 2048)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = trackBuilder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isLowLatencyMode) {
                try {
                    val frameSize = channelCount * 2
                    val minFrames = minBufSize / frameSize
                    track.setBufferSizeInFrames(minFrames)
                } catch (_: Exception) {}
            }
            audioTrack = track

            track.play()
            isDecoderReady = true
            AppLogger.d(TAG, "AudioDecoder and AudioTrack started successfully with buffer: $bufferSize bytes (lowLatency=$isLowLatencyMode).")
            
            startDrainThread(mc, track)
            startFeedThread(mc)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start AudioDecoder", e)
            stop()
        }
    }

    private fun startDrainThread(mc: MediaCodec, track: AudioTrack) {
        stopDrainThread()
        isDraining = true
        drainThread = thread(start = true, name = "AudioDecoderDrainThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val bufferInfo = MediaCodec.BufferInfo()
            while (isDraining) {
                try {
                    var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outputIndex >= 0 && isDraining) {
                        val outputBuffer = mc.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                var remaining = bufferInfo.size
                                while (remaining > 0 && isDraining) {
                                    val written = track.write(outputBuffer, remaining, AudioTrack.WRITE_BLOCKING)
                                    if (written <= 0) break
                                    remaining -= written
                                }
                            } else {
                                val pcmBytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(pcmBytes)
                                var written = 0
                                while (written < pcmBytes.size && isDraining) {
                                    val w = track.write(pcmBytes, written, pcmBytes.size - written)
                                    if (w <= 0) break
                                    written += w
                                }
                            }
                            val ptsMs = bufferInfo.presentationTimeUs / 1000L
                            if (ptsMs > 0) {
                                lastRenderedAudioPtsMs = ptsMs
                            }
                        }
                        mc.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mc.dequeueOutputBuffer(bufferInfo, 10_000)
                    }
                } catch (e: IllegalStateException) {
                    break
                } catch (e: Exception) {
                    if (isDraining) AppLogger.e(TAG, "Error in audio drain thread", e)
                }
            }
        }
    }

    private fun stopDrainThread() {
        isDraining = false
        drainThread?.interrupt()
        drainThread = null
    }

    private fun startFeedThread(mc: MediaCodec) {
        stopFeedThread()
        isFeeding = true
        feedThread = thread(start = true, name = "AudioDecoderFeedThread") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (isFeeding) {
                try {
                    val task = taskQueue.take()
                    try {
                        var inputIndex = mc.dequeueInputBuffer(10_000)
                        var retryCount = 0
                        while (inputIndex < 0 && retryCount < 50 && isFeeding) {
                            inputIndex = mc.dequeueInputBuffer(10_000)
                            retryCount++
                        }

                        if (inputIndex >= 0) {
                            val inputBuffer = mc.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(task.data, 0, task.size)
                                val ptsUs = if (task.timestampMs >= 0) {
                                    task.timestampMs * 1000L
                                } else {
                                    System.nanoTime() / 1000L
                                }
                                mc.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    task.size,
                                    ptsUs,
                                    0
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (isFeeding) AppLogger.e(TAG, "Error feeding audio frame", e)
                    } finally {
                        recycleTask(task)
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopFeedThread() {
        isFeeding = false
        feedThread?.interrupt()
        feedThread = null
    }

    fun decodeFrame(frameBytes: ByteArray, isCodecConfig: Boolean, timestampMs: Long = 0L) {
        if (isCodecConfig) {
            val configChanged = lastCodecConfigData == null || !lastCodecConfigData!!.contentEquals(frameBytes)
            if (!isDecoderReady || configChanged) {
                AppLogger.i(TAG, "Initializing/restarting audio decoder with new CodecConfig, size: ${frameBytes.size}")
                start(codecConfigData = frameBytes)
            }
            return
        }

        if (!isDecoderReady) {
            start(codecConfigData = lastCodecConfigData)
        }

        if (!isDecoderReady) return

        // Audio queue buffer cap: 15 frames (~300ms) in low latency, 200 frames (~4.2s) in large buffer mode
        val maxQueueLimit = if (isLowLatencyMode) 15 else 200
        var droppedCount = 0
        while (taskQueue.size > maxQueueLimit) {
            val dropped = taskQueue.poll()
            if (dropped != null) {
                recycleTask(dropped)
                droppedCount++
            }
        }
        if (droppedCount > 0 && isLowLatencyMode) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
            } catch (_: Exception) {}
        }

        val task = obtainTask(frameBytes.size)
        System.arraycopy(frameBytes, 0, task.data, 0, frameBytes.size)
        task.size = frameBytes.size
        task.isCodecConfig = isCodecConfig
        task.timestampMs = timestampMs

        if (!taskQueue.offer(task)) {
            AppLogger.w(TAG, "Audio decoder queue full! Dropping audio frame.")
            recycleTask(task)
        }
    }

    @Synchronized
    fun flushDecoder() {
        try {
            decoder?.flush()
            taskQueue.clear()
            lastRenderedAudioPtsMs = -1L
            AppLogger.i(TAG, "AudioDecoder flushed successfully.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error flushing AudioDecoder", e)
        }
    }

    @Synchronized
    fun stop() {
        stopFeedThread()
        stopDrainThread()
        
        isDecoderReady = false
        lastCodecConfigData = null
        lastRenderedAudioPtsMs = -1L
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        decoder = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioDecoder"
    }
}
