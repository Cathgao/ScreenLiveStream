package com.cath.screencast.net

import com.cath.screencast.model.VideoCodec

interface IStreamer {
    fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    )

    fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    )

    fun sendStatsBeacon(rttMs: Int, lossPercent: Float)
    fun sendStreamStopSignal()
    
    var onRequestKeyframe: (() -> Unit)?
    
    fun setBitrate(bitrateKbps: Int, fps: Int = 90) {}
    
    fun start(targetIp: String, port: Int)
    fun stop()
}
