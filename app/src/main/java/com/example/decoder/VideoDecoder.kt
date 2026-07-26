package com.example.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.example.log.AppLogger
import com.example.model.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

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
    private var lastTimestampMs = -1L
    @Volatile
    private var referenceLost = false

    @Volatile
    private var isDraining = false
    private var drainThread: Thread? = null
    
    @Volatile
    private var isFeeding = false
    private var feedThread: Thread? = null

    @Volatile
    var activeDecoderName: String = "未初始化"
        private set

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var onRequestKeyframe: (() -> Unit)? = null

    private class DecodeTask {
        var data: ByteArray = ByteArray(0)
        var size: Int = 0
        var isKeyframe: Boolean = false
        var isCodecConfig: Boolean = false
        var isHevc: Boolean = false
        var timestampMs: Long = 0
        var seq: Int = 0
    }

    private val taskQueue = ArrayBlockingQueue<DecodeTask>(60) // Max 60 frames in queue (~1 sec at 60fps)
    private val taskPool = ConcurrentLinkedQueue<DecodeTask>()

    private fun obtainTask(minSize: Int): DecodeTask {
        val task = taskPool.poll() ?: DecodeTask()
        if (task.data.size < minSize) {
            task.data = ByteArray(Math.max(minSize, 512 * 1024))
        }
        return task
    }

    private fun recycleTask(task: DecodeTask) {
        taskPool.offer(task)
    }

    fun notifyReferenceLost() {
        referenceLost = true
        AppLogger.w(TAG, "Reference frame lost notified! Dropping P-frames until next IDR Keyframe.")
        onRequestKeyframe?.invoke()
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
        taskQueue.clear()

        try {
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            if (codecConfigData != null && codecConfigData.isNotEmpty()) {
                if (isHevc) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                    AppLogger.d(TAG, "Configuring HEVC decoder with csd-0 size: ${codecConfigData.size}")
                } else {
                    val (sps, pps) = extractSpsAndPps(codecConfigData)
                    if (sps != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    }
                    if (pps != null) {
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    }
                    if (sps == null && pps == null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData))
                    }
                }
            }

            val (mc, name) = createBestDecoder(mimeType)
            mc.configure(format, surface, null, 0)
            mc.start()

            decoder = mc
            activeDecoderName = name
            isDecoderReady = true
            AppLogger.i(TAG, "VideoDecoder initialized with $mimeType using [$name]")
            startDrainThread(mc)
            startFeedThread(mc)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start VideoDecoder for $mimeType", e)
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
                    AppLogger.i(TAG, "Selected Video Decoder candidate: '${info.name}' (Priority: ${getDecoderPriority(info)})")
                    return Pair(codec, info.name)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to instantiate decoder '${info.name}', trying next: ${e.message}")
                }
            }

            val fallback = MediaCodec.createDecoderByType(mimeType)
            Pair(fallback, fallback.name)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Exception during decoder lookup, using default for $mimeType: ${e.message}")
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

    private class NalUnit(val offset: Int, val length: Int, val startCodeLen: Int)

    private fun findNalUnits(data: ByteArray): List<NalUnit> {
        val list = mutableListOf<NalUnit>()
        var i = 0
        val len = data.size
        while (i < len - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val start = i
                val startCodeLen = 4
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
                list.add(NalUnit(start, nextStart - start, startCodeLen))
                i = j - 1
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val start = i
                val startCodeLen = 3
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
                list.add(NalUnit(start, nextStart - start, startCodeLen))
                i = j - 1
            }
            i++
        }
        return list
    }

    private fun extractSpsAndPps(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = findNalUnits(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            val payloadOffset = nal.offset + nal.startCodeLen
            if (payloadOffset >= data.size) continue
            val nalType = data[payloadOffset].toInt() and 0x1F
            if (nalType == 7) {
                sps = data.copyOfRange(nal.offset, nal.offset + nal.length)
            } else if (nalType == 8) {
                pps = data.copyOfRange(nal.offset, nal.offset + nal.length)
            }
        }
        return Pair(sps, pps)
    }

    private fun ensure4ByteAnnexB(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        val nals = findNalUnits(data)
        if (nals.isEmpty()) return data

        var needsNormalizing = false
        var totalSize = 0
        for (nal in nals) {
            if (nal.startCodeLen == 3) {
                needsNormalizing = true
                totalSize += nal.length + 1
            } else {
                totalSize += nal.length
            }
        }

        if (!needsNormalizing) return data

        val result = ByteArray(totalSize)
        var pos = 0
        for (nal in nals) {
            if (nal.startCodeLen == 3) {
                result[pos++] = 0.toByte()
                result[pos++] = 0.toByte()
                result[pos++] = 0.toByte()
                result[pos++] = 1.toByte()
                System.arraycopy(data, nal.offset + 3, result, pos, nal.length - 3)
                pos += nal.length - 3
            } else {
                System.arraycopy(data, nal.offset, result, pos, nal.length)
                pos += nal.length
            }
        }
        return result
    }

    private fun containsParameterSets(data: ByteArray, isHevc: Boolean): Boolean {
        val nals = findNalUnits(data)
        for (nal in nals) {
            val payloadOffset = nal.offset + nal.startCodeLen
            if (payloadOffset >= data.size) continue

            val nalType = if (isHevc) {
                (data[payloadOffset].toInt() and 0x7E) ushr 1
            } else {
                data[payloadOffset].toInt() and 0x1F
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
                            
                            // Calculate ideal render time based on receiver anchor
                            val renderTimeNs = receiverStartNs + deltaUs * 1000L
                            val now = System.nanoTime()
                            
                            // If the frame render time is too far in the future (e.g. > 200ms due to timestamp anomaly or drift),
                            // re-anchor to prevent MediaCodec from holding the buffer and starving the decoder.
                            // We do NOT re-anchor if the frame is in the past, so that delayed bursts can be played
                            // as fast as possible to catch up to real-time.
                            val scheduledTimeNs = if (renderTimeNs > now + 200_000_000L) {
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
                            videoWidth = w
                            videoHeight = h
                            AppLogger.i(TAG, "Video format changed: $w x $h")
                            onVideoSizeChanged?.invoke(w, h)
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error getting video format", e)
                        }
                    }
                } catch (e: IllegalStateException) {
                    break
                } catch (e: Exception) {
                    if (isDraining) AppLogger.e(TAG, "Error in drain thread", e)
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
        feedThread = thread(start = true, name = "VideoDecoderFeedThread") {
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
                            val inputBuffer: ByteBuffer? = mc.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(task.data, 0, task.size)
            
                                val flags = if (task.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                val ptsUs = if (task.timestampMs >= 0) task.timestampMs * 1000L else System.nanoTime() / 1000L
            
                                if (receiverStartNs == 0L) {
                                    receiverStartNs = System.nanoTime()
                                    streamStartPtsUs = ptsUs
                                }
            
                                mc.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    task.size,
                                    ptsUs,
                                    flags
                                )
                            }
                        } else {
                            AppLogger.w(TAG, "Input buffer unavailable after 500ms timeout on seq=${task.seq}. Requesting IDR keyframe.")
                            notifyReferenceLost()
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error decoding frame seq=${task.seq}", e)
                        notifyReferenceLost()
                    }
                    recycleTask(task)
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

    fun decodeFrame(frameBytes: ByteArray, isKeyframe: Boolean, isCodecConfig: Boolean, isHevc: Boolean, timestampMs: Long, seq: Int) {
        val codecType = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        val surf = targetSurface ?: return

        val formattedBytes = ensure4ByteAnnexB(frameBytes)

        if (isCodecConfig) {
            val configChanged = lastCodecConfigData == null || !lastCodecConfigData!!.contentEquals(formattedBytes)
            lastCodecConfigData = formattedBytes
            if (!isDecoderReady || currentCodec != codecType || configChanged) {
                AppLogger.i(TAG, "Initializing decoder with CodecConfig, size: ${formattedBytes.size}")
                start(surf, isHevc, formattedBytes)
            }
            return
        }

        if (!isDecoderReady || currentCodec != codecType) {
            start(surf, isHevc, lastCodecConfigData)
        }

        if (lastTimestampMs != -1L && timestampMs < lastTimestampMs - 1000) {
            AppLogger.i(TAG, "Timestamp reset detected (old=$lastTimestampMs, new=$timestampMs). Restarting decoder.")
            stop()
            start(surf, isHevc, lastCodecConfigData)
        }
        lastTimestampMs = timestampMs

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
                AppLogger.i(TAG, "First video Keyframe received (seq=$seq). Starting decoding!")
            }
            hasReceivedFirstKeyframe = true
            referenceLost = false
            lastFrameSeq = seq
        }

        if (!hasReceivedFirstKeyframe) {
            return
        }

        if (!isKeyframe) {
            if (lastFrameSeq != -1 && seq > lastFrameSeq + 1) {
                val gap = seq - lastFrameSeq - 1
                AppLogger.w(TAG, "Sequence gap detected: last=$lastFrameSeq, current=$seq, gap=$gap. Requesting IDR keyframe.")
                notifyReferenceLost()
            }

            if (referenceLost) {
                return
            }

            if (lastFrameSeq != -1 && seq < lastFrameSeq) {
                return
            }
        }

        lastFrameSeq = seq

        if (!isDecoderReady) return
        
        val task = obtainTask(finalBytes.size)
        System.arraycopy(finalBytes, 0, task.data, 0, finalBytes.size)
        task.size = finalBytes.size
        task.isKeyframe = isKeyframe
        task.isCodecConfig = isCodecConfig
        task.isHevc = isHevc
        task.timestampMs = timestampMs
        task.seq = seq

        if (!taskQueue.offer(task)) {
            AppLogger.w(TAG, "Video decoder task queue full! Dropping frame seq=$seq and requesting IDR.")
            recycleTask(task)
            notifyReferenceLost()
        }
    }

    fun stop() {
        stopDrainThread()
        stopFeedThread()
        isDecoderReady = false
        hasReceivedFirstKeyframe = false
        lastFrameSeq = -1
        lastTimestampMs = -1L
        referenceLost = false
        receiverStartNs = 0L
        lastCodecConfigData = null
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

    fun flushDecoder() {
        try {
            decoder?.flush()
            taskQueue.clear()
            hasReceivedFirstKeyframe = false
            lastFrameSeq = -1
            referenceLost = false
            receiverStartNs = 0L
            streamStartPtsUs = 0L
            AppLogger.i(TAG, "Decoder flushed successfully.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error flushing decoder", e)
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}

