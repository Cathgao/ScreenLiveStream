package com.example.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class AudioDecoder {
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isDecoderReady = false
    private var lastCodecConfigData: ByteArray? = null

    @Synchronized
    fun start(sampleRate: Int = 48000, channelCount: Int = 2, codecConfigData: ByteArray? = null) {
        stop()
        lastCodecConfigData = codecConfigData

        try {
            val mimeType = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)
            
            if (codecConfigData != null && codecConfigData.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                Log.d(TAG, "Configuring AudioDecoder with csd-0 size: ${codecConfigData.size}")
            }

            val mc = MediaCodec.createDecoderByType(mimeType)
            mc.configure(format, null, null, 0)
            mc.start()
            decoder = mc

            // Create AudioTrack for real-time low-latency playback
            val channelConfig = if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isDecoderReady = true
            Log.d(TAG, "AudioDecoder and AudioTrack started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioDecoder", e)
            stop()
        }
    }

    @Synchronized
    fun decodeFrame(frameBytes: ByteArray, isCodecConfig: Boolean) {
        if (isCodecConfig) {
            val configChanged = lastCodecConfigData == null || !lastCodecConfigData!!.contentEquals(frameBytes)
            if (!isDecoderReady || configChanged) {
                Log.i(TAG, "Initializing/restarting audio decoder with new CodecConfig, size: ${frameBytes.size}")
                start(codecConfigData = frameBytes)
            }
            return
        }

        if (!isDecoderReady) {
            start(codecConfigData = lastCodecConfigData)
        }

        val mc = decoder ?: return
        val track = audioTrack ?: return
        if (!isDecoderReady) return

        try {
            val inputIndex = mc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = mc.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameBytes)
                    mc.queueInputBuffer(
                        inputIndex,
                        0,
                        frameBytes.size,
                        System.nanoTime() / 1000,
                        0
                    )
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                val outputBuffer = mc.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val pcmBytes = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.get(pcmBytes)
                    
                    track.write(pcmBytes, 0, pcmBytes.size)
                }
                mc.releaseOutputBuffer(outputIndex, false)
                outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio frame", e)
        }
    }

    @Synchronized
    fun stop() {
        isDecoderReady = false
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
