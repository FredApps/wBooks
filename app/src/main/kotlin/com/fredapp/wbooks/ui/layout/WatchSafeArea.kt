package com.fredapp.wbooks.ui.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun watchContentPadding(
    horizontal: Dp = 12.dp,
    vertical: Dp = 12.dp,
): PaddingValues {
    val round = LocalConfiguration.current.isScreenRound
    return if (round) {
        PaddingValues(horizontal = maxDp(horizontal, 24.dp), vertical = maxDp(vertical, 24.dp))
    } else {
        PaddingValues(horizontal = horizontal, vertical = vertical)
    }
}

@Composable
internal fun watchListPadding(
    start: Dp = 8.dp,
    top: Dp = 12.dp,
    end: Dp = 8.dp,
    bottom: Dp = 32.dp,
): PaddingValues {
    val round = LocalConfiguration.current.isScreenRound
    return if (round) {
        PaddingValues(
            start = maxDp(start, 24.dp),
            top = maxDp(top, 28.dp),
            end = maxDp(end, 24.dp),
            bottom = maxDp(bottom, 36.dp),
        )
    } else {
        PaddingValues(start = start, top = top, end = end, bottom = bottom)
    }
}

@Composable
internal fun watchReaderPadding(
    start: Dp = 14.dp,
    top: Dp = 48.dp,
    end: Dp = 14.dp,
    bottom: Dp = 24.dp,
): PaddingValues {
    val round = LocalConfiguration.current.isScreenRound
    return if (round) {
        PaddingValues(
            start = maxDp(start, 24.dp),
            top = maxDp(top, 48.dp),
            end = maxDp(end, 24.dp),
            bottom = maxDp(bottom, 32.dp),
        )
    } else {
        PaddingValues(start = start, top = top, end = end, bottom = bottom)
    }
}

private fun maxDp(a: Dp, b: Dp): Dp = if (a > b) a else b
