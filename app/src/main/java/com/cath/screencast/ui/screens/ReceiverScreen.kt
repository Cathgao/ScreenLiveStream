package com.cath.screencast.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cath.screencast.service.QuestReceiverService
import com.cath.screencast.ui.theme.*
import com.cath.screencast.viewmodel.MainViewModel
import com.cath.screencast.model.StreamStats

@Composable
fun ReceiverStatsOverlay(viewModel: MainViewModel, videoWidth: Int, videoHeight: Int) {
    val stats by viewModel.receiverStats.collectAsState()
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FPS:${String.format("%.1f", stats.fps)}",
            fontSize = 8.sp,
            lineHeight = 8.sp,
            color = Color.White
        )
        Text(
            text = "码率:${String.format("%.1fM", stats.bitrateMbps)}",
            fontSize = 8.sp,
            lineHeight = 8.sp,
            color = Color.White
        )
        Text(
            text = "Ping:${stats.rttMs}ms",
            fontSize = 8.sp,
            lineHeight = 8.sp,
            color = Color.White
        )
        if (videoWidth > 0 && videoHeight > 0) {
            Text(
                text = "${videoWidth}x${videoHeight}",
                fontSize = 8.sp,
                lineHeight = 8.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun ReceiverScreen(
    viewModel: MainViewModel,
    receiverService: QuestReceiverService?,
    isListening: Boolean,
    onStartListening: (port: Int, isRecordEnabled: Boolean) -> Unit,
    onStopListening: () -> Unit
) {
    val receiverConfig by viewModel.receiverConfig.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()

    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var isRecordEnabled by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(receiverService) {
        receiverService?.onStatsUpdated = { newStats ->
            viewModel.updateReceiverStats(newStats)
        }
        receiverService?.onVideoSizeChanged = { w, h ->
            videoWidth = w
            videoHeight = h
        }
    }

    LaunchedEffect(isListening) {
        if (!isListening) {
            videoWidth = 0
            videoHeight = 0
            viewModel.updateReceiverStats(StreamStats())
        }
    }

    LaunchedEffect(isListening, videoWidth, videoHeight) {
        if (isListening && videoWidth > 0 && videoHeight > 0) {
            val isVideoLandscape = videoWidth > videoHeight
            activity?.requestedOrientation = if (isVideoLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        } else if (!isListening) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isListening) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        fun setImmersiveMode(enable: Boolean) {
            if (window == null || insetsController == null) return
            if (enable) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isListening) {
                    setImmersiveMode(true)
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                if (isListening) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        setImmersiveMode(isListening)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            setImmersiveMode(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isListening) {
            // Video Viewport using AndroidView SurfaceView
            AndroidView(
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (videoWidth > 0 && videoHeight > 0) {
                            Modifier.aspectRatio(videoWidth.toFloat() / videoHeight.toFloat())
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
                    .testTag("receiver_surface_view"),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                receiverService?.bindSurface(holder.surface)
                            }
    
                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                            }
    
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                receiverService?.unbindSurface()
                            }
                        })
                    }
                },
                update = { surfaceView ->
                    val holder = surfaceView.holder
                    if (holder.surface.isValid) {
                        receiverService?.bindSurface(holder.surface)
                    }
                }
            )
        }

        if (isListening) {
            // Data stats Overlay
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                ReceiverStatsOverlay(viewModel, videoWidth, videoHeight)
            }
        }

        if (isListening) {
            // Small circular red stop button in the bottom right corner
            Button(
                onClick = { onStopListening() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    contentColor = Color.White
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(48.dp)
                    .testTag("receiver_stop_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Listening",
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            // Floating Controls Bar
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCyberSurface.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("协议:", fontSize = 12.sp, color = TextSecondary)
                        com.cath.screencast.model.TransportProtocol.values().forEach { proto ->
                            val selected = receiverConfig.protocol == proto
                            Surface(
                                color = if (selected) NeonCyan else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, BorderCyan),
                                modifier = Modifier.clickable { viewModel.updateReceiverProtocol(proto) }
                            ) {
                                Text(
                                    text = proto.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.Black else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Recording switch above start button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = if (isRecordEnabled) LiveGreen else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "同时录制到本地",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "保存至 Movies/QuestCast/",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = isRecordEnabled,
                            onCheckedChange = { isRecordEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = LiveGreen
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = "本地 IP: $localIp",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "端口: ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                var portText by remember(receiverConfig.listenPort) { mutableStateOf(receiverConfig.listenPort.toString()) }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = portText,
                                    onValueChange = { 
                                        portText = it
                                        val p = it.toIntOrNull()
                                        if (p != null) {
                                            viewModel.updateReceiverPort(p)
                                        }
                                    },
                                    textStyle = MaterialTheme.typography.labelSmall.copy(color = NeonCyan),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                    ),
                                    modifier = Modifier
                                        .width(50.dp)
                                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                onStartListening(receiverConfig.listenPort, isRecordEnabled)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LiveGreen,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("receiver_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "启动接收",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
