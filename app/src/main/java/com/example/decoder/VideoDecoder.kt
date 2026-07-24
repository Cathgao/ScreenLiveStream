package com.example.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.model.VideoCodec
import java.nio.ByteBuffer

class VideoDecoder {
    private var decoder: MediaCodec? = null
    @Volatile
    private var isDecoderReady = false
    private var currentCodec: VideoCodec? = null
    @Volatile
    private var targetSurface: Surface? = null

    fun setSurface(surface: Surface?) {
        if (targetSurface != surface) {
            targetSurface = surface
            if (surface == null) {
                stop()
            }
        }
    }

    fun start(surface: Surface, isHevc: Boolean) {
        stop()
        targetSurface = surface
        val mimeType = if (isHevc) VideoCodec.H265.mimeType else VideoCodec.H264.mimeType
        currentCodec = if (isHevc) VideoCodec.H265 else VideoCodec.H264

        try {
            // Default 1080p initial decoder format
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080)

            // Low Latency flag for Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val mc = MediaCodec.createDecoderByType(mimeType)
            mc.configure(format, surface, null, 0)
            mc.start()

            decoder = mc
            isDecoderReady = true
            Log.d(TAG, "VideoDecoder initialized with $mimeType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VideoDecoder for $mimeType", e)
            stop()
        }
    }

    fun decodeFrame(frameBytes: ByteArray, isKeyframe: Boolean, isCodecConfig: Boolean, isHevc: Boolean) {
        val codecType = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        val surf = targetSurface

        if (surf != null && (!isDecoderReady || currentCodec != codecType)) {
            start(surf, isHevc)
        }

        val mc = decoder ?: return
        if (!isDecoderReady) return

        try {
            val inputIndex = mc.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = mc.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameBytes)

                    val flags = if (isCodecConfig) {
                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    } else if (isKeyframe) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }

                    val presentationTimeUs = System.nanoTime() / 1000
                    mc.queueInputBuffer(
                        inputIndex,
                        0,
                        frameBytes.size,
                        presentationTimeUs,
                        flags
                    )
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)

            while (outputIndex >= 0) {
                // Immediate render to surface with render=true
                mc.releaseOutputBuffer(outputIndex, true)
                outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
        }
    }

    fun stop() {
        isDecoderReady = false
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        decoder = null
        currentCodec = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
