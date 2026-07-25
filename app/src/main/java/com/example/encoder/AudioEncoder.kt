package com.example.encoder

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
import com.example.log.AppLogger
import com.example.net.IStreamer
import java.util.concurrent.atomic.AtomicBoolean

class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val tcpStreamer: IStreamer? = null,
    private var muxerManager: MuxerManager? = null
) {
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var captureThread: Thread? = null

    private val isRunning = AtomicBoolean(false)

    fun setMuxerManager(muxer: MuxerManager?) {
        this.muxerManager = muxer
    }

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
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            record.startRecording()
            AppLogger.i(TAG, "Internal Audio AudioRecord and AAC MediaCodec started successfully (48kHz Stereo 128kbps).")

            startAudioLoop(record, codec, bufferSize)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start internal AudioEncoder", e)
            stop()
        }
    }

    private fun startAudioLoop(record: AudioRecord, codec: MediaCodec, bufferSize: Int) {
        val startTimeNs = System.nanoTime()

        captureThread = Thread({
            val audioBuffer = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var audioFrameCount = 0L

            AppLogger.i(TAG, "Audio capture loop thread running...")

            while (isRunning.get()) {
                val readBytes = record.read(audioBuffer, 0, audioBuffer.size)
                if (readBytes > 0) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(audioBuffer, 0, readBytes)
                            val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                            codec.queueInputBuffer(inputBufferIndex, 0, readBytes, ptsUs, 0)
                        }
                    }

                    // Dequeue output from AAC encoder
                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = codec.outputFormat
                            AppLogger.i(TAG, "Audio Encoder output format changed: $newFormat")
                            muxerManager?.setAudioFormat(newFormat)
                        } else {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                
                                val data = ByteArray(bufferInfo.size)
                                val originalPosition = outputBuffer.position()
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(data)
                                
                                val timestampMs = System.currentTimeMillis()
                                
                                if (isConfig) {
                                    AppLogger.i(TAG, "Audio Encoder produced AAC CodecConfig, size: ${bufferInfo.size} bytes")
                                    tcpStreamer?.sendAudioFrame(data, data.size, timestampMs, true)
                                } else {
                                    audioFrameCount++
                                    if (audioFrameCount == 1L || audioFrameCount % 200L == 0L) {
                                        AppLogger.i(TAG, "Audio Encoder captured & encoded AAC frame #$audioFrameCount, size: ${bufferInfo.size} bytes")
                                    }

                                    tcpStreamer?.sendAudioFrame(data, data.size, timestampMs, false)

                                    // Restore position for Muxer
                                    outputBuffer.position(bufferInfo.offset)
                                    muxerManager?.writeAudioSample(outputBuffer, bufferInfo)
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
