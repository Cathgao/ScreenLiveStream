package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.BitrateMode
import com.example.model.DiscoveredDevice
import com.example.model.EncoderCapabilities
import com.example.model.EyeCrop
import com.example.model.ReceiverConfig
import com.example.model.StreamConfig
import com.example.model.StreamStats
import com.example.model.TransportProtocol
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import com.example.net.LanDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

enum class AppMode {
    QUEST_SENDER,
    MOBILE_RECEIVER
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentMode = MutableStateFlow(AppMode.QUEST_SENDER)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _streamConfig = MutableStateFlow(StreamConfig())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig.asStateFlow()

    private val _receiverConfig = MutableStateFlow(ReceiverConfig())
    val receiverConfig: StateFlow<ReceiverConfig> = _receiverConfig.asStateFlow()

    private val _senderStats = MutableStateFlow(StreamStats())
    val senderStats: StateFlow<StreamStats> = _senderStats.asStateFlow()

    private val _receiverStats = MutableStateFlow(StreamStats())
    val receiverStats: StateFlow<StreamStats> = _receiverStats.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _localIpAddress = MutableStateFlow("127.0.0.1")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _encoderCapabilities = MutableStateFlow<EncoderCapabilities?>(null)
    val encoderCapabilities: StateFlow<EncoderCapabilities?> = _encoderCapabilities.asStateFlow()

    private val _resolutionOptions = MutableStateFlow<List<VideoResolution>>(emptyList())
    val resolutionOptions: StateFlow<List<VideoResolution>> = _resolutionOptions.asStateFlow()

    val lanDiscovery = LanDiscovery()

    init {
        initResolutionOptions()
        val defaultRes = _resolutionOptions.value.firstOrNull()
        if (defaultRes != null && (_streamConfig.value.resolution == VideoResolution.DEFAULT || _streamConfig.value.resolution.width == 0)) {
            _streamConfig.value = _streamConfig.value.copy(resolution = defaultRes)
        }
        fetchLocalIp()
        lanDiscovery.onDevicesUpdated = { list ->
            _discoveredDevices.value = list
            if (list.isNotEmpty() && (_streamConfig.value.targetIp.isEmpty() || _streamConfig.value.targetIp == "192.168.1.100")) {
                _streamConfig.value = _streamConfig.value.copy(
                    targetIp = list.first().ipAddress,
                    targetPort = list.first().port
                )
            }
        }
        startLanScan()
        validateAndClampConfig()
    }

    private fun initResolutionOptions() {
        val metrics = getApplication<Application>().resources.displayMetrics
        val nativeW = metrics.widthPixels
        val nativeH = metrics.heightPixels

        fun align16(v: Int): Int = (((v + 8) / 16) * 16).coerceAtLeast(128)

        val options = mutableListOf<VideoResolution>()

        // 1. 100% Native Scale
        val w100 = align16(nativeW)
        val h100 = align16(nativeH)
        options.add(VideoResolution("原生 100% (${w100}x${h100})", w100, h100))

        // 2. 75% Scale
        val w75 = align16((nativeW * 0.75f).toInt())
        val h75 = align16((nativeH * 0.75f).toInt())
        options.add(VideoResolution("高清晰度 75% (${w75}x${h75})", w75, h75))

        // 3. 50% Scale
        val w50 = align16((nativeW * 0.50f).toInt())
        val h50 = align16((nativeH * 0.50f).toInt())
        options.add(VideoResolution("平衡画质 50% (${w50}x${h50})", w50, h50))

        // 4. 33% Scale
        val w33 = align16((nativeW * 0.33f).toInt())
        val h33 = align16((nativeH * 0.33f).toInt())
        options.add(VideoResolution("流畅画质 33% (${w33}x${h33})", w33, h33))

        // 5. 25% Scale
        val w25 = align16((nativeW * 0.25f).toInt())
        val h25 = align16((nativeH * 0.25f).toInt())
        options.add(VideoResolution("极速推流 25% (${w25}x${h25})", w25, h25))

        _resolutionOptions.value = options
    }

    fun setAppMode(mode: AppMode) {
        _currentMode.value = mode
    }

    fun updateCodec(codec: VideoCodec) {
        _streamConfig.value = _streamConfig.value.copy(codec = codec)
        validateAndClampConfig()
    }

    fun updateBitrate(bitrateKbps: Int) {
        _streamConfig.value = _streamConfig.value.copy(bitrateKbps = bitrateKbps)
        validateAndClampConfig()
    }

    fun updateBitrateMode(mode: BitrateMode) {
        _streamConfig.value = _streamConfig.value.copy(bitrateMode = mode)
        validateAndClampConfig()
    }

    fun updateFrameRate(fps: Int) {
        _streamConfig.value = _streamConfig.value.copy(frameRate = fps)
        validateAndClampConfig()
    }

    fun updateEyeCrop(crop: EyeCrop) {
        _streamConfig.value = _streamConfig.value.copy(eyeCrop = crop)
    }

    fun updateResolution(resolution: VideoResolution) {
        _streamConfig.value = _streamConfig.value.copy(resolution = resolution)
        validateAndClampConfig()
    }

    fun validateAndClampConfig() {
        val current = _streamConfig.value
        val caps = EncoderCapabilities.query(current.codec, current.bitrateMode)
        _encoderCapabilities.value = caps

        // Check if resolution exceeds encoder capabilities
        var updatedRes = current.resolution
        val isTooLarge = updatedRes.width > caps.maxWidth || updatedRes.height > caps.maxHeight
        if (isTooLarge) {
            val supportedRes = resolutionOptions.value.filter {
                it.width <= caps.maxWidth && it.height <= caps.maxHeight
            }
            updatedRes = if (supportedRes.isNotEmpty()) {
                supportedRes.firstOrNull() ?: current.resolution
            } else {
                current.resolution
            }
        }

        // Clamp bitrate within supported range
        val minKbps = caps.minBitrate / 1000
        val maxKbps = caps.maxBitrate / 1000
        val clampedBitrate = current.bitrateKbps.coerceIn(minKbps, maxKbps)

        // Ensure the bitrateMode is actually supported
        val finalMode = if (caps.supportedBitrateModes.contains(current.bitrateMode)) {
            current.bitrateMode
        } else {
            caps.supportedBitrateModes.firstOrNull() ?: BitrateMode.VBR
        }

        _streamConfig.value = current.copy(
            resolution = updatedRes,
            bitrateKbps = clampedBitrate,
            bitrateMode = finalMode
        )
    }

    fun updateTargetIp(ip: String) {
        _streamConfig.value = _streamConfig.value.copy(targetIp = ip)
    }

    fun updateTargetPort(port: Int) {
        _streamConfig.value = _streamConfig.value.copy(targetPort = port)
    }

    fun updateProtocol(protocol: TransportProtocol) {
        _streamConfig.value = _streamConfig.value.copy(protocol = protocol)
    }

    fun updateReceiverProtocol(protocol: TransportProtocol) {
        _receiverConfig.value = _receiverConfig.value.copy(protocol = protocol)
    }

    fun updateReceiverPort(port: Int) {
        _receiverConfig.value = _receiverConfig.value.copy(listenPort = port)
    }

    fun updateReceiverJitterBuffer(ms: Int) {
        _receiverConfig.value = _receiverConfig.value.copy(jitterBufferMs = ms)
    }

    fun selectDiscoveredDevice(device: DiscoveredDevice) {
        _streamConfig.value = _streamConfig.value.copy(
            targetIp = device.ipAddress,
            targetPort = device.port
        )
    }

    fun updateReceiverStats(stats: StreamStats) {
        _receiverStats.value = stats
    }

    fun startLanScan() {
        lanDiscovery.startScanning()
    }

    fun stopLanScan() {
        lanDiscovery.stopScanning()
    }

    private fun fetchLocalIp() {
        viewModelScope.launch {
            try {
                val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    _localIpAddress.value = Formatter.formatIpAddress(ipInt)
                    return@launch
                }
            } catch (e: Exception) {
                // Fallback to NetworkInterface
            }

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            _localIpAddress.value = addr.hostAddress ?: "127.0.0.1"
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                _localIpAddress.value = "未知 IP"
            }
        }
    }

    override fun onCleared() {
        lanDiscovery.stopScanning()
        lanDiscovery.stopAnnouncing()
        super.onCleared()
    }
}
