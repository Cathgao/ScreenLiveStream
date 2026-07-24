package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.log.AppLogger
import com.example.model.BitrateMode
import com.example.model.EyeCrop
import com.example.model.VideoCodec
import com.example.model.VideoResolution
import com.example.service.QuestSenderService
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel

@Composable
fun SenderScreen(
    viewModel: MainViewModel,
    isStreaming: Boolean,
    onStartStreamRequested: (Intent) -> Unit,
    onStopStreamRequested: () -> Unit
) {
    val context = LocalContext.current
    val config by viewModel.streamConfig.collectAsState()
    val caps by viewModel.encoderCapabilities.collectAsState()
    val resolutionOptions by viewModel.resolutionOptions.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()
    var logExportResult by remember { mutableStateOf<String?>(null) }

    var ipInput by remember { mutableStateOf(config.targetIp) }
    var portInput by remember { mutableStateOf(config.targetPort.toString()) }

    LaunchedEffect(config.targetIp) {
        if (ipInput != config.targetIp) {
            ipInput = config.targetIp
        }
    }
    LaunchedEffect(config.targetPort) {
        val portStr = config.targetPort.toString()
        if (portInput != portStr) {
            portInput = portStr
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, QuestSenderService::class.java).apply {
                putExtra(QuestSenderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(QuestSenderService.EXTRA_RESULT_DATA, result.data)
                putExtra(QuestSenderService.EXTRA_TARGET_IP, config.targetIp)
                putExtra(QuestSenderService.EXTRA_TARGET_PORT, config.targetPort)
                putExtra(QuestSenderService.EXTRA_BITRATE, config.bitrateKbps)
                putExtra(QuestSenderService.EXTRA_FRAMERATE, config.frameRate)
                putExtra(QuestSenderService.EXTRA_RES_WIDTH, config.resolution.width)
                putExtra(QuestSenderService.EXTRA_RES_HEIGHT, config.resolution.height)
                putExtra(QuestSenderService.EXTRA_EYE_CROP, config.eyeCrop.name)
                putExtra(QuestSenderService.EXTRA_CODEC, config.codec.name)
                putExtra(QuestSenderService.EXTRA_BITRATE_MODE, config.bitrateMode.name)
            }
            onStartStreamRequested(serviceIntent)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        AppLogger.i("SenderScreen", "RECORD_AUDIO permission result: $isGranted. Launching MediaProjection...")
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun startProjectionWithPermissionCheck() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkObsidian)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Target Receiver IP & LAN Auto-Discovery (Moved here above Start button)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCyberSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = LiveGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "接收端目标 IP",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    IconButton(onClick = { viewModel.startLanScan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan LAN", tint = NeonCyan)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val isIpError = ipInput.isNotEmpty() && !isValidIp(ipInput)
                val isPortError = portInput.isNotEmpty() && !isValidPort(portInput)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = {
                            ipInput = it
                            if (isValidIp(it)) {
                                viewModel.updateTargetIp(it)
                            }
                        },
                        isError = isIpError,
                        label = { Text("目标 IP 地址") },
                        supportingText = {
                            if (isIpError) {
                                Text("请输入合法的 IPv4 地址", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (isIpError) MaterialTheme.colorScheme.error else NeonCyan,
                            unfocusedBorderColor = if (isIpError) MaterialTheme.colorScheme.error else BorderCyan,
                            focusedLabelColor = if (isIpError) MaterialTheme.colorScheme.error else NeonCyan,
                            unfocusedLabelColor = TextSecondary
                        )
                    )

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = {
                            portInput = it
                            val portInt = it.toIntOrNull()
                            if (portInt != null && portInt in 1..65535) {
                                viewModel.updateTargetPort(portInt)
                            }
                        },
                        isError = isPortError,
                        label = { Text("端口") },
                        supportingText = {
                            if (isPortError) {
                                Text("1-65535", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (isPortError) MaterialTheme.colorScheme.error else NeonCyan,
                            unfocusedBorderColor = if (isPortError) MaterialTheme.colorScheme.error else BorderCyan,
                            focusedLabelColor = if (isPortError) MaterialTheme.colorScheme.error else NeonCyan,
                            unfocusedLabelColor = TextSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "局域网自动发现的接收端 (${discoveredDevices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (discoveredDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .background(DarkCyberCard, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在扫描局域网接收端...或在上方手动输入 IP",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                } else {
                    Text(
                        text = "💡 点击下方接收端卡片选择，或直接点击『一键投屏』开始",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    discoveredDevices.forEach { dev ->
                        val isSelected = dev.ipAddress == config.targetIp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else DarkCyberCard)
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) NeonCyan else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    viewModel.selectDiscoveredDevice(dev)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = dev.deviceName,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = LiveGreen,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "已选中",
                                                color = Color.Black,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${dev.ipAddress}:${dev.port} • 延迟: ${dev.pingMs}ms",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.selectDiscoveredDevice(dev)
                                    if (!isStreaming) {
                                        startProjectionWithPermissionCheck()
                                    } else {
                                        onStopStreamRequested()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected && isStreaming) ErrorRed else LiveGreen
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isSelected && isStreaming) "停止投屏" else "一键投屏",
                                    color = if (isSelected && isStreaming) Color.White else Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        val isInputValid = isValidIp(ipInput) && isValidPort(portInput)

        // Action Button: Start / Stop Stream
        val buttonText = when {
            isStreaming -> "停止推流"
            !isInputValid -> "请在上方输入合法的接收端 IP 及端口"
            config.targetIp.isNotEmpty() -> "启动 Quest 3 画面投屏"
            else -> "请在上方选择或输入接收端 IP"
        }

        Button(
            onClick = {
                if (isStreaming) {
                    onStopStreamRequested()
                } else {
                    startProjectionWithPermissionCheck()
                }
            },
            enabled = isStreaming || isInputValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("sender_toggle_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) ErrorRed else NeonCyan,
                contentColor = Color.Black,
                disabledContainerColor = DarkCyberCard,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buttonText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Eye Cropping Selector (Quest 3 Dual-Eye Handler)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCyberSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = NeonPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Quest 3 单眼画面裁剪 (防止重影与超载)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                EyeCrop.values().forEach { crop ->
                    val isSelected = config.eyeCrop == crop
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) NeonPurple.copy(alpha = 0.2f) else DarkCyberCard)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) NeonPurple else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.updateEyeCrop(crop) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.updateEyeCrop(crop) },
                            colors = RadioButtonDefaults.colors(selectedColor = NeonPurple)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = crop.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                            Text(
                                text = crop.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Hardware Video Encoder Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCyberSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "硬件编码设置",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                caps?.let { c ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonCyan.copy(alpha = 0.08f))
                            .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "自动匹配编码器: ${c.encoderName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NeonCyan
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "硬件加速: ${if (c.isHardwareAccelerated) "是 (硬件解码芯片)" else "否 (软件编码)"} • 最高帧率: ${c.maxFrameRate} FPS",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "支持分辨率范围: ${c.minWidth}x${c.minHeight} ~ ${c.maxWidth}x${c.maxHeight}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Codec selection
                Text("编码格式", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoCodec.values().forEach { codec ->
                        val selected = config.codec == codec
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.updateCodec(codec) },
                            label = { Text(codec.displayName, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = DarkCyberCard,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bitrate Mode Selection
                Text("码率控制模式", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BitrateMode.values().forEach { mode ->
                        val isSupported = caps?.supportedBitrateModes?.contains(mode) ?: true
                        val selected = config.bitrateMode == mode
                        FilterChip(
                            selected = selected,
                            enabled = isSupported,
                            onClick = { if (isSupported) viewModel.updateBitrateMode(mode) },
                            label = { 
                                Text(
                                    text = if (isSupported) mode.displayName else "${mode.name} (当前格式不支持)", 
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ) 
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = DarkCyberCard,
                                labelColor = TextPrimary,
                                disabledContainerColor = DarkCyberCard.copy(alpha = 0.4f),
                                disabledLabelColor = TextSecondary.copy(alpha = 0.4f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bitrate slider & presets
                val maxBitrateVal = caps?.let { (it.maxBitrate / 1000).toFloat() } ?: 40000f
                val minBitrateVal = caps?.let { (it.minBitrate / 1000).toFloat() } ?: 2000f
                val clampedBitrate = config.bitrateKbps.toFloat().coerceIn(minBitrateVal, maxBitrateVal)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("推流码率", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(
                        "${config.bitrateKbps / 1000} Mbps",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = NeonCyan
                    )
                }

                Slider(
                    value = clampedBitrate,
                    onValueChange = { viewModel.updateBitrate(it.toInt()) },
                    valueRange = minBitrateVal..maxBitrateVal,
                    steps = 37,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonCyan,
                        activeTrackColor = NeonCyan,
                        inactiveTrackColor = DarkCyberCard
                    )
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(8000, 16000, 25000, 40000).filter { it <= maxBitrateVal }.forEach { preset ->
                        OutlinedButton(
                            onClick = { viewModel.updateBitrate(preset) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            border = ButtonDefaults.outlinedToolboxBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (config.bitrateKbps == preset) NeonCyan else Color.Transparent
                                )
                            )
                        ) {
                            Text("${preset / 1000}M", fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Frame rate (Auto Native, 30, 60, 72, 90, 120 FPS)
                val maxFpsVal = caps?.maxFrameRate ?: 120
                Text("目标帧率", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val fpsOptions = listOf(
                        0 to "原生帧率",
                        60 to "60 FPS",
                        72 to "72 FPS",
                        90 to "90 FPS",
                        120 to "120 FPS"
                    ).filter { it.first <= maxFpsVal }
                    fpsOptions.forEach { (fpsVal, label) ->
                        val selected = config.frameRate == fpsVal
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.updateFrameRate(fpsVal) },
                            label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonPurple,
                                selectedLabelColor = Color.White,
                                containerColor = DarkCyberCard,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Resolution Selector
                Text("推流分辨率", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                resolutionOptions.forEach { res ->
                    val isSupported = caps?.let { res.width <= it.maxWidth && res.height <= it.maxHeight } ?: true
                    val selected = config.resolution == res
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) NeonCyan.copy(alpha = 0.15f)
                                else if (!isSupported) DarkCyberCard.copy(alpha = 0.4f)
                                else DarkCyberCard
                            )
                            .clickable(enabled = isSupported) { viewModel.updateResolution(res) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isSupported) res.displayName else "${res.displayName} (⚠️ 编码芯片不支持此分辨率)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSupported) TextPrimary else TextSecondary.copy(alpha = 0.5f)
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }



        // Diagnostics & Log Export Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("log_export_card"),
            colors = CardDefaults.cardColors(containerColor = DarkCyberSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Log Diagnostics Icon",
                        tint = NeonCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "编码器与投屏诊断日志",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Text(
                    text = "日志包含：系统 AVC/HEVC 编码器列表、系统内部声音 (AudioPlaybackCapture) 录制状态、VirtualDisplay 及 MediaMuxer 录制详情。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = {
                        val result = AppLogger.exportLogs(context)
                        logExportResult = result
                        android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("export_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "导出诊断日志到 Download 文件夹",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                logExportResult?.let { status ->
                    Surface(
                        color = DarkObsidian,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = LiveGreen,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

private val ButtonDefaults.outlinedToolboxBorder: androidx.compose.foundation.BorderStroke
    get() = androidx.compose.foundation.BorderStroke(1.dp, BorderCyan)

private fun isValidIp(ip: String): Boolean {
    val regex = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$".toRegex()
    return ip.matches(regex)
}

private fun isValidPort(port: String): Boolean {
    val num = port.toIntOrNull() ?: return false
    return num in 1..65535
}
