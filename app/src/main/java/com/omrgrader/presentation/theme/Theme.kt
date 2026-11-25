package com.omrgrader.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.omrgrader.data.preferences.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF14507A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8F7), onPrimaryContainer = Color(0xFF05283F),
    secondary = Color(0xFF4C6575), tertiary = Color(0xFF9B4D00),
    background = Color(0xFFF7FAFC), surface = Color.White,
    error = Color(0xFFB3261E)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC9EE), onPrimary = Color(0xFF062438),
    primaryContainer = Color(0xFF0D415F), onPrimaryContainer = Color(0xFFD3E8F7),
    secondary = Color(0xFFB4C8D6), tertiary = Color(0xFFFFB875),
    background = Color(0xFF10181E), surface = Color(0xFF17222B),
    error = Color(0xFFFFB4AB)
)

@Composable
fun OpenScantronTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
