package com.example.net

import com.example.model.StreamStats

interface IReceiver {
    var onFrameAssembled: ((ByteArray, Boolean, Boolean, Boolean, Long, Int) -> Unit)?
    var onAudioFrame: ((ByteArray, Boolean, Long) -> Unit)?
    var onReferenceLost: (() -> Unit)?
    var onStatsUpdated: ((StreamStats) -> Unit)?
    
    var jitterBufferMs: Int
    
    fun start(port: Int)
    fun stop()
}
