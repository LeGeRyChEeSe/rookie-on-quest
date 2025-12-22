package com.vrpirates.rookieonquest.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VrpColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), // White text
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A), // Dark grey bar
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF3498db), // Blue accent
    background = Color(0xFF000000), // Black background
    surface = Color(0xFF0F0F0F), // Slightly lighter black for cards
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    error = Color(0xFFCF6679)
)

@Composable
fun RookieOnQuestTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VrpColorScheme,
        content = content
    )
}
