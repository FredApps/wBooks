package com.wbooks.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import com.wbooks.data.position.BookPosition
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Document
import com.wbooks.parser.model.flatIndexOf
import com.wbooks.parser.model.positionAt
import com.wbooks.ui.ReaderViewModel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Normal reading mode. Flat LazyColumn over all blocks with bezel + tap + autoscroll.
 *
 * Interactions:
 *  - Swipe: smooth scroll (LazyColumn default).
 *  - Rotary bezel: incremental scroll via [Modifier.rotaryScrollable].
 *  - Tap: when autoscroll is OFF, advance one page (90% of viewport). When
 *    autoscroll is ON, pause/resume the timer.
 *  - Autoscroll: a LaunchedEffect driven by [ReaderSettings.autoscrollSpeed]
 *    (mapped roughly to pixels-per-second) calls animateScrollBy in small steps.
 *
 * Position plumbing:
 *  - Restore: on first compose with a doc, scroll to vm.currentPosition.
 *  - Save: snapshotFlow on firstVisibleItemIndex, debounced, reports back.
 *  - Jump: collect vm.jumps and scroll to that position immediately.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun NormalMode(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: ReaderViewModel,
) {
    val blocks = remember(document) { document.chapters.flatMap { it.blocks } }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // ---- Restore last position when the document changes (i.e. a new book opens). ----
    LaunchedEffect(document) {
        val flat = document.flatIndexOf(initialPosition)
        if (flat in blocks.indices) listState.scrollToItem(flat)
    }

    // ---- Listen for explicit jumps (chapter list, bookmark tap). ----
    LaunchedEffect(document) {
        vm.jumps.collect { target ->
            val flat = document.flatIndexOf(target)
            if (flat in blocks.indices) listState.animateScrollToItem(flat)
        }
    }

    // ---- Save position as the user scrolls. drop(1) skips the initial restore. ----
    LaunchedEffect(document) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .distinctUntilChanged()
            .debounce(500)
            .collect { flat -> vm.reportPosition(document.positionAt(flat)) }
    }

    // ---- Autoscroll. ----
    var autoscrollPaused by remember { mutableStateOf(false) }
    LaunchedEffect(settings.autoscrollEnabled, settings.autoscrollSpeed, autoscrollPaused) {
        if (!settings.autoscrollEnabled || autoscrollPaused) return@LaunchedEffect
        // 1..60 → ~10..240 px/s, smooth at any speed.
        val pxPerSec = (settings.autoscrollSpeed * 4f).coerceAtLeast(8f)
        val stepMs = 50L
        val pxPerStep = pxPerSec * stepMs / 1000f
        while (true) {
            listState.scrollBy(pxPerStep)
            kotlinx.coroutines.delay(stepMs)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester)
            .pointerInput(settings.autoscrollEnabled) {
                detectTapGestures(onTap = {
                    if (settings.autoscrollEnabled) {
                        autoscrollPaused = !autoscrollPaused
                    } else {
                        scope.launch {
                            val info = listState.layoutInfo
                            val pageStep = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                            if (pageStep > 0f) listState.animateScrollBy(pageStep * 0.9f)
                        }
                    }
                })
            },
    ) {
        items(count = blocks.size) { index ->
            BlockView(block = blocks[index], settings = settings)
        }
    }
}

/** Non-animated scroll helper — animateScrollBy snaps too coarsely for autoscroll's tiny steps. */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollBy(pixels: Float) {
    scroll { scrollBy(pixels) }
}
