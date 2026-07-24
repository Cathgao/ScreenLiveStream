package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
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
import com.example.service.QuestReceiverService
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel

@Composable
fun ReceiverScreen(
    viewModel: MainViewModel,
    receiverService: QuestReceiverService?,
    isListening: Boolean,
    onStartListening: (Int) -> Unit,
    onStopListening: () -> Unit
) {
    val receiverConfig by viewModel.receiverConfig.collectAsState()
    val stats by viewModel.receiverStats.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()

    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(receiverService) {
        receiverService?.udpReceiver?.onStatsUpdated = { newStats ->
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
        }
    }

    LaunchedEffect(isListening, videoWidth, videoHeight) {
        if (isListening && videoWidth > 0 && videoHeight > 0) {
            val isVideoLandscape = videoWidth > videoHeight
            activity?.requestedOrientation = if (isVideoLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video Viewport using AndroidView SurfaceView
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
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

        // Floating HUD Overlay (Always Visible, Backgroundless, semi-transparent compact layout)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Transparent)
                .padding(4.dp)
        ) {
            CompactHudStatItem(label = "FPS:", value = String.format("%.1f", stats.fps), color = NeonCyan.copy(alpha = 0.8f))
            Text("|", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
            CompactHudStatItem(label = "码率:", value = String.format("%.1fM", stats.bitrateMbps), color = NeonPurple.copy(alpha = 0.8f))
            Text("|", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
            CompactHudStatItem(label = "延迟:", value = "${stats.latencyMs}ms", color = LiveGreen.copy(alpha = 0.8f))
            Text("|", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
            CompactHudStatItem(label = "丢包:", value = String.format("%.1f%%", stats.packetLossPercent), color = TextSecondary.copy(alpha = 0.8f))
            if (videoWidth > 0 && videoHeight > 0) {
                Text("|", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
                CompactHudStatItem(
                    label = "格式:",
                    value = "${videoWidth}x${videoHeight} (${if (videoWidth > videoHeight) "横屏" else "竖屏"})",
                    color = NeonCyan.copy(alpha = 0.8f)
                )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "本地 IP: $localIp",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "端口: ${receiverConfig.listenPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    Button(
                        onClick = {
                            onStartListening(receiverConfig.listenPort)
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

@Composable
private fun CompactHudStatItem(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
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
