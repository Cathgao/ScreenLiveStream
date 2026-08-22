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
import java.util.concurrent.atomic.AtomicBoolean

class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val tcpStreamer: IStreamer? = null,
    @Volatile var streamStartRealNs: Long = 0L
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

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.e(TAG, "AudioPlaybackCapture requires Android 10 (Q) or higher")
            return
        }

        if (!isRunning.compareAndSet(false, true)) {
            AppLogger.w(TAG, "AudioEncoder is already running")
            return
        }

        AppLogger.i(TAG, "Starting AudioEncoder...")

        try {
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = Math.max(minBufferSize * 2, 4096)

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize")
                record.release()
                isRunning.set(false)
                return
            }

            val mimeType = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)

            val codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            audioRecord = record
            audioCodec = codec

            record.startRecording()
            startAudioLoop(record, codec, bufferSize)

            AppLogger.i(TAG, "AudioEncoder started successfully (sampleRate=$sampleRate, channels=2, bitrate=256k)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start internal AudioEncoder", e)
            stop()
        }
    }

    private fun startAudioLoop(record: AudioRecord, codec: MediaCodec, bufferSize: Int) {
        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Read standard AAC frame chunk size: 1024 samples * 2 channels * 2 bytes = 4096 bytes (~21.3ms per read)
            val pcmChunkSize = 4096
            val audioBuffer = ByteArray(pcmChunkSize)
            var aacTempBuffer = ByteArray(4096)
            val bufferInfo = MediaCodec.BufferInfo()
            var audioFrameCount = 0L

            if (streamStartRealNs == 0L) {
                streamStartRealNs = System.nanoTime()
            }

            AppLogger.i(TAG, "Audio capture loop thread running with $pcmChunkSize bytes chunk reading (streamStartRealNs=$streamStartRealNs)...")

            while (isRunning.get()) {
                val readBytes = record.read(audioBuffer, 0, audioBuffer.size)
                if (readBytes > 0) {
                    val nowNs = System.nanoTime()
                    val ptsUs = ((nowNs - streamStartRealNs) / 1000L).coerceAtLeast(0L)
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

                                if (aacTempBuffer.size < bufferInfo.size) {
                                    aacTempBuffer = ByteArray(bufferInfo.size * 2)
                                }
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(aacTempBuffer, 0, bufferInfo.size)

                                val networkPtsMs = (bufferInfo.presentationTimeUs / 1000L).coerceAtLeast(0L)

                                if (isConfig) {
                                    AppLogger.i(TAG, "Audio Encoder produced AAC CodecConfig, size: ${bufferInfo.size} bytes")
                                    tcpStreamer?.sendAudioFrame(aacTempBuffer, bufferInfo.size, networkPtsMs, true)
                                } else {
                                    audioFrameCount++
                                    if (audioFrameCount == 1L || audioFrameCount % 200L == 0L) {
                                        AppLogger.i(TAG, "Audio Encoder captured & encoded AAC frame #$audioFrameCount, size: ${bufferInfo.size} bytes, networkPtsMs=$networkPtsMs")
                                    }

                                    tcpStreamer?.sendAudioFrame(aacTempBuffer, bufferInfo.size, networkPtsMs, false)
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
