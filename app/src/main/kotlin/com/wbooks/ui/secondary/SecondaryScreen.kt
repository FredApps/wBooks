package com.wbooks.ui.secondary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.R
import com.wbooks.data.book.Book

/**
 * The "swipe-right" page (page 0 in the pager): search, bookmark-here, bookmarks list, chapters.
 * Selecting Search resets the reader to normal mode per the design spec.
 */
@Composable
fun SecondaryScreen(book: Book) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
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
            item {
                Chip(
                    label = { Text(stringResource(R.string.tools_chapters)) },
                    onClick = { /* TODO open chapter list */ },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}
