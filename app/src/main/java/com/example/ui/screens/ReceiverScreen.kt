package com.example.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    var showHud by remember { mutableStateOf(true) }

    LaunchedEffect(receiverService) {
        receiverService?.udpReceiver?.onStatsUpdated = { newStats ->
            viewModel.updateReceiverStats(newStats)
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

        // Floating HUD Overlay
        AnimatedVisibility(
            visible = showHud,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCyberSurface.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (stats.fps > 5f) LiveGreen else AlertAmber)
                        )
                        Text(
                            text = if (isListening) "接收监听中 ($localIp:${receiverConfig.listenPort})" else "未启动接收监听",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudStatItem(label = "FPS", value = String.format("%.1f", stats.fps), color = NeonCyan)
                        HudStatItem(label = "码率", value = String.format("%.1f M", stats.bitrateMbps), color = NeonPurple)
                        HudStatItem(label = "延迟", value = "${stats.latencyMs} ms", color = LiveGreen)
                        HudStatItem(label = "丢包", value = String.format("%.1f%%", stats.packetLossPercent), color = TextSecondary)
                    }
                }
            }
        }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHud = !showHud }) {
                        Icon(
                            imageVector = if (showHud) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle HUD",
                            tint = NeonCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
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
                }

                Button(
                    onClick = {
                        if (isListening) {
                            onStopListening()
                        } else {
                            onStartListening(receiverConfig.listenPort)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) ErrorRed else LiveGreen,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("receiver_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isListening) "停止监听" else "启动接收",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HudStatItem(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
