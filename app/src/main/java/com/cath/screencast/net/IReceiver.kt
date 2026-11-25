package com.cath.screencast.net

import com.cath.screencast.model.StreamStats

interface IReceiver {
    var onFrameAssembled: ((ByteArray, Int, Boolean, Boolean, Boolean, Long, Int) -> Unit)?
    var onAudioFrame: ((ByteArray, Int, Boolean, Long) -> Unit)?
    var onReferenceLost: (() -> Unit)?
    var onStatsUpdated: ((StreamStats) -> Unit)?
    var onStreamStop: (() -> Unit)?
    
    var jitterBufferMs: Int
    
    fun start(port: Int)
    fun stop()
    fun requestKeyframe() {}
}
