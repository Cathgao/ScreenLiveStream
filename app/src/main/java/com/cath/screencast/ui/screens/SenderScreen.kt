package com.cath.screencast.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cath.screencast.log.AppLogger
import com.cath.screencast.model.BitrateMode
import com.cath.screencast.model.EyeCrop
import com.cath.screencast.model.VideoCodec
import com.cath.screencast.model.VideoResolution
import com.cath.screencast.service.QuestSenderService
import com.cath.screencast.ui.theme.*
import com.cath.screencast.viewmodel.MainViewModel

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
                putExtra(QuestSenderService.EXTRA_PROTOCOL, config.protocol.name)
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

                val isIpError = remember(ipInput) { ipInput.isNotEmpty() && !isValidIp(ipInput) }
                val isPortError = remember(portInput) { portInput.isNotEmpty() && !isValidPort(portInput) }

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
                            .defaultMinSize(minHeight = 64.dp)
                            .background(DarkCyberCard, shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 16.dp),
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
                                        text = if (dev.ipAddress == localIp) "本机" else dev.deviceName,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
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

        val isInputValid = remember(ipInput, portInput) { isValidIp(ipInput) && isValidPort(portInput) }

        // Action Button: Start / Stop Stream
        val buttonText = when {
            isStreaming -> "停止推流"
            !isInputValid -> "请在上方输入合法的接收端 IP 及端口"
            config.targetIp.isNotEmpty() -> if (viewModel.isQuestDevice) "启动Quest画面投屏" else "启动画面投屏"
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
        
        if (viewModel.isQuestDevice) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "选择投屏画面 (Quest)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EyeCrop.values().forEach { crop ->
                        val selected = config.eyeCrop == crop
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.updateEyeCrop(crop) },
                            label = { Text(crop.displayName, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = DarkCyberCard,
                                labelColor = TextPrimary
                            )
                        )
                    }
                }
            }
        }



        AnimatedVisibility(visible = !isStreaming) {
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
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "自动匹配编码器:",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = NeonCyan
                                    )
                                    Text(
                                        text = c.encoderName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = NeonCyan
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "硬件加速: ${if (c.isHardwareAccelerated) "是 (硬件解码芯片)" else "否 (软件编码)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "最高帧率: ${c.maxFrameRate} FPS",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "支持分辨率范围:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                text = "${c.minWidth}x${c.minHeight} ~ ${c.maxWidth}x${c.maxHeight}",
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

                // Transport Protocol Selection
                Text("传输协议", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.cath.screencast.model.TransportProtocol.values().forEach { proto ->
                        val selected = config.protocol == proto
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.updateProtocol(proto) },
                            label = { Text(proto.name, fontWeight = FontWeight.Bold) },
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
                    val presets = remember(maxBitrateVal) {
                        listOf(8000, 16000, 25000, 40000).filter { it <= maxBitrateVal }
                    }
                    presets.forEach { preset ->
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
                    val fpsOptions = remember(maxFpsVal) {
                        listOf(
                            0 to "原生帧率",
                            60 to "60 FPS",
                            72 to "72 FPS",
                            90 to "90 FPS",
                            120 to "120 FPS"
                        ).filter { it.first <= maxFpsVal }
                    }
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
                if (viewModel.isQuestDevice) {
                    val aspectRatios = remember(resolutionOptions) {
                        resolutionOptions.map { it.category }.distinct().filter { it.isNotEmpty() }
                    }
                    var selectedAspectRatio by remember(config.resolution) {
                        mutableStateOf(
                            if (config.resolution.category.isNotEmpty()) config.resolution.category
                            else aspectRatios.firstOrNull { it == "16:9" } ?: aspectRatios.firstOrNull() ?: "16:9"
                        )
                    }

                    Text("视频纵横比", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(2.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        aspectRatios.forEach { ratio ->
                            val selected = selectedAspectRatio == ratio
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedAspectRatio = ratio
                                    if (config.resolution.category != ratio) {
                                        resolutionOptions.firstOrNull { it.category == ratio }?.let { newRes ->
                                            viewModel.updateResolution(newRes)
                                        }
                                    }
                                },
                                label = { Text(ratio, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan,
                                    selectedLabelColor = Color.Black,
                                    containerColor = DarkCyberCard,
                                    labelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("推流分辨率", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(2.dp))

                    val filteredResolutions = remember(selectedAspectRatio, resolutionOptions) {
                        resolutionOptions.filter { it.category == selectedAspectRatio }
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        filteredResolutions.forEach { res ->
                            val isSupported = caps?.let { res.width <= it.maxWidth && res.height <= it.maxHeight } ?: true
                            val selected = config.resolution.width == res.width && config.resolution.height == res.height
                            FilterChip(
                                selected = selected,
                                enabled = isSupported,
                                onClick = { viewModel.updateResolution(res) },
                                label = { Text(res.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan,
                                    selectedLabelColor = Color.Black,
                                    containerColor = DarkCyberCard,
                                    labelColor = TextPrimary,
                                    disabledContainerColor = DarkCyberCard.copy(alpha = 0.3f),
                                    disabledLabelColor = TextSecondary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                } else {
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
        }
        }

        // Diagnostics & Log Export Button + GitHub Link
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val result = AppLogger.exportLogs(context)
                    logExportResult = result
                    android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("export_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "导出诊断日志",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/Cathgao/ScreenLiveStream")
                    )
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(56.dp)
                    .testTag("github_button")
            ) {
                Icon(
                    imageVector = GithubIcon,
                    contentDescription = "GitHub Repository",
                    tint = Color.Black,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        logExportResult?.let { status ->
            Surface(
                color = DarkCyberCard,
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

private val ButtonDefaults.outlinedToolboxBorder: androidx.compose.foundation.BorderStroke
    get() = androidx.compose.foundation.BorderStroke(1.dp, BorderCyan)

private val ipRegex = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$".toRegex()

private fun isValidIp(ip: String): Boolean {
    return ip.matches(ipRegex)
}

private fun isValidPort(port: String): Boolean {
    val num = port.toIntOrNull() ?: return false
    return num in 1..65535
}

private val GithubIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Github",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(6.477f, 2f, 2f, 6.484f, 2f, 12.017f)
        curveTo(2f, 16.442f, 4.865f, 20.197f, 8.839f, 21.521f)
        curveTo(9.339f, 21.613f, 9.521f, 21.304f, 9.521f, 21.038f)
        curveTo(9.521f, 20.801f, 9.513f, 20.17f, 9.508f, 19.335f)
        curveTo(6.726f, 19.94f, 6.139f, 17.992f, 6.139f, 17.992f)
        curveTo(5.685f, 16.834f, 5.029f, 16.526f, 5.029f, 16.526f)
        curveTo(4.121f, 15.906f, 5.09f, 15.918f, 5.09f, 15.918f)
        curveTo(6.093f, 15.988f, 6.62f, 16.95f, 6.62f, 16.95f)
        curveTo(7.512f, 18.48f, 8.961f, 18.038f, 9.53f, 17.782f)
        curveTo(9.622f, 17.135f, 9.88f, 16.694f, 10.166f, 16.444f)
        curveTo(7.946f, 16.191f, 5.611f, 15.331f, 5.611f, 11.493f)
        curveTo(5.611f, 10.4f, 6.001f, 9.505f, 6.64f, 8.805f)
        curveTo(6.537f, 8.552f, 6.194f, 7.533f, 6.738f, 6.155f)
        curveTo(6.738f, 6.155f, 7.578f, 5.885f, 9.488f, 7.181f)
        curveTo(10.287f, 6.959f, 11.142f, 6.848f, 11.992f, 6.844f)
        curveTo(12.842f, 6.848f, 13.697f, 6.959f, 14.496f, 7.181f)
        curveTo(16.406f, 5.885f, 17.246f, 6.155f, 17.246f, 6.155f)
        curveTo(17.79f, 7.533f, 17.447f, 8.552f, 17.344f, 8.805f)
        curveTo(17.983f, 9.505f, 18.373f, 10.4f, 18.373f, 11.493f)
        curveTo(18.373f, 15.341f, 16.034f, 16.188f, 13.808f, 16.436f)
        curveTo(14.167f, 16.745f, 14.486f, 17.356f, 14.486f, 18.291f)
        curveTo(14.486f, 19.629f, 14.474f, 20.71f, 14.474f, 21.038f)
        curveTo(14.474f, 21.306f, 14.654f, 21.618f, 15.162f, 21.52f)
        curveTo(19.135f, 20.194f, 22f, 16.441f, 22f, 12.017f)
        curveTo(22f, 6.484f, 17.523f, 2f, 12f, 2f)
        close()
    }.build()
}
