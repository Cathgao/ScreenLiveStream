package com.example.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

import com.example.model.VideoCodec

class UdpFecStreamer : IStreamer {
    private val TAG = "UdpFecStreamer"
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    
    @Volatile
    var isStreaming = false
        private set
        
    private val MAX_PAYLOAD = 1300
    private val FEC_GROUP_SIZE = 12 // Adjust based on network
    
    override var onRequestKeyframe: (() -> Unit)? = null
    
    override fun start(targetIp: String, port: Int) {
        if (isStreaming) return
        isStreaming = true
        this.targetPort = port
        kotlin.concurrent.thread(name = "UdpStreamerThread") {
            try {
                socket = DatagramSocket()
                socket?.sendBufferSize = 4 * 1024 * 1024
                targetAddress = InetAddress.getByName(targetIp)
                Log.i(TAG, "UDP Streamer started targeting $targetIp:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting UDP streamer", e)
                isStreaming = false
            }
        }
    }
    
    private var videoSeqCounter = 0
    private var audioSeqCounter = 0
    
    override fun sendFrame(
        frameData: ByteArray,
        offset: Int,
        size: Int,
        timestampMs: Long,
        isKeyframe: Boolean,
        isCodecConfig: Boolean,
        codec: VideoCodec
    ) {
        if (!isStreaming || socket == null || targetAddress == null) return
        
        try {
            val frameSize = size
            val totalFragments = (frameSize + MAX_PAYLOAD - 1) / MAX_PAYLOAD
            var flags = 0
            if (isKeyframe) flags = flags or 1
            if (isCodecConfig) flags = flags or 2
            if (codec == VideoCodec.H265) flags = flags or 4
            
            val seq = videoSeqCounter++
            
            val fragments = ArrayList<ByteArray>()
            for (i in 0 until totalFragments) {
                val fragOffset = offset + i * MAX_PAYLOAD
                val length = Math.min(MAX_PAYLOAD, frameSize - i * MAX_PAYLOAD)
                
                val packetBuf = ByteBuffer.allocate(28 + length)
                packetBuf.putInt(0x55445056) // magic
                packetBuf.putInt(seq)
                packetBuf.putLong(timestampMs)
                packetBuf.put(flags.toByte())
                packetBuf.putShort(i.toShort())
                packetBuf.putShort(totalFragments.toShort())
                packetBuf.putInt(frameSize)
                packetBuf.put(ByteArray(3)) // pad to 28 bytes
                packetBuf.put(frameData, fragOffset, length)
                
                val data = packetBuf.array()
                fragments.add(data)
                
                val dp = DatagramPacket(data, data.size, targetAddress, targetPort)
                socket?.send(dp)
            }
            
            // Generate FEC
            var fecGroupId = 0
            for (i in 0 until totalFragments step FEC_GROUP_SIZE) {
                val end = Math.min(i + FEC_GROUP_SIZE, totalFragments)
                
                val fecPayload = ByteArray(MAX_PAYLOAD)
                for (j in i until end) {
                    val fragData = fragments[j]
                    val fragPayloadLen = fragData.size - 28
                    for (k in 0 until fragPayloadLen) {
                        fecPayload[k] = (fecPayload[k].toInt() xor fragData[28 + k].toInt()).toByte()
                    }
                }
                
                val packetBuf = ByteBuffer.allocate(28 + MAX_PAYLOAD)
                packetBuf.putInt(0x55445056)
                packetBuf.putInt(seq)
                packetBuf.putLong(timestampMs)
                val fecFlags = flags or 8 // FEC flag
                packetBuf.put(fecFlags.toByte())
                packetBuf.putShort(fecGroupId.toShort())
                packetBuf.putShort(totalFragments.toShort())
                packetBuf.putInt(frameSize)
                packetBuf.put(ByteArray(3)) // pad
                packetBuf.put(fecPayload)
                
                val dp = DatagramPacket(packetBuf.array(), packetBuf.array().size, targetAddress, targetPort)
                socket?.send(dp)
                
                fecGroupId++
            }
            
        } catch (e: Exception) {
            // Log.e(TAG, "Error sending frame", e)
        }
    }
    
    override fun sendAudioFrame(
        frameData: ByteArray,
        size: Int,
        timestampMs: Long,
        isCodecConfig: Boolean
    ) {
        if (!isStreaming || socket == null || targetAddress == null) return
        
        try {
            var flags = 16 // 16 = Audio
            if (isCodecConfig) flags = flags or 32
            
            val seq = audioSeqCounter++
            
            val packetBuf = ByteBuffer.allocate(28 + size)
            packetBuf.putInt(0x55445056)
            packetBuf.putInt(seq)
            packetBuf.putLong(timestampMs)
            packetBuf.put(flags.toByte())
            packetBuf.putShort(0.toShort()) // fragIndex
            packetBuf.putShort(1.toShort()) // totalFragments
            packetBuf.putInt(size) // frameSize
            packetBuf.put(ByteArray(3)) // pad
            packetBuf.put(frameData, 0, size)
            
            val data = packetBuf.array()
            val dp = DatagramPacket(data, data.size, targetAddress, targetPort)
            socket?.send(dp)
        } catch (e: Exception) {}
    }
    
    override fun sendStatsBeacon(rttMs: Int, lossPercent: Float) {
        if (!isStreaming || socket == null || targetAddress == null) return
        
        try {
            val flags = 64 // 64 = Beacon
            val packetBuf = ByteBuffer.allocate(28 + 8)
            packetBuf.putInt(0x55445056)
            packetBuf.putInt(0)
            packetBuf.putLong(0L)
            packetBuf.put(flags.toByte())
            packetBuf.putShort(0.toShort())
            packetBuf.putShort(1.toShort())
            packetBuf.putInt(8)
            packetBuf.put(ByteArray(3))
            
            packetBuf.putInt(rttMs.coerceAtLeast(0))
            val lossBp = (lossPercent * 100f).toInt().coerceIn(0, 10000)
            packetBuf.putInt(lossBp)
            
            val data = packetBuf.array()
            val dp = DatagramPacket(data, data.size, targetAddress, targetPort)
            socket?.send(dp)
        } catch (e: Exception) {}
    }
    
    override fun stop() {
        isStreaming = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
    }
}
