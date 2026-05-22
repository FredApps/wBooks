package com.fredapp.wbooks.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROTARY_FOCUS_SETTLE_DELAY_MS = 80L
private val ROTARY_FOCUS_RETRY_DELAYS_MS = longArrayOf(0L, 80L, 200L, 450L, 900L)

/**
 * A counter that increments on every Activity ON_RESUME. Use it as a key so
 * rotary focus can be reclaimed when the app returns from the background.
 */
@Composable
internal fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

/**
 * Requests focus after Compose has had a frame, plus a tiny delay for pager
 * settle/disposal. Immediate requestFocus() calls can lose to an outgoing menu
 * or InlineSlider during horizontal swipe-back, leaving the bezel unowned.
 */
private suspend fun claimRotaryFocusOnce(focusRequester: FocusRequester, delayMs: Long) {
    withFrameNanos { }
    if (delayMs > 0L) delay(delayMs)
    runCatching { focusRequester.requestFocus() }
}

internal suspend fun claimRotaryFocusAfterSettle(focusRequester: FocusRequester) {
    claimRotaryFocusOnce(focusRequester, ROTARY_FOCUS_SETTLE_DELAY_MS)
}

private suspend fun claimRotaryFocusWithRetries(focusRequester: FocusRequester) {
    for (delayMs in ROTARY_FOCUS_RETRY_DELAYS_MS) {
        claimRotaryFocusOnce(focusRequester, delayMs)
    }
}

/**
 * Runs [onActivated] only when this rotary owner becomes active, then claims
 * focus. Focus is also reclaimed after Activity resume or optional local state
 * transitions, without re-running activation side effects such as scroll-to-top.
 */
@Composable
internal fun ClaimRotaryFocusOnActive(
    active: Boolean,
    focusRequester: FocusRequester,
    vararg refocusKeys: Any?,
    onActivated: suspend () -> Unit = {},
) {
    val resumeTick = rememberResumeTick()
    val latestOnActivated by rememberUpdatedState(onActivated)

    LaunchedEffect(active) {
        if (active) {
            latestOnActivated()
            claimRotaryFocusWithRetries(focusRequester)
        }
    }

    LaunchedEffect(active, resumeTick, refocusKeys.toList()) {
        if (active) {
            claimRotaryFocusWithRetries(focusRequester)
        }
    }
}

/**
 * Keep rotary list scrolling owned by the page-level list even when children
 * such as sliders or text fields can receive focus or handle touch gestures.
 */
@Composable
internal fun Modifier.pageRotaryScrollOwner(state: ScalingLazyListState): Modifier {
    val scope = rememberCoroutineScope()

    fun scrollBy(scrollPixels: Float): Boolean {
        if (scrollPixels == 0f) return false
        scope.launch { state.scrollBy(scrollPixels) }
        return true
    }

    return this
        .onPreRotaryScrollEvent { event -> scrollBy(event.verticalScrollPixels) }
        .onRotaryScrollEvent { event -> scrollBy(event.verticalScrollPixels) }
}

private suspend fun ScalingLazyListState.scrollBy(pixels: Float) {
    scroll { scrollBy(pixels) }
}
