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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import com.wbooks.data.settings.ReaderSettings
import com.wbooks.parser.model.Document
import kotlinx.coroutines.launch

/**
 * Normal reading mode — a flat list of [com.wbooks.parser.model.Block]s rendered via [BlockView].
 *
 * Interactions per the design spec:
 *  - Swipe: smooth scroll (native LazyColumn behaviour).
 *  - Tap: ~one-page advance (animateScrollBy 90% of viewport height).
 *  - Rotary bezel: incremental scroll via [Modifier.rotaryScrollable].
 *
 * Chapters are flattened. When bookmarks/positions land we'll switch to an index
 * that knows where chapter boundaries fall; for rendering they're just concatenated.
 */
@Composable
fun NormalMode(
    document: Document,
    settings: ReaderSettings,
) {
    val blocks = remember(document) { document.chapters.flatMap { it.blocks } }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    scope.launch {
                        val info = listState.layoutInfo
                        val pageStep = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                        if (pageStep > 0f) listState.animateScrollBy(pageStep * 0.9f)
                    }
                })
            },
    ) {
        items(count = blocks.size) { index ->
            BlockView(block = blocks[index], settings = settings)
        }
    }
}
