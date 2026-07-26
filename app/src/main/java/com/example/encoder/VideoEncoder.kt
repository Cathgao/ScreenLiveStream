package com.example.encoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Build
import android.view.Surface
import com.example.log.AppLogger
import com.example.model.BitrateMode
import com.example.model.StreamConfig
import com.example.net.IStreamer
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VideoEncoder(
    private val config: StreamConfig,
    private val tcpStreamer: IStreamer,
    private val overrideWidth: Int = 0,
    private val overrideHeight: Int = 0,
    private val overrideFps: Int = 0,
    private val context: Context? = null
) {
    // Local-recording sink. Invoked once per output buffer; the
    // consumer must duplicate the ByteBuffer (muxers do) or read it
    // synchronously — MediaCodec reclaims it after the next
    // releaseOutputBuffer.
    var onEncodedSample: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    // Most recent MediaCodec output format. Null until the first
    // INFO_OUTPUT_FORMAT_CHANGED arrives.
    @Volatile
    var currentOutputFormat: MediaFormat? = null
        private set
    private var codec: MediaCodec? = null
    private var renderer: SurfaceCropRenderer? = null
    @Volatile
    private var isEncoding = false
    private var encoderThread: Thread? = null
    @Volatile
    private var lastCodecConfigData: ByteArray? = null
    private var streamStartNs = 0L

    val inputSurface: Surface?
        get() = renderer?.inputSurface

    // Captured the moment the video encoder produced its first
    // output frame. Both `presentationTimeUs` (from the SurfaceTexture
    // queue) and `System.nanoTime()` are recorded together so the
    // audio encoder can translate its own System.nanoTime()-based
    // input PTS into a value that is collinear with the video
    // MediaCodec output PTS. See AudioEncoder for the consumer side.
    @Volatile
    var firstFramePtsUs: Long = 0L
        private set
    @Volatile
    var firstFrameRealNs: Long = 0L
        private set
    @Volatile
    var firstFrameCaptured: Boolean = false
        private set

    // Callback fired on the encoder thread the first time a video
    // output frame is produced. Sender wires this to AudioEncoder so
    // the audio PTS domain can be rebased to the video's zero point.
    var onFirstFrameCaptured: (() -> Unit)? = null

    fun start() {
        try {
            logSystemEncoders()

            val width = if (overrideWidth > 0) overrideWidth else if (config.resolution.width > 0) config.resolution.width else 1920
            val height = if (overrideHeight > 0) overrideHeight else if (config.resolution.height > 0) config.resolution.height else 1080
            val frameRate = if (overrideFps > 0) overrideFps else if (config.frameRate > 0) config.frameRate else 90
            val mimeType = config.codec.mimeType

            AppLogger.i(TAG, "Starting VideoEncoder: $width x $height @ $frameRate fps, bitrate: ${config.bitrateKbps} Kbps, Codec: ${config.codec.displayName}")

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1s
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                config.bitrateMode.modeInt
            )
            if (config.bitrateMode == BitrateMode.CQ) {
                // Set high quality target for CQ mode (typically 0-100, 80 is very high quality with great compression ratio)
                format.setInteger(MediaFormat.KEY_QUALITY, 80)
            }

            // Low-latency priority hint and no B-frames
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            format.setInteger("max-bframes", 0)

            AppLogger.i(TAG, "Configuring MediaCodec with format: $format")

            val mc = if (config.bitrateMode == BitrateMode.CQ) {
                val cqCodecName = findCqEncoder(mimeType)
                if (cqCodecName != null) {
                    AppLogger.i(TAG, "Found dedicated CQ hardware encoder: $cqCodecName, instantiating...")
                    try {
                        MediaCodec.createByCodecName(cqCodecName)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to create by codec name $cqCodecName, falling back to type: ${e.message}")
                        MediaCodec.createEncoderByType(mimeType)
                    }
                } else {
                    MediaCodec.createEncoderByType(mimeType)
                }
            } else {
                MediaCodec.createEncoderByType(mimeType)
            }
            AppLogger.i(TAG, "Created encoder instance: ${mc.name} for MIME: $mimeType")

            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val codecSurface = mc.createInputSurface()
            mc.start()
            codec = mc

            renderer = SurfaceCropRenderer(
                codecInputSurface = codecSurface,
                width = width,
                height = height
            )

            isEncoding = true
            startEncodeLoop()
            AppLogger.i(TAG, "VideoEncoder loop started successfully using ${mc.name}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize VideoEncoder", e)
            stop()
        }
    }

    private fun findCqEncoder(mimeType: String): String? {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (info.name.endsWith(".cq", ignoreCase = true)) {
                    for (type in info.supportedTypes) {
                        if (type.equals(mimeType, ignoreCase = true)) {
                            return info.name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error searching for CQ encoder", e)
        }
        return null
    }

    private fun logSystemEncoders() {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val infos = codecList.codecInfos
            AppLogger.i(TAG, "=== System Video Encoders Inventory (${infos.size} total codecs) ===")
            for (info in infos) {
                if (!info.isEncoder) continue
                val types = info.supportedTypes
                val isH264 = types.any { it.equals("video/avc", ignoreCase = true) }
                val isH265 = types.any { it.equals("video/hevc", ignoreCase = true) }
                if (isH264 || isH265) {
                    val isHW = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        !info.name.startsWith("OMX.google.", ignoreCase = true) && !info.name.startsWith("c2.android.", ignoreCase = true)
                    }
                    val isSW = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        info.name.startsWith("OMX.google.", ignoreCase = true) || info.name.startsWith("c2.android.", ignoreCase = true)
                    }
                    AppLogger.i(TAG, "Codec: ${info.name} | HW=$isHW | SW=$isSW | Types=${types.joinToString()}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error enumerating system encoders", e)
        }
    }

    private fun startEncodeLoop() {
        encoderThread = thread(start = true, name = "QuestVideoEncoderThread") {
            val bufferInfo = MediaCodec.BufferInfo()
            var tempBuffer = ByteArray(512 * 1024)
            var totalOutputFrames = 0
            var lastLogTime = System.currentTimeMillis()

            streamStartNs = 0L
            AppLogger.i(TAG, "Encoder loop thread running...")

            while (isEncoding) {
                try {
                    val now = System.currentTimeMillis()

                    // Draw frame via GL Renderer
                    renderer?.drawFrame()

                    val mc = codec ?: break
                    var outputIndex = mc.dequeueOutputBuffer(bufferInfo, 5000)

                    while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = mc.outputFormat
                            currentOutputFormat = newFormat
                            AppLogger.i(TAG, "Encoder output format changed: $newFormat")
                        } else {
                            totalOutputFrames++
                            val outputBuffer: ByteBuffer? = mc.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Hand the raw output buffer to the muxer
                                // before we copy it for the network path.
                                // bufferInfo.position/size are already set
                                // by MediaCodec, so the muxer can write
                                // the slice as-is.
                                onEncodedSample?.invoke(outputBuffer, bufferInfo)
                                val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val dataSize = bufferInfo.size
                                if (tempBuffer.size < dataSize) {
                                    tempBuffer = ByteArray(dataSize * 2)
                                }
                                outputBuffer.get(tempBuffer, 0, dataSize)

                                val frameNs = if (bufferInfo.presentationTimeUs > 0) bufferInfo.presentationTimeUs * 1000L else System.nanoTime()
                                if (streamStartNs == 0L) {
                                    streamStartNs = frameNs
                                    // Record the first-frame coordinates so
                                    // AudioEncoder can rebase its PTS to
                                    // share the same zero point.
                                    firstFramePtsUs = bufferInfo.presentationTimeUs
                                    firstFrameRealNs = System.nanoTime()
                                    firstFrameCaptured = true
                                    onFirstFrameCaptured?.invoke()
                                }
                                val timestampMs = ((frameNs - streamStartNs) / 1_000_000L).coerceAtLeast(0L)

                                if (isCodecConfig) {
                                    lastCodecConfigData = tempBuffer.copyOf(dataSize)
                                    AppLogger.i(TAG, "Encoder produced CodecConfig (SPS/PPS), size: $dataSize bytes")
                                    tcpStreamer.sendFrame(
                                        frameData = tempBuffer,
                                        offset = 0,
                                        size = dataSize,
                                        timestampMs = timestampMs,
                                        isKeyframe = false,
                                        isCodecConfig = true,
                                        codec = config.codec
                                    )
                                } else {
                                    if (isKeyframe) {
                                        AppLogger.d(TAG, "Encoder produced Keyframe #$totalOutputFrames, size: $dataSize bytes")
                                        val configBytes = lastCodecConfigData
                                        if (configBytes != null) {
                                            // Send separate CodecConfig packet so decoder gets csd-0 configured
                                            tcpStreamer.sendFrame(
                                                frameData = configBytes,
                                                offset = 0,
                                                size = configBytes.size,
                                                timestampMs = timestampMs,
                                                isKeyframe = false,
                                                isCodecConfig = true,
                                                codec = config.codec
                                            )
                                        }
                                        // Send clean IDR Keyframe data without prepending configBytes inside the same buffer
                                        tcpStreamer.sendFrame(
                                            frameData = tempBuffer,
                                            offset = 0,
                                            size = dataSize,
                                            timestampMs = timestampMs,
                                            isKeyframe = true,
                                            isCodecConfig = false,
                                            codec = config.codec
                                        )
                                    } else {
                                        tcpStreamer.sendFrame(
                                            frameData = tempBuffer,
                                            offset = 0,
                                            size = dataSize,
                                            timestampMs = timestampMs,
                                            isKeyframe = false,
                                            isCodecConfig = false,
                                            codec = config.codec
                                        )
                                    }
                                }
                            }
                            mc.releaseOutputBuffer(outputIndex, false)

                            // Periodic logging every 60 frames or 3 seconds
                            if (totalOutputFrames % 60 == 0 || now - lastLogTime > 3000) {
                                lastLogTime = now
                                AppLogger.i(TAG, "Encoder Stats: Total Encoded Frames=$totalOutputFrames")
                            }
                        }

                        outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    if (outputIndex < 0 && outputIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Yield CPU when no buffer is ready
                        Thread.sleep(2)
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        AppLogger.e(TAG, "Error in encode loop", e)
                    }
                }
            }
            try {
                renderer?.release()
                AppLogger.i(TAG, "SurfaceCropRenderer released on encoder thread.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error releasing SurfaceCropRenderer on encoder thread", e)
            }
            renderer = null
            AppLogger.i(TAG, "Encoder loop thread exited. Final total frames encoded: $totalOutputFrames")
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
            AppLogger.i(TAG, "Instant IDR Keyframe requested on VideoEncoder")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error requesting IDR keyframe", e)
        }
    }

    fun stop() {
        AppLogger.i(TAG, "Stopping VideoEncoder...")
        isEncoding = false

        val encoderThreadRef = encoderThread
        if (encoderThreadRef != null && encoderThreadRef.isAlive) {
            try {
                encoderThreadRef.join(5_000L)
                if (encoderThreadRef.isAlive) {
                    AppLogger.w(TAG, "encoderThread did not terminate within 5s; releasing codec anyway")
                }
            } catch (e: InterruptedException) {
                AppLogger.e(TAG, "Interrupted while joining encoderThread", e)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error joining encoderThread", e)
            }
        }
        encoderThread = null

        try {
            codec?.stop()
            codec?.release()
            AppLogger.i(TAG, "MediaCodec stopped and released.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing MediaCodec", e)
        }
        codec = null
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
