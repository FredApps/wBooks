package com.wbooks.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WBooksColors = Colors(
    primary = androidx.compose.ui.graphics.Color(0xFFE8E6E1),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF101013),
    background = androidx.compose.ui.graphics.Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE8E6E1),
    surface = androidx.compose.ui.graphics.Color(0xFF101013),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE8E6E1),
)

@Composable
fun WBooksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WBooksColors, content = content)
}
