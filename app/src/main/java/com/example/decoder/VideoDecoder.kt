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
    private var lastCodecConfigData: ByteArray? = null
    private var hasReceivedFirstKeyframe = false
    private var lastFrameSeq = -1
    private var referenceLost = false

    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    fun setSurface(surface: Surface?) {
        if (targetSurface != surface) {
            targetSurface = surface
            if (surface == null) {
                stop()
            }
        }
    }

    fun start(surface: Surface, isHevc: Boolean, codecConfigData: ByteArray? = null) {
        stop()
        targetSurface = surface
        val mimeType = if (isHevc) VideoCodec.H265.mimeType else VideoCodec.H264.mimeType
        currentCodec = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        lastCodecConfigData = codecConfigData

        try {
            // Default 1080p initial decoder format (adaptive scaling allows decoder to output real resolution)
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080)

            // Low Latency flag for Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            // Set CSD-0 / CSD-1 if codec config is available
            if (codecConfigData != null && codecConfigData.isNotEmpty()) {
                if (isHevc) {
                    // For H.265 (HEVC), pass the entire concatenated VPS/SPS/PPS in csd-0
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                    Log.d(TAG, "Configuring HEVC decoder with csd-0 size: ${codecConfigData.size}")
                } else {
                    // For H.264 (AVC), strictly extract SPS and PPS
                    val (sps, pps) = extractSpsAndPps(codecConfigData)
                    if (sps != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        Log.d(TAG, "Configuring AVC decoder with csd-0 (SPS) size: ${sps.size}")
                    }
                    if (pps != null) {
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        Log.d(TAG, "Configuring AVC decoder with csd-1 (PPS) size: ${pps.size}")
                    }
                    if (sps == null && pps == null) {
                        // Fallback
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                        Log.w(TAG, "Configuring AVC decoder with fallback csd-0 size: ${codecConfigData.size}")
                    }
                }
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

    private fun extractSpsAndPps(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = splitNalUnits(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            if (nal.size < 5) continue
            // Check start code size (3 or 4 bytes)
            val headerOffset = if (nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
                4
            } else if (nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                3
            } else {
                continue
            }
            if (headerOffset >= nal.size) continue
            val nalType = nal[headerOffset].toInt() and 0x1F
            if (nalType == 7) {
                sps = nal
            } else if (nalType == 8) {
                pps = nal
            }
        }
        return Pair(sps, pps)
    }

    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var i = 0
        val len = data.size
        while (i < len - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val start = i
                i += 4
                var nextStart = len
                var j = i
                while (j < len - 3) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()) {
                        nextStart = j
                        break
                    } else if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) {
                        nextStart = j
                        break
                    }
                    j++
                }
                list.add(data.copyOfRange(start, nextStart))
                i = j - 1
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val start = i
                i += 3
                var nextStart = len
                var j = i
                while (j < len - 3) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()) {
                        nextStart = j
                        break
                    } else if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) {
                        nextStart = j
                        break
                    }
                    j++
                }
                list.add(data.copyOfRange(start, nextStart))
                i = j - 1
            }
            i++
        }
        return list
    }

    private fun drainOutputBuffers(mc: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (outputIndex >= 0) {
                mc.releaseOutputBuffer(outputIndex, true)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val format = mc.outputFormat
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "Video format changed: $w x $h")
                    onVideoSizeChanged?.invoke(w, h)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting video format", e)
                }
            }
            outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun decodeFrame(frameBytes: ByteArray, isKeyframe: Boolean, isCodecConfig: Boolean, isHevc: Boolean, seq: Int) {
        val codecType = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        val surf = targetSurface ?: return

        if (isCodecConfig) {
            val configChanged = lastCodecConfigData == null || !lastCodecConfigData!!.contentEquals(frameBytes)
            if (!isDecoderReady || currentCodec != codecType || configChanged) {
                Log.i(TAG, "Initializing/restarting decoder with new CodecConfig, size: ${frameBytes.size}")
                start(surf, isHevc, frameBytes)
            }
            lastFrameSeq = -1
            referenceLost = false
            hasReceivedFirstKeyframe = false
            return
        }

        if (!isDecoderReady || currentCodec != codecType) {
            start(surf, isHevc, lastCodecConfigData)
        }

        // Detect if sender restarted (sequence number reset)
        val isRestart = seq < lastFrameSeq - 100

        if (isKeyframe || isRestart) {
            if (isKeyframe) {
                if (!hasReceivedFirstKeyframe) {
                    Log.i(TAG, "First video Keyframe received (seq=$seq). Starting decoding/display!")
                }
                hasReceivedFirstKeyframe = true
                lastFrameSeq = seq
            } else {
                hasReceivedFirstKeyframe = false
                lastFrameSeq = seq
                Log.w(TAG, "Stream sequence reset detected on non-keyframe (seq=$seq). Waiting for Keyframe.")
                return
            }
        }

        if (!hasReceivedFirstKeyframe) {
            // Drop any frame before the first I-frame to prevent green/corrupted screen at start
            return
        }

        // Sequence gap detection
        if (lastFrameSeq != -1 && seq > lastFrameSeq + 1) {
            val gap = seq - lastFrameSeq - 1
            Log.w(TAG, "Sequence gap detected: last=$lastFrameSeq, current=$seq, gap=$gap. Continuous feed maintained for smoothness.")
        }

        // Out-of-order frame check (already played / obsolete)
        if (lastFrameSeq != -1 && seq < lastFrameSeq) {
            Log.w(TAG, "Dropped out-of-order/stale frame at decoder: seq=$seq, last=$lastFrameSeq")
            return
        }

        // Update sequence
        lastFrameSeq = seq

        val mc = decoder ?: return
        if (!isDecoderReady) return

        try {
            var inputIndex = mc.dequeueInputBuffer(5000)
            var retryCount = 0
            while (inputIndex < 0 && retryCount < 3) {
                // If there are no input buffers available, drain output buffers to free up space,
                // then try to dequeue again. This prevents silent frame drops under transient load.
                drainOutputBuffers(mc)
                retryCount++
                inputIndex = mc.dequeueInputBuffer(5000)
            }

            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = mc.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameBytes)

                    val flags = if (isKeyframe) {
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
            } else {
                Log.w(TAG, "Dropped frame because decoder input buffer was unavailable after retries!")
            }

            // Always drain any pending output buffers after queuing a new frame
            drainOutputBuffers(mc)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
        }
    }

    fun stop() {
        isDecoderReady = false
        hasReceivedFirstKeyframe = false
        lastFrameSeq = -1
        referenceLost = false
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
