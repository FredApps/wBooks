package com.fredapp.wbooks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import com.fredapp.wbooks.data.settings.ThemeChoice

private val DarkColors = Colors(
    primary = Color(0xFFE8E6E1),
    onPrimary = Color(0xFF101013),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF101013),
    onSurface = Color(0xFFE8E6E1),
)

private val LightColors = Colors(
    primary = Color(0xFF1A1A1F),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F2EC),
    onBackground = Color(0xFF1A1A1F),
    surface = Color(0xFFE6E2DA),
    onSurface = Color(0xFF1A1A1F),
)

/**
 * App theme. The [choice] is the persisted user preference; SYSTEM defers to
 * [isSystemInDarkTheme]. On Wear OS the system theme is almost always dark
 * (for battery), but we honour it for correctness.
 */
@Composable
fun WBooksTheme(
    choice: ThemeChoice = ThemeChoice.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colors = if (dark) DarkColors else LightColors, content = content)
}
