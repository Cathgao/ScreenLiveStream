package com.example.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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

    @Volatile
    private var isDraining = false
    private var drainThread: Thread? = null

    @Volatile
    var activeDecoderName: String = "未初始化"
        private set

    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    fun notifyReferenceLost() {
        referenceLost = true
        Log.w(TAG, "Reference frame lost notified! Dropping P-frames until next IDR Keyframe.")
    }

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
            // Default 1080p initial decoder format
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
            }

            // Low Latency flag for Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            // Set CSD-0 / CSD-1 if codec config is available
            if (codecConfigData != null && codecConfigData.isNotEmpty()) {
                if (isHevc) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                    Log.d(TAG, "Configuring HEVC decoder with csd-0 size: ${codecConfigData.size}")
                } else {
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
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                        Log.w(TAG, "Configuring AVC decoder with fallback csd-0 size: ${codecConfigData.size}")
                    }
                }
            }

            val (mc, name) = createBestDecoder(mimeType)
            mc.configure(format, surface, null, 0)
            mc.start()

            decoder = mc
            activeDecoderName = name
            isDecoderReady = true
            Log.i(TAG, "VideoDecoder initialized with $mimeType using [$name]")
            startDrainThread(mc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VideoDecoder for $mimeType", e)
            stop()
        }
    }

    /**
     * Prioritizes hardware decoders (HW) over software decoders (SW),
     * and prefers Codec2 HW (c2.*) over legacy OMX HW to avoid OMX driver bugs.
     */
    private fun createBestDecoder(mimeType: String): Pair<MediaCodec, String> {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val candidateInfos = mutableListOf<MediaCodecInfo>()

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val supportsType = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                if (supportsType) {
                    candidateInfos.add(info)
                }
            }

            // Sort candidates by priority score (lowest = best)
            val sortedCandidates = candidateInfos.sortedWith { c1, c2 ->
                getDecoderPriority(c1) - getDecoderPriority(c2)
            }

            for (info in sortedCandidates) {
                try {
                    val codec = MediaCodec.createByCodecName(info.name)
                    Log.i(TAG, "Selected Video Decoder candidate: '${info.name}' (Priority: ${getDecoderPriority(info)})")
                    return Pair(codec, info.name)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to instantiate decoder '${info.name}', trying next: ${e.message}")
                }
            }

            val fallback = MediaCodec.createDecoderByType(mimeType)
            Pair(fallback, fallback.name)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during decoder lookup, using default for $mimeType: ${e.message}")
            val fallback = MediaCodec.createDecoderByType(mimeType)
            Pair(fallback, fallback.name)
        }
    }

    private fun isSoftwareDecoder(name: String): Boolean {
        val lc = name.lowercase()
        return lc.startsWith("c2.android.") ||
               lc.startsWith("omx.google.") ||
               lc.startsWith("omx.ffmpeg.") ||
               lc.contains("sw") ||
               lc.contains("soft")
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        return !isSoftwareDecoder(info.name)
    }

    private fun getDecoderPriority(info: MediaCodecInfo): Int {
        val name = info.name.lowercase()
        val isHw = isHardwareDecoder(info)
        val isC2 = name.startsWith("c2.")

        return when {
            // 1. Codec2 Hardware Decoder (Preferred HW, e.g. c2.qti.hevc.decoder)
            isC2 && isHw -> 1
            // 2. Legacy OMX Hardware Decoder (Hardware accelerated, e.g. OMX.qcom.video.decoder.hevc)
            isHw -> 2
            // 3. Codec2 Software Decoder (Fallback SW, e.g. c2.android.hevc.decoder)
            isC2 -> 3
            // 4. Legacy OMX / Google Software Decoder (e.g. OMX.google.hevc.decoder)
            else -> 4
        }
    }

    private fun extractSpsAndPps(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = splitNalUnits(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            if (nal.size < 5) continue
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

    private fun ensure4ByteAnnexB(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        val nals = splitNalUnits(data)
        if (nals.isEmpty()) return data

        var needsNormalizing = false
        var totalSize = 0
        for (nal in nals) {
            if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                needsNormalizing = true
                totalSize += nal.size + 1
            } else {
                totalSize += nal.size
            }
        }

        if (!needsNormalizing) return data

        val result = ByteArray(totalSize)
        var pos = 0
        for (nal in nals) {
            if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                result[pos++] = 0.toByte()
                result[pos++] = 0.toByte()
                result[pos++] = 0.toByte()
                result[pos++] = 1.toByte()
                System.arraycopy(nal, 3, result, pos, nal.size - 3)
                pos += nal.size - 3
            } else {
                System.arraycopy(nal, 0, result, pos, nal.size)
                pos += nal.size
            }
        }
        return result
    }

    private fun containsParameterSets(data: ByteArray, isHevc: Boolean): Boolean {
        val nals = splitNalUnits(data)
        for (nal in nals) {
            if (nal.size < 4) continue
            val headerOffset = if (nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
                4
            } else if (nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
                3
            } else {
                continue
            }
            if (headerOffset >= nal.size) continue

            val nalType = if (isHevc) {
                (nal[headerOffset].toInt() and 0x7E) ushr 1
            } else {
                nal[headerOffset].toInt() and 0x1F
            }

            if (isHevc) {
                if (nalType == 32 || nalType == 33 || nalType == 34) return true
            } else {
                if (nalType == 7 || nalType == 8) return true
            }
        }
        return false
    }

    @Volatile
    var receiverStartNs = 0L
    @Volatile
    var streamStartPtsUs = 0L

    private fun startDrainThread(mc: MediaCodec) {
        stopDrainThread()
        isDraining = true
        drainThread = kotlin.concurrent.thread(start = true, name = "VideoDecoderDrainThread") {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isDraining) {
                try {
                    var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outputIndex >= 0 && isDraining) {
                        val doRender = bufferInfo.size != 0
                        if (doRender && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && receiverStartNs != 0L) {
                            val deltaUs = bufferInfo.presentationTimeUs - streamStartPtsUs
                            val renderTimeNs = receiverStartNs + deltaUs * 1000L + 50 * 1_000_000L // 50ms jitter buffer
                            
                            // Prevent scheduling too far into the future due to clock drift (cap at 150ms ahead)
                            val now = System.nanoTime()
                            val scheduledTimeNs = if (renderTimeNs > now + 150_000_000L) {
                                // If it's more than 150ms in the future, we drift reset
                                receiverStartNs = now - deltaUs * 1000L
                                now + 50 * 1_000_000L
                            } else if (renderTimeNs < now - 50_000_000L) {
                                // If it's more than 50ms in the past, drift reset
                                receiverStartNs = now - deltaUs * 1000L
                                now
                            } else {
                                renderTimeNs
                            }
                            
                            mc.releaseOutputBuffer(outputIndex, scheduledTimeNs)
                        } else {
                            mc.releaseOutputBuffer(outputIndex, doRender)
                        }
                        outputIndex = mc.dequeueOutputBuffer(bufferInfo, 10_000)
                    }
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
                } catch (e: IllegalStateException) {
                    break
                } catch (e: Exception) {
                    if (isDraining) Log.e(TAG, "Error in drain thread", e)
                }
            }
        }
    }

    private fun stopDrainThread() {
        isDraining = false
        drainThread?.interrupt()
        drainThread = null
    }

    fun decodeFrame(frameBytes: ByteArray, isKeyframe: Boolean, isCodecConfig: Boolean, isHevc: Boolean, timestampMs: Long, seq: Int) {
        val codecType = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        val surf = targetSurface ?: return

        val formattedBytes = ensure4ByteAnnexB(frameBytes)

        if (isCodecConfig) {
            val configChanged = lastCodecConfigData == null || !lastCodecConfigData!!.contentEquals(formattedBytes)
            lastCodecConfigData = formattedBytes
            if (!isDecoderReady || currentCodec != codecType || configChanged) {
                Log.i(TAG, "Initializing decoder with CodecConfig, size: ${formattedBytes.size}")
                start(surf, isHevc, formattedBytes)
            }
            return
        }

        if (!isDecoderReady || currentCodec != codecType) {
            start(surf, isHevc, lastCodecConfigData)
        }

        val finalBytes = if (isKeyframe && lastCodecConfigData != null && !containsParameterSets(formattedBytes, isHevc)) {
            val config = lastCodecConfigData!!
            val combined = ByteArray(config.size + formattedBytes.size)
            System.arraycopy(config, 0, combined, 0, config.size)
            System.arraycopy(formattedBytes, 0, combined, config.size, formattedBytes.size)
            combined
        } else {
            formattedBytes
        }

        if (isKeyframe) {
            if (!hasReceivedFirstKeyframe) {
                Log.i(TAG, "First video Keyframe received (seq=$seq). Starting decoding!")
            }
            hasReceivedFirstKeyframe = true
            referenceLost = false
            lastFrameSeq = seq
        }

        if (!hasReceivedFirstKeyframe) {
            // Drop frames prior to first Keyframe to avoid corrupted rendering
            return
        }

        if (!isKeyframe) {
            // 1. Check sequence gap (packet lost over network)
            if (lastFrameSeq != -1 && seq > lastFrameSeq + 1) {
                val gap = seq - lastFrameSeq - 1
                Log.w(TAG, "Sequence gap detected: last=$lastFrameSeq, current=$seq, gap=$gap. Requesting IDR keyframe.")
                notifyReferenceLost()
            }

            // 2. If reference frame was lost/corrupted, strictly drop non-keyframes to avoid green/macroblock artifacts ("烂帧")
            if (referenceLost) {
                return
            }

            // 3. Drop stale out-of-order frame
            if (lastFrameSeq != -1 && seq < lastFrameSeq) {
                return
            }
        }

        lastFrameSeq = seq

        val mc = decoder ?: return
        if (!isDecoderReady) return

        try {
            // Wait up to 10ms for input buffer. Hardware decoders usually free input buffers in 0.5-1ms.
            // A non-zero wait eliminates random silent frame drops at 60/90/120 FPS!
            var inputIndex = mc.dequeueInputBuffer(10_000)
            var retryCount = 0
            while (inputIndex < 0 && retryCount < 50) {
                // drain thread is running concurrently
                inputIndex = mc.dequeueInputBuffer(10_000)
                retryCount++
            }

            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = mc.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(finalBytes)

                    val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    val ptsUs = if (timestampMs > 0) timestampMs * 1000L else System.nanoTime() / 1000L

                    if (receiverStartNs == 0L) {
                        receiverStartNs = System.nanoTime()
                        streamStartPtsUs = ptsUs
                    }

                    mc.queueInputBuffer(
                        inputIndex,
                        0,
                        finalBytes.size,
                        ptsUs,
                        flags
                    )
                }
            } else {
                Log.w(TAG, "Input buffer unavailable after 500ms timeout on seq=$seq. Requesting IDR keyframe.")
                notifyReferenceLost()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame seq=$seq", e)
            notifyReferenceLost()
        }
    }

    private fun hasCodecConfigHeader(bytes: ByteArray): Boolean {
        // Quick check if frame starts with VPS (32) / SPS (33/7) NAL header
        if (bytes.size < 5) return false
        val offset = if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) 4
        else if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) 3
        else return false

        if (offset >= bytes.size) return false
        val nalType = bytes[offset].toInt() and 0x1F
        return nalType == 7 || nalType == 8 || nalType == 32 || nalType == 33 || nalType == 34
    }

    fun stop() {
        stopDrainThread()
        isDecoderReady = false
        hasReceivedFirstKeyframe = false
        lastFrameSeq = -1
        referenceLost = false
        receiverStartNs = 0L
        activeDecoderName = "未运行"
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

