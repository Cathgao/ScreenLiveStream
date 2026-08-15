package com.cath.screencast.encoder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import com.cath.screencast.log.AppLogger
import com.cath.screencast.net.IStreamer
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val tcpStreamer: IStreamer? = null
) {
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var captureThread: Thread? = null

    private val isRunning = AtomicBoolean(false)

    // Local-recording sink. See VideoEncoder.onEncodedSample for
    // the threading contract.
    var onEncodedSample: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    @Volatile
    var currentOutputFormat: MediaFormat? = null
        private set

    // Cross-encoder PTS anchors. The sender probes these from
    // VideoEncoder (its first MediaCodec output frame) and pushes
    // them in via the public setters. While both are 0 the audio
    // capture thread falls back to its own nanoTime() anchor so a
    // misconfigured sender still produces audio.
    @Volatile
    var videoStartPtsUs: Long = 0L
    @Volatile
    var videoStartRealNs: Long = 0L
    @Volatile
    var audioStartPtsUs: Long = -1L

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.w(TAG, "Internal audio capture requires Android 10 (API 29) or higher.")
            return
        }

        if (isRunning.getAndSet(true)) {
            AppLogger.w(TAG, "AudioEncoder is already running.")
            return
        }

        try {
            AppLogger.i(TAG, "Initializing Internal Audio Capture (AudioPlaybackCaptureConfiguration)...")
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 48000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encodingFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encodingFormat)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            val audioFormat = AudioFormat.Builder()
                .setEncoding(encodingFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord state is not initialized!")
                isRunning.set(false)
                return
            }
            audioRecord = record

            // Configure AAC MediaCodec
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            record.startRecording()
            AppLogger.i(TAG, "Internal Audio AudioRecord and AAC MediaCodec started successfully (48kHz Stereo 256kbps).")

            startAudioLoop(record, codec, bufferSize)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start internal AudioEncoder", e)
            stop()
        }
    }

    private fun startAudioLoop(record: AudioRecord, codec: MediaCodec, bufferSize: Int) {
        val audioThreadStartNs = System.nanoTime()

        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // Read standard AAC frame chunk size: 1024 samples * 2 channels * 2 bytes = 4096 bytes (~21.3ms per read)
            val pcmChunkSize = 4096
            val audioBuffer = ByteArray(pcmChunkSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var audioFrameCount = 0L

            AppLogger.i(TAG, "Audio capture loop thread running with $pcmChunkSize bytes chunk reading...")

            fun computePtsUs(): Long {
                val vStartPts = videoStartPtsUs
                val vStartReal = videoStartRealNs
                return if (vStartPts > 0L && vStartReal > 0L) {
                    val deltaUs = (System.nanoTime() - vStartReal) / 1000L
                    (vStartPts + deltaUs).coerceAtLeast(0L)
                } else {
                    (System.nanoTime() - audioThreadStartNs) / 1000L
                }
            }

            while (isRunning.get()) {
                val readBytes = record.read(audioBuffer, 0, audioBuffer.size)
                if (readBytes > 0) {
                    val ptsUs = computePtsUs()
                    queueAudio(codec, audioBuffer, readBytes, ptsUs)

                    // Dequeue output from AAC encoder
                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = codec.outputFormat
                            currentOutputFormat = newFormat
                            AppLogger.i(TAG, "Audio Encoder output format changed: $newFormat")
                        } else {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                onEncodedSample?.invoke(outputBuffer, bufferInfo)
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(data)

                                val rawPtsUs = bufferInfo.presentationTimeUs
                                if (audioStartPtsUs < 0L && rawPtsUs > 0L) {
                                    audioStartPtsUs = rawPtsUs
                                }

                                val startPtsUs = if (videoStartPtsUs > 0L) videoStartPtsUs else audioStartPtsUs
                                val networkPtsMs = if (startPtsUs > 0L && rawPtsUs >= startPtsUs) {
                                    (rawPtsUs - startPtsUs) / 1000L
                                } else {
                                    0L
                                }

                                if (isConfig) {
                                    AppLogger.i(TAG, "Audio Encoder produced AAC CodecConfig, size: ${bufferInfo.size} bytes")
                                    tcpStreamer?.sendAudioFrame(data, data.size, networkPtsMs, true)
                                } else {
                                    audioFrameCount++
                                    if (audioFrameCount == 1L || audioFrameCount % 200L == 0L) {
                                        AppLogger.i(TAG, "Audio Encoder captured & encoded AAC frame #$audioFrameCount, size: ${bufferInfo.size} bytes, rawPtsUs=$rawPtsUs, networkPtsMs=$networkPtsMs")
                                    }

                                    tcpStreamer?.sendAudioFrame(data, data.size, networkPtsMs, false)
                                }
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }
            }
            AppLogger.i(TAG, "Audio capture loop thread ended. Total audio frames encoded: $audioFrameCount")
        }, "AudioCaptureThread")

        captureThread?.start()
    }

    // Queues a single PCM frame into the AAC encoder. Blocks (up to
    // 10 s) if the encoder input is full.
    private fun queueAudio(codec: MediaCodec, pcm: ByteArray, size: Int, ptsUs: Long) {
        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(pcm, 0, size)
                codec.queueInputBuffer(inputBufferIndex, 0, size, ptsUs, 0)
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        AppLogger.i(TAG, "Stopping AudioEncoder...")

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            AppLogger.i(TAG, "AudioRecord released.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping AudioRecord", e)
        }

        try {
            audioCodec?.stop()
            audioCodec?.release()
            audioCodec = null
            AppLogger.i(TAG, "Audio AAC MediaCodec released.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping Audio MediaCodec", e)
        }

        captureThread?.interrupt()
        captureThread = null
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }
}
