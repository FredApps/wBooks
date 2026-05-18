package com.wbooks.ui.secondary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.R
import com.wbooks.data.bookmarks.Bookmark
import com.wbooks.data.position.BookPosition
import com.wbooks.parser.model.Block
import com.wbooks.ui.DocumentState
import com.wbooks.ui.ReaderViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Page 0 — Tools. Search, bookmark-here, bookmarks list, chapter list. The
 * bookmarks list uses combinedClickable so a long press triggers a confirm
 * step (per spec). All actions route through [ReaderViewModel].
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SecondaryScreen(state: DocumentState, vm: ReaderViewModel) {
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        if (state !is DocumentState.Loaded) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Open a book to see tools")
            }
            return@Scaffold
        }

        val bookmarks by vm.bookmarks.collectAsState()
        var pendingDelete by remember { mutableStateOf<BookPosition?>(null) }

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
                    onClick = { vm.bookmarkHere() },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }

            if (bookmarks.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.tools_bookmarks)) } }
                items(bookmarks, key = { "bm:${it.position.chapterIndex}-${it.position.blockIndex}" }) { bm ->
                    BookmarkRow(
                        bookmark = bm,
                        pendingDelete = pendingDelete,
                        onJump = { vm.jumpTo(bm.position) },
                        onRequestDelete = { pendingDelete = bm.position },
                        onConfirmDelete = {
                            vm.deleteBookmark(bm.position)
                            pendingDelete = null
                        },
                        onCancelDelete = { pendingDelete = null },
                    )
                }
            }

            item { ListHeader { Text(stringResource(R.string.tools_chapters)) } }
            val chapters = state.doc.chapters.withIndex().toList()
            items(chapters, key = { it.index }) { (idx, chapter) ->
                Chip(
                    label = { Text(chapterDisplay(chapter, idx)) },
                    onClick = { vm.jumpTo(BookPosition(chapterIndex = idx, blockIndex = 0)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    pendingDelete: BookPosition?,
    onJump: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    val isPending = pendingDelete == bookmark.position
    val label = bookmark.label
        ?: "Chapter ${bookmark.position.chapterIndex + 1} · §${bookmark.position.blockIndex + 1}"
    val subtitle = remember(bookmark.savedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(bookmark.savedAtMs))
    }

    if (isPending) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(text = "Delete?", modifier = Modifier.padding(end = 4.dp))
            Chip(
                label = { Text("Yes") },
                onClick = onConfirmDelete,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.padding(end = 2.dp),
            )
            Chip(
                label = { Text("No") },
                onClick = onCancelDelete,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    } else {
        Chip(
            label = { Text(label) },
            secondaryLabel = { Text(subtitle) },
            onClick = onJump,
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.combinedClickable(
                onClick = onJump,
                onLongClick = onRequestDelete,
            ),
        )
    }
}

private fun chapterDisplay(chapter: com.wbooks.parser.model.Chapter, index: Int): String {
    chapter.title?.takeIf { it.isNotBlank() }?.let { return it }
    // Fall back: first heading inside the chapter, then a generic name.
    chapter.blocks.firstOrNull { it is Block.Heading }?.let { heading ->
        return (heading as Block.Heading).text
    }
    return "Chapter ${index + 1}"
}
