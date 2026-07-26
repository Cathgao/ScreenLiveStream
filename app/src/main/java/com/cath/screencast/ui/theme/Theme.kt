package com.cath.screencast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val QuestDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003840),
    onPrimaryContainer = NeonCyan,
    secondary = NeonPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF38124A),
    onSecondaryContainer = NeonPurple,
    tertiary = LiveGreen,
    background = DarkObsidian,
    onBackground = TextPrimary,
    surface = DarkCyberSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkCyberCard,
    onSurfaceVariant = TextSecondary,
    outline = BorderCyan
)

@Composable
fun QuestCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = QuestDarkColorScheme,
        typography = Typography,
        content = content
    )
}
