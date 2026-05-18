package com.wbooks.ui.secondary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.R
import com.wbooks.ui.DocumentState

/**
 * Page 0 (swipe right from the reader): search, bookmark-here, bookmarks list, chapter list.
 * Once a document is loaded, the chapter list comes from the doc itself.
 */
@Composable
fun SecondaryScreen(state: DocumentState) {
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        if (state !is DocumentState.Loaded) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Open a book to see tools")
            }
            return@Scaffold
        }
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Chip(
                    label = { Text(stringResource(R.string.tools_search)) },
                    onClick = { /* TODO open search */ },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.tools_bookmark_here)) },
                    onClick = { /* TODO bookmark current position */ },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.tools_bookmarks)) },
                    onClick = { /* TODO open bookmarks list */ },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item { ListHeader { Text(stringResource(R.string.tools_chapters)) } }
            val chapters = state.doc.chapters
            items(chapters.withIndex().toList(), key = { it.index }) { (idx, chapter) ->
                Chip(
                    label = { Text(chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}") },
                    onClick = { /* TODO jump reader to this chapter */ },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}
