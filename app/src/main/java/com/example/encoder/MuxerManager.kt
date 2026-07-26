package com.example.encoder

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.media.MediaFormat
import android.media.MediaCodec
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.log.AppLogger
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MuxerManager(
    private val context: Context?,
    private val codecName: String,
    private val expectAudio: Boolean = true
) {
    private var mediaMuxer: MediaMuxer? = null
    private var muxerOutputFile: File? = null
    private var muxerUri: Uri? = null

    @Volatile
    var isStarted = false
        private set

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // Per-track start PTS. Each track independently remembers the
    // PTS of its first sample; the muxer subtracts this from every
    // sample's PTS to produce a zero-based muxerPts. a/v sync is now
    // established at the sender, so the muxer just records whatever
    // the encoders hand in.
    // Unified baseline PTS. The first sample (video or audio) sets
    // baseStartPtsUs; all subsequent samples subtract this base to preserve
    // A/V sync and VFR frame timing.
    private var baseStartPtsUs = -1L
    private var prevVideoPtsUs = -1L
    private var prevAudioPtsUs = -1L

    var videoFramesWritten = 0
        private set
    var videoBytesWritten = 0L
        private set

    var audioFramesWritten = 0
        private set
    var audioBytesWritten = 0L
        private set

    private val lock = Any()
    private val startTimeMs = System.currentTimeMillis()

    init {
        initMuxer()
    }

    private fun initMuxer() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "screen_record_${codecName}_$timeStamp.mp4"
        muxerUri = null
        muxerOutputFile = null

        // 1. MediaStore API for Android 10+ (API 29+) to write directly to public Movies/QuestCast
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context != null) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuestCast")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    val pfd = resolver.openFileDescriptor(uri, "rw")
                    if (pfd != null) {
                        muxerUri = uri
                        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        videoTrackIndex = -1
                        audioTrackIndex = -1
                        isStarted = false
                        AppLogger.i(TAG, "MediaMuxer initialized via MediaStore in public Movies: /sdcard/Movies/QuestCast/$fileName")
                        return
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to create MediaMuxer via MediaStore in Movies: ${e.message}")
            }
        }

        // 2. Direct File write fallback candidates (prioritizing /sdcard/Movies)
        val candidateDirs = mutableListOf<File>()
        candidateDirs.add(File("/sdcard/Movies"))
        candidateDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "QuestCast"))
        candidateDirs.add(File("/sdcard/Download"))
        context?.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { candidateDirs.add(it) }
        context?.filesDir?.let { candidateDirs.add(it) }

        for (dir in candidateDirs) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val outputFile = File(dir, fileName)
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                muxerOutputFile = outputFile
                mediaMuxer = muxer
                videoTrackIndex = -1
                audioTrackIndex = -1
                isStarted = false

                AppLogger.i(TAG, "MediaMuxer initialized directly to file at: ${outputFile.absolutePath}")
                return
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not initialize MediaMuxer at ${dir.absolutePath}: ${e.message}")
            }
        }

        AppLogger.e(TAG, "Failed to initialize MediaMuxer in any candidate directory!")
        mediaMuxer = null
    }

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            if (videoFormat == null) {
                videoFormat = format
                AppLogger.i(TAG, "MuxerManager received Video Format: $format")
                tryStartMuxerLocked()
            }
        }
    }

    // Subscribe to encoder output. The encoder's current output
    // format is forwarded to setVideoFormat as soon as it becomes
    // available, and every buffer is routed to writeVideoSample.
    // Caller must invoke detachVideoEncoder before releasing the
    // encoder.
    fun attachVideoEncoder(encoder: VideoEncoder) {
        encoder.onEncodedSample = { buffer, bufferInfo ->
            val format = encoder.currentOutputFormat
            if (format != null) {
                setVideoFormat(format)
            }
            writeVideoSample(buffer, bufferInfo)
        }
    }

    fun detachVideoEncoder(encoder: VideoEncoder) {
        encoder.onEncodedSample = null
    }

    fun setAudioFormat(format: MediaFormat) {
        synchronized(lock) {
            if (audioFormat == null) {
                audioFormat = format
                AppLogger.i(TAG, "MuxerManager received Audio Format: $format")
                tryStartMuxerLocked()
            }
        }
    }

    fun attachAudioEncoder(encoder: AudioEncoder) {
        encoder.onEncodedSample = { buffer, bufferInfo ->
            val format = encoder.currentOutputFormat
            if (format != null) {
                setAudioFormat(format)
            }
            writeAudioSample(buffer, bufferInfo)
        }
    }

    fun detachAudioEncoder(encoder: AudioEncoder) {
        encoder.onEncodedSample = null
    }

    fun forceStartIfTimeout() {
        synchronized(lock) {
            if (!isStarted && videoFormat != null) {
                tryStartMuxerLocked()
            }
        }
    }

    private fun tryStartMuxerLocked() {
        if (isStarted) return
        val mux = mediaMuxer ?: return

        val vFormat = videoFormat
        val aFormat = audioFormat

        val elapsedTime = System.currentTimeMillis() - startTimeMs
        val readyToStart = if (expectAudio) {
            (vFormat != null && aFormat != null) || (vFormat != null && elapsedTime > 1500)
        } else {
            vFormat != null
        }

        if (readyToStart && vFormat != null) {
            try {
                videoTrackIndex = mux.addTrack(vFormat)
                if (aFormat != null) {
                    audioTrackIndex = mux.addTrack(aFormat)
                    AppLogger.i(TAG, "Added Audio Track index $audioTrackIndex to MediaMuxer")
                } else if (expectAudio) {
                    AppLogger.w(TAG, "Starting MediaMuxer without Audio track due to timeout or missing Audio format.")
                }

                mux.start()
                isStarted = true
                AppLogger.i(TAG, "MediaMuxer started successfully! Video track idx: $videoTrackIndex, Audio track idx: $audioTrackIndex")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start MediaMuxer", e)
            }
        }
    }

    fun writeVideoSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!isStarted) {
                tryStartMuxerLocked()
            }
            if (!isStarted || videoTrackIndex < 0) return

            val rawPtsUs = bufferInfo.presentationTimeUs
            if (rawPtsUs < 0L) {
                AppLogger.w(TAG, "Skipping video sample with negative PTS=${bufferInfo.presentationTimeUs}")
                return
            }

            val dupBuffer = buffer.duplicate()
            dupBuffer.position(bufferInfo.offset)
            dupBuffer.limit(bufferInfo.offset + bufferInfo.size)

            if (baseStartPtsUs < 0L) {
                baseStartPtsUs = rawPtsUs
                AppLogger.i(TAG, "MediaMuxer baseStartPtsUs locked to $rawPtsUs (via first video frame)")
            }

            var muxerPts = rawPtsUs - baseStartPtsUs
            if (muxerPts <= prevVideoPtsUs) {
                muxerPts = prevVideoPtsUs + 1_000L
            }
            prevVideoPtsUs = muxerPts

            val sampleInfo = MediaCodec.BufferInfo().apply {
                set(dupBuffer.position(), bufferInfo.size, muxerPts, bufferInfo.flags)
            }

            try {
                mediaMuxer?.writeSampleData(videoTrackIndex, dupBuffer, sampleInfo)
                videoFramesWritten++
                videoBytesWritten += bufferInfo.size
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error writing video sample to MediaMuxer", e)
            }
        }
    }

    fun writeAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!isStarted) {
                tryStartMuxerLocked()
            }
            if (!isStarted || audioTrackIndex < 0) return

            val rawPtsUs = bufferInfo.presentationTimeUs
            if (rawPtsUs < 0L) {
                AppLogger.w(TAG, "Skipping audio sample with negative PTS=${bufferInfo.presentationTimeUs}")
                return
            }

            val dupBuffer = buffer.duplicate()
            dupBuffer.position(bufferInfo.offset)
            dupBuffer.limit(bufferInfo.offset + bufferInfo.size)

            if (baseStartPtsUs < 0L) {
                baseStartPtsUs = rawPtsUs
                AppLogger.i(TAG, "MediaMuxer baseStartPtsUs locked to $rawPtsUs (via first audio frame)")
            }

            var muxerPts = rawPtsUs - baseStartPtsUs
            if (muxerPts <= prevAudioPtsUs) {
                muxerPts = prevAudioPtsUs + 1_000L
            }
            prevAudioPtsUs = muxerPts

            val sampleInfo = MediaCodec.BufferInfo().apply {
                set(dupBuffer.position(), bufferInfo.size, muxerPts, bufferInfo.flags)
            }

            try {
                mediaMuxer?.writeSampleData(audioTrackIndex, dupBuffer, sampleInfo)
                audioFramesWritten++
                audioBytesWritten += bufferInfo.size
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error writing audio sample to MediaMuxer", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (isStarted) {
                try {
                    mediaMuxer?.stop()
                    mediaMuxer?.release()
                    mediaMuxer = null
                    isStarted = false

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && muxerUri != null && context != null) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        context.contentResolver.update(muxerUri!!, values, null, null)
                        AppLogger.i(TAG, "MuxerManager stopped cleanly. Saved MP4 to public Movies (/sdcard/Movies/QuestCast/)! Video frames: $videoFramesWritten, Audio frames: $audioFramesWritten")
                    } else if (muxerOutputFile != null) {
                        val finalSize = muxerOutputFile?.length() ?: 0L
                        AppLogger.i(TAG, "MuxerManager stopped cleanly. Saved MP4 file size: $finalSize bytes at ${muxerOutputFile?.absolutePath} (Video frames: $videoFramesWritten, Audio frames: $audioFramesWritten)")

                        muxerOutputFile?.let { file ->
                            context?.let { ctx ->
                                MediaScannerConnection.scanFile(
                                    ctx,
                                    arrayOf(file.absolutePath),
                                    arrayOf("video/mp4"),
                                    null
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error stopping MediaMuxer", e)
                }
            } else {
                try {
                    mediaMuxer?.release()
                    mediaMuxer = null
                } catch (e: Exception) {
                    // Ignore
                }
                AppLogger.w(TAG, "MuxerManager released without stop() because it was never started.")
            }
        }
    }

    companion object {
        private const val TAG = "MuxerManager"
    }
}
