package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.example.service.QuestReceiverService
import com.example.service.QuestSenderService
import com.example.ui.screens.ReceiverScreen
import com.example.ui.screens.SenderScreen
import com.example.ui.theme.*
import com.example.viewmodel.AppMode
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var senderService: QuestSenderService? = null
    private var isSenderBound = false

    private var receiverService: QuestReceiverService? = null
    private var isReceiverBound = false

    private var isSenderStreamingState by mutableStateOf(false)
    private var isReceiverListeningState by mutableStateOf(false)

    private val senderConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as QuestSenderService.LocalBinder
            senderService = binder.getService()
            isSenderBound = true
            isSenderStreamingState = binder.getService().isStreaming
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            senderService = null
            isSenderBound = false
            isSenderStreamingState = false
        }
    }

    private val receiverConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as QuestReceiverService.LocalBinder
            val svc = binder.getService()
            receiverService = svc
            isReceiverBound = true
            isReceiverListeningState = svc.isListening
            svc.onListeningStateChanged = { listening ->
                runOnUiThread {
                    isReceiverListeningState = listening
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            isReceiverBound = false
            isReceiverListeningState = false
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestPermissions()

        // Bind Services
        val senderIntent = Intent(this, QuestSenderService::class.java)
        bindService(senderIntent, senderConnection, Context.BIND_AUTO_CREATE)

        val receiverIntent = Intent(this, QuestReceiverService::class.java)
        bindService(receiverIntent, receiverConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val density = LocalDensity.current
            // Clamp font scale to prevent large text from breaking the layout on different DPI/font settings
            val customDensity = Density(
                density = density.density,
                fontScale = density.fontScale.coerceIn(0.85f, 1.15f)
            )
            CompositionLocalProvider(LocalDensity provides customDensity) {
                QuestCastTheme {
                    val currentMode by viewModel.currentMode.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkObsidian,
                    bottomBar = {
                        if (!(currentMode == AppMode.MOBILE_RECEIVER && isReceiverListeningState)) {
                            NavigationBar(
                                containerColor = DarkCyberSurface,
                                contentColor = TextPrimary,
                                modifier = Modifier.testTag("app_navigation_bar")
                            ) {
                                val senderLabel = if (viewModel.isQuestDevice) "Quest 发送端" else "发送端"
                                val receiverLabel = if (viewModel.isQuestDevice) "手机接收端" else "接收端"

                                NavigationBarItem(
                                    selected = currentMode == AppMode.QUEST_SENDER,
                                    onClick = { viewModel.setAppMode(AppMode.QUEST_SENDER) },
                                    icon = { Icon(Icons.Default.Cast, contentDescription = "Sender") },
                                    label = { Text(senderLabel, fontWeight = FontWeight.Bold) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentMode == AppMode.MOBILE_RECEIVER,
                                    onClick = { viewModel.setAppMode(AppMode.MOBILE_RECEIVER) },
                                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = "Receiver") },
                                    label = { Text(receiverLabel, fontWeight = FontWeight.Bold) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = LiveGreen,
                                        indicatorColor = LiveGreen,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentMode) {
                            AppMode.QUEST_SENDER -> {
                                SenderScreen(
                                    viewModel = viewModel,
                                    isStreaming = isSenderStreamingState,
                                    onStartStreamRequested = { intent ->
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(intent)
                                        } else {
                                            startService(intent)
                                        }
                                        isSenderStreamingState = true
                                    },
                                    onStopStreamRequested = {
                                        val stopIntent = Intent(this@MainActivity, QuestSenderService::class.java).apply {
                                            action = QuestSenderService.ACTION_STOP
                                        }
                                        startService(stopIntent)
                                        isSenderStreamingState = false
                                    }
                                )
                            }
                            AppMode.MOBILE_RECEIVER -> {
                                ReceiverScreen(
                                    viewModel = viewModel,
                                    receiverService = receiverService,
                                    isListening = isReceiverListeningState,
                                    onStartListening = { port, record ->
                                        val serviceIntent = Intent(this@MainActivity, QuestReceiverService::class.java).apply {
                                            putExtra(QuestReceiverService.EXTRA_LISTEN_PORT, port)
                                            putExtra(QuestReceiverService.EXTRA_RECORD_ENABLED, record)
                                            putExtra(QuestReceiverService.EXTRA_JITTER_BUFFER_MS, viewModel.receiverConfig.value.jitterBufferMs)
                                            putExtra(QuestReceiverService.EXTRA_PROTOCOL, viewModel.receiverConfig.value.protocol.name)
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(serviceIntent)
                                        } else {
                                            startService(serviceIntent)
                                        }
                                        isReceiverListeningState = true
                                    },
                                    onStopListening = {
                                        receiverService?.stopListening()
                                        stopService(Intent(this@MainActivity, QuestReceiverService::class.java))
                                        isReceiverListeningState = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        if (isSenderBound) {
            unbindService(senderConnection)
            isSenderBound = false
        }
        if (isReceiverBound) {
            unbindService(receiverConnection)
            isReceiverBound = false
        }
        super.onDestroy()
    }
}
