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
    var jitterBufferMs: Int = 0

    @Volatile
    private var isPrebuffering = true

    @Volatile
    private var lastWrittenAudioPtsMs: Long = -1L

    @Volatile
    private var totalFramesWritten: Long = 0L

    @Volatile
    private var currentSampleRate: Int = 48000

    @Volatile
    private var currentChannelCount: Int = 2

    @Volatile
    private var lastSyncTimeMs: Long = 0L

    @Volatile
    private var feedAnchorWallTimeNs: Long = 0L

    @Volatile
    private var feedAnchorPtsMs: Long = -1L

    private val taskQueue = ArrayBlockingQueue<DecodeTask>(500)
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

    /**
     * Sets the playback timeline anchor, synced with VideoDecoder.
     */
    fun setPlaybackAnchor(wallTimeNs: Long, ptsMs: Long) {
        if (wallTimeNs > 0 && ptsMs >= 0) {
            feedAnchorWallTimeNs = wallTimeNs
            feedAnchorPtsMs = ptsMs
            isPrebuffering = false

            // Discard audio tasks that are already older than the anchor PTS
            var purged = 0
            while (taskQueue.isNotEmpty()) {
                val head = taskQueue.peek() ?: break
                if (head.timestampMs < ptsMs - 40L) {
                    val task = taskQueue.poll()
                    if (task != null) {
                        recycleTask(task)
                        purged++
                    }
                } else {
                    break
                }
            }
            if (purged > 0) {
                AppLogger.i(TAG, "AudioDecoder purged $purged obsolete audio tasks older than anchor PTS $ptsMs")
            }
            AppLogger.i(TAG, "AudioDecoder synced to Video playback anchor: wallTimeNs=$wallTimeNs, ptsMs=$ptsMs")
        }
    }

    /**
     * Calculates the exact PTS of the audio sample currently being played
     * by the speaker hardware, accounting for AudioTrack's internal ring buffer latency.
     */
    fun getAcousticPlaybackPtsMs(): Long {
        val track = audioTrack ?: return lastWrittenAudioPtsMs
        val writtenPts = lastWrittenAudioPtsMs
        if (writtenPts < 0) return -1L
        return try {
            val head = 0xFFFFFFFFL and track.playbackHeadPosition.toLong()
            val unplayedFrames = (totalFramesWritten - head).coerceAtLeast(0L)
            val latencyMs = (unplayedFrames * 1000L) / currentSampleRate
            (writtenPts - latencyMs).coerceAtLeast(0L)
        } catch (e: Exception) {
            writtenPts
        }
    }

    /**
     * Synchronizes audio playback with rendered video presentation timestamp.
     * Called whenever a video frame is presented on screen.
     * Note: NEVER drop AAC frames mid-stream, as missing MDCT overlap causes loud pops/crackling.
     */
    fun syncWithVideoPts(videoPtsMs: Long) {
        if (!isDecoderReady || videoPtsMs <= 0) return
        val currentAudioPts = getAcousticPlaybackPtsMs()
        if (currentAudioPts <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastSyncTimeMs < 100) return // Check sync at ~10Hz
        lastSyncTimeMs = now

        val diffMs = currentAudioPts - videoPtsMs // > 0: audio is ahead of video; < 0: audio is lagging

        // Fast recovery if drift is significant (> 300ms)
        if (diffMs < -300) {
            var droppedCount = 0
            while (taskQueue.isNotEmpty()) {
                val head = taskQueue.peek() ?: break
                if (head.timestampMs < videoPtsMs - 20) {
                    val dropped = taskQueue.poll()
                    if (dropped != null) {
                        recycleTask(dropped)
                        droppedCount++
                    }
                } else {
                    break
                }
            }
            if (feedAnchorPtsMs > 0 && feedAnchorWallTimeNs > 0) {
                feedAnchorWallTimeNs -= (-diffMs * 1_000_000L)
            }
            AppLogger.w(TAG, "[AV_SYNC] Significant audio lag detected (diff=${diffMs}ms). Fast-forwarded audio (purged $droppedCount tasks).")
            return
        }

        // Seamless pitch-preserving speed adjustment (Android 6.0+ PlaybackParams) for drift correction
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val currentSpeed = track.playbackParams.speed
                    val targetSpeed = when {
                        diffMs < -200 -> 1.08f   // Audio lagging significantly -> catch up faster
                        diffMs < -30  -> 1.03f   // Audio lagging slightly (> 30ms) -> catch up smoothly
                        diffMs > 200  -> 0.92f   // Audio leading significantly -> slow down faster
                        diffMs > 30   -> 0.97f   // Audio leading slightly (> 30ms) -> slow down smoothly
                        else          -> 1.0f    // In-sync (within ±30ms) -> normal 1.0x speed
                    }
                    if (Math.abs(currentSpeed - targetSpeed) > 0.005f) {
                        val params = track.playbackParams
                        params.speed = targetSpeed
                        track.playbackParams = params
                        AppLogger.d(TAG, "[AV_SYNC] Adjusted audio playback speed to ${targetSpeed}x (diff=${diffMs}ms, acousticAudioPts=$currentAudioPts, videoPts=$videoPtsMs)")
                    }
                }
            } catch (_: Exception) {
                // Ignore OEM-specific playback param exceptions
            }
        }
    }

    @Synchronized
    fun start(sampleRate: Int = 48000, channelCount: Int = 2, codecConfigData: ByteArray? = null) {
        stop()
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        lastCodecConfigData = codecConfigData
        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.poll()
            if (task != null) recycleTask(task)
        }
        lastWrittenAudioPtsMs = -1L
        totalFramesWritten = 0L
        lastSyncTimeMs = 0L
        feedAnchorWallTimeNs = 0L
        feedAnchorPtsMs = -1L
        isPrebuffering = true

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
            // Low-latency mode: 1x minBufSize (~20-30ms)
            // Buffered mode: 4x (~80-120ms)
            val multiplier = if (isLowLatencyMode) 1 else 4
            val bufferSize = Math.max(minBufSize * multiplier, 4096)

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
            AppLogger.d(TAG, "AudioDecoder and AudioTrack started successfully with buffer: $bufferSize bytes (lowLatency=$isLowLatencyMode, jitterBuffer=$jitterBufferMs).")
            
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
            val bytesPerFrame = currentChannelCount * 2
            while (isDraining) {
                try {
                    var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outputIndex >= 0 && isDraining) {
                        val outputBuffer = mc.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            var totalWritten = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                var remaining = bufferInfo.size
                                while (remaining > 0 && isDraining) {
                                    val written = track.write(outputBuffer, remaining, AudioTrack.WRITE_BLOCKING)
                                    if (written <= 0) break
                                    remaining -= written
                                    totalWritten += written
                                }
                            } else {
                                val pcmBytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(pcmBytes)
                                var written = 0
                                while (written < pcmBytes.size && isDraining) {
                                    val w = track.write(pcmBytes, written, pcmBytes.size - written)
                                    if (w <= 0) break
                                    written += w
                                    totalWritten += w
                                }
                            }
                            if (totalWritten > 0 && bytesPerFrame > 0) {
                                totalFramesWritten += (totalWritten / bytesPerFrame)
                            }
                            val ptsMs = bufferInfo.presentationTimeUs / 1000L
                            if (ptsMs > 0) {
                                lastWrittenAudioPtsMs = ptsMs
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
            val targetJitterMs = if (jitterBufferMs > 0) jitterBufferMs else 25

            while (isFeeding) {
                try {
                    if (isPrebuffering) {
                        val prebufferStartNs = System.nanoTime()
                        while (isFeeding && isPrebuffering) {
                            if (feedAnchorWallTimeNs > 0L && feedAnchorPtsMs >= 0L) {
                                isPrebuffering = false
                                break
                            }
                            if (taskQueue.isEmpty()) {
                                Thread.sleep(2)
                                continue
                            }
                            val first = taskQueue.peek()
                            val last = taskQueue.lastOrNull()
                            val accumulatedMs = if (first != null && last != null) (last.timestampMs - first.timestampMs) else 0L
                            val elapsedMs = (System.nanoTime() - prebufferStartNs) / 1_000_000L

                            val isReady = if (targetJitterMs <= 50) {
                                accumulatedMs >= targetJitterMs || taskQueue.size >= 2
                            } else {
                                accumulatedMs >= targetJitterMs || (elapsedMs >= targetJitterMs + 500L && accumulatedMs >= 100L)
                            }

                            if (isReady) {
                                isPrebuffering = false
                                feedAnchorWallTimeNs = System.nanoTime()
                                feedAnchorPtsMs = first?.timestampMs ?: 0L
                                AppLogger.i(TAG, "Audio prebuffering complete (${taskQueue.size} tasks, span=${accumulatedMs}ms, target=${targetJitterMs}ms). Starting smooth paced audio playback.")
                                break
                            }
                            Thread.sleep(2)
                        }
                    }

                    val task = taskQueue.take()
                    try {
                        if (feedAnchorPtsMs < 0) {
                            feedAnchorPtsMs = task.timestampMs
                            feedAnchorWallTimeNs = System.nanoTime()
                        }
                        val relPtsMs = task.timestampMs - feedAnchorPtsMs
                        if (relPtsMs < -300L) {
                            // Discard frame that is substantially in the past relative to anchor
                            continue
                        } else if (relPtsMs > 3600000) {
                            feedAnchorPtsMs = task.timestampMs
                            feedAnchorWallTimeNs = System.nanoTime()
                        } else {
                            val targetTimeNs = feedAnchorWallTimeNs + relPtsMs * 1_000_000L
                            val now = System.nanoTime()
                            val waitNs = targetTimeNs - now
                            if (waitNs > 0) {
                                val waitMs = waitNs / 1_000_000L
                                val waitRemainderNs = (waitNs % 1_000_000L).toInt()
                                if (waitMs > 0 || waitRemainderNs > 0) {
                                    Thread.sleep(waitMs, waitRemainderNs)
                                }
                            } else if (waitNs < -150_000_000L) {
                                feedAnchorWallTimeNs = now - relPtsMs * 1_000_000L
                            }
                        }

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

        // Audio queue buffer cap: 15 frames (~300ms) in low latency, 500 frames (~10.6s) in large buffer mode
        val maxQueueLimit = if (isLowLatencyMode) 15 else 500
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
            while (taskQueue.isNotEmpty()) {
                val task = taskQueue.poll()
                if (task != null) recycleTask(task)
            }
            lastWrittenAudioPtsMs = -1L
            totalFramesWritten = 0L
            feedAnchorWallTimeNs = 0L
            feedAnchorPtsMs = -1L
            isPrebuffering = true
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
        lastWrittenAudioPtsMs = -1L
        totalFramesWritten = 0L
        feedAnchorWallTimeNs = 0L
        feedAnchorPtsMs = -1L
        isPrebuffering = true
        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.poll()
            if (task != null) recycleTask(task)
        }

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
