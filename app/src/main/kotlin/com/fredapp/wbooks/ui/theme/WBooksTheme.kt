package com.fredapp.wbooks.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val DarkColors = Colors(
    primary = Color(0xFFE8E6E1),
    onPrimary = Color(0xFF101013),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF101013),
    onSurface = Color(0xFFE8E6E1),
)

/**
 * App theme. Dark-only: the watch's OLED background draws best at zero
 * luminance and the previous LIGHT / SYSTEM options have been retired. The
 * caller can still tint the text via [textColor]; that flows into the
 * Material `primary` / `onBackground` / `onSurface` slots without changing
 * the underlying dark surface palette.
 */
@Composable
fun WBooksTheme(
    textColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = textColor?.let {
        DarkColors.copy(
            primary = it,
            onBackground = it,
            onSurface = it,
        )
    } ?: DarkColors
    MaterialTheme(colors = colors, content = content)
}
