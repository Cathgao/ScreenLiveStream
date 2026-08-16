package com.cath.screencast.model

import android.media.MediaCodecInfo

enum class VideoCodec(val displayName: String, val mimeType: String) {
    H264("H.264 / AVC", "video/avc"),
    H265("H.265 / HEVC (推荐)", "video/hevc")
}

enum class EyeCrop(val displayName: String, val sysPropValue: Int) {
    BOTH("双眼", 2),
    LEFT("左眼", 0),
    RIGHT("右眼", 1)
}

data class VideoResolution(val displayName: String, val width: Int, val height: Int, val category: String = "") {
    companion object {
        val DEFAULT = VideoResolution("原生 100%", 0, 0, "")
    }
}

enum class BitrateMode(val displayName: String, val modeInt: Int) {
    VBR("VBR 动态码率 (最省资源 & 自动降码率)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
    CBR("CBR 恒定码率 (网络流量平稳)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
    CQ("CQ 恒定质量 (画质平稳)", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
}

enum class TransportProtocol(val displayName: String) {
    TCP("TCP (低丢包)"),
    UDP("UDP (带FEC, 低延迟)")
}

data class StreamConfig(
    val codec: VideoCodec = VideoCodec.H265,
    val bitrateKbps: Int = 16000, // 16 Mbps default
    val bitrateMode: BitrateMode = BitrateMode.VBR, // VBR default for max resource savings
    val frameRate: Int = 0, // 0 = Match Native (72/90/120 FPS)
    val eyeCrop: EyeCrop = EyeCrop.BOTH,
    val resolution: VideoResolution = VideoResolution.DEFAULT,
    val targetIp: String = "192.168.1.100",
    val targetPort: Int = 8888,
    val autoDiscover: Boolean = true,
    val protocol: TransportProtocol = TransportProtocol.UDP
)

data class EncoderCapabilities(
    val encoderName: String,
    val isHardwareAccelerated: Boolean,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
    val maxFrameRate: Int,
    val supportedBitrateModes: List<BitrateMode>
) {
    companion object {
        fun query(codec: VideoCodec, bitrateMode: BitrateMode): EncoderCapabilities {
            val mimeType = codec.mimeType
            var selectedInfo: MediaCodecInfo? = null

            try {
                val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                
                // 1. If CQ, try to find the specialized CQ encoder first
                if (bitrateMode == BitrateMode.CQ) {
                    for (info in codecList.codecInfos) {
                        if (!info.isEncoder) continue
                        if (info.name.endsWith(".cq", ignoreCase = true)) {
                            val supported = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                            if (supported) {
                                selectedInfo = info
                                break
                            }
                        }
                    }
                }
                
                // 2. If not found or not CQ, get the default encoder for the mime type
                if (selectedInfo == null) {
                    for (info in codecList.codecInfos) {
                        if (!info.isEncoder) continue
                        val supported = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                        if (supported) {
                            selectedInfo = info
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Safe fallback
            }

            if (selectedInfo == null) {
                return EncoderCapabilities(
                    encoderName = "Default Encoder",
                    isHardwareAccelerated = true,
                    minWidth = 128,
                    maxWidth = 4096,
                    minHeight = 128,
                    maxHeight = 4096,
                    minBitrate = 500 * 1000,
                    maxBitrate = 40 * 1000 * 1000,
                    maxFrameRate = 120,
                    supportedBitrateModes = listOf(BitrateMode.VBR, BitrateMode.CBR, BitrateMode.CQ)
                )
            }

            val name = selectedInfo.name
            val isHW = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                selectedInfo.isHardwareAccelerated
            } else {
                !name.startsWith("OMX.google.", ignoreCase = true) && !name.startsWith("c2.android.", ignoreCase = true)
            }

            var minW = 128
            var maxW = 1920
            var minH = 128
            var maxH = 1080
            var minB = 500000
            var maxB = 40000000
            var maxFps = 120
            val supportedModes = mutableListOf<BitrateMode>()

            try {
                val caps = selectedInfo.getCapabilitiesForType(mimeType)
                val videoCaps = caps.videoCapabilities
                if (videoCaps != null) {
                    minW = videoCaps.supportedWidths.lower
                    maxW = videoCaps.supportedWidths.upper
                    minH = videoCaps.supportedHeights.lower
                    maxH = videoCaps.supportedHeights.upper
                    
                    val bitrateRange = videoCaps.bitrateRange
                    if (bitrateRange != null) {
                        minB = bitrateRange.lower
                        maxB = bitrateRange.upper
                    }
                    
                    val fpsRange = videoCaps.supportedFrameRates
                    if (fpsRange != null) {
                        maxFps = fpsRange.upper
                    }
                }

                val encCaps = caps.encoderCapabilities
                if (encCaps != null) {
                    if (encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                        supportedModes.add(BitrateMode.VBR)
                    }
                    if (encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                        supportedModes.add(BitrateMode.CBR)
                    }
                    if (encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                        supportedModes.add(BitrateMode.CQ)
                    }
                } else {
                    supportedModes.add(BitrateMode.VBR)
                    supportedModes.add(BitrateMode.CBR)
                }
            } catch (e: Exception) {
                supportedModes.add(BitrateMode.VBR)
                supportedModes.add(BitrateMode.CBR)
            }

            return EncoderCapabilities(
                encoderName = name,
                isHardwareAccelerated = isHW,
                minWidth = minW,
                maxWidth = maxW,
                minHeight = minH,
                maxHeight = maxH,
                minBitrate = minB,
                maxBitrate = maxB,
                maxFrameRate = maxFps,
                supportedBitrateModes = if (supportedModes.isEmpty()) listOf(BitrateMode.VBR, BitrateMode.CBR) else supportedModes
            )
        }
    }
}

data class ReceiverConfig(
    val listenPort: Int = 8888,
    val autoAnnounce: Boolean = true,
    val lowLatencyMode: Boolean = true,
    val jitterBufferMs: Int = 50,
    val protocol: TransportProtocol = TransportProtocol.UDP
)

data class StreamStats(
    val isStreaming: Boolean = false,
    val isReceiving: Boolean = false,
    val fps: Float = 0f,
    val bitrateMbps: Float = 0f,
    val latencyMs: Long = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val codecName: String = "",
    val totalFrames: Long = 0,
    val droppedFrames: Long = 0,
    val packetLossPercent: Float = 0f,
    // ---- Phase-3 loss / RTT diagnostics (additive; legacy fields preserved) ----
    /** Frames we gave up on after STALE_FRAME_TIMEOUT_MS with parts missing. */
    val lossTimeoutPercent: Float = 0f,
    /** Frames we proactively dropped to stay under the byte budget. */
    val lossEvictedPercent: Float = 0f,
    /**
     * Network-layer loss estimate from sender-initiated RTT probes.
     * This is the SOLE source of truth for link loss — the earlier
     * frameSeq-gap heuristic was removed because a dispatcher /
     * listener race produced phantom 95%+ values.
     */
    val lossNetworkPercent: Float = 0f,
    /** Current sum of expected bytes for all unfinished assemblies. */
    val inFlightBytes: Long = 0,
    /** Wall-clock time at which this snapshot was created (UI uses to avoid stale overlays). */
    val statsTimestampMs: Long = 0,
    /**
     * Latest rolling RTT in milliseconds from the sender-side
     * UdpRttProbe. Mirrored to the receiver HUD via FLAG_PING_STATS
     * beacons (one per second) so the receiver can show real link
     * latency instead of the wall-clock delta that used to clamp at
     * 1000 ms. 0 means "no beacon received yet".
     */
    val rttMs: Int = 0,
    /** Indicates if receiver is currently in 20s stream loss timeout countdown */
    val isTimeoutCounting: Boolean = false,
    /** Remaining seconds in the disconnect countdown */
    val timeoutRemainingSec: Int = 0
)

data class DiscoveredDevice(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val protocol: TransportProtocol = TransportProtocol.UDP,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val pingMs: Int = 5
)
