package com.wbooks.ui.secondary

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.wbooks.ui.SearchResult
import java.text.DateFormat
import java.util.Date

/**
 * Page 0 — Tools. Either the standard tools/bookmarks/chapters list, or, when
 * a search query is active, the search results.
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

        val query by vm.searchQuery.collectAsState()
        val results by vm.searchResults.collectAsState()

        if (query.isNotBlank()) {
            SearchResultsList(
                query = query,
                results = results,
                onOpen = vm::openSearchResult,
                onClear = vm::clearSearch,
            )
            return@Scaffold
        }

        // Voice search via the platform speech recognizer. On Wear OS without a
        // recognizer installed the launch throws; we catch it and route the user
        // to the typed-input panel as a fallback.
        val searchLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) vm.runSearch(text)
        }

        var searchPanelOpen by remember { mutableStateOf(false) }
        if (searchPanelOpen) {
            SearchPanel(
                onVoice = {
                    runCatching { searchLauncher.launch(buildSearchIntent()) }
                        .onFailure { /* no speech recognizer: stay on the typed panel */ }
                },
                onSubmit = { typed ->
                    if (typed.isNotBlank()) {
                        vm.runSearch(typed)
                        searchPanelOpen = false
                    }
                },
                onCancel = { searchPanelOpen = false },
            )
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
                    onClick = { searchPanelOpen = true },
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

@Composable
private fun SearchResultsList(
    query: String,
    results: List<SearchResult>,
    onOpen: (SearchResult) -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text("\"$query\" · ${results.size}") } }
        item {
            Chip(
                label = { Text("Clear") },
                onClick = onClear,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        if (results.isEmpty()) {
            item {
                Text(
                    text = "No matches",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(results, key = { "${it.position.chapterIndex}-${it.position.blockIndex}-${it.snippet.hashCode()}" }) { r ->
                Chip(
                    label = { Text(r.snippet) },
                    secondaryLabel = { Text("Ch ${r.position.chapterIndex + 1}") },
                    onClick = { onOpen(r) },
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
    chapter.blocks.firstOrNull { it is Block.Heading }?.let { heading ->
        return (heading as Block.Heading).text
    }
    return "Chapter ${index + 1}"
}

private fun buildSearchIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Search")
}

/**
 * Search entry point that works on every Wear OS install: a Voice chip (which
 * delegates to the system speech recognizer when present) plus a `BasicTextField`
 * for typed input. Watches without a speech recognizer fall through to the type
 * field; watches without a system IME at all are rare on Wear OS 3+.
 */
@Composable
private fun SearchPanel(
    onVoice: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colors.onSurface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search")
        Chip(
            label = { Text("Voice") },
            onClick = onVoice,
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, onSurface.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = onSurface, fontSize = 14.sp, textAlign = TextAlign.Start),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSubmit(query)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Chip(
                label = { Text("Go") },
                onClick = { onSubmit(query) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.weight(1f),
            )
            Chip(
                label = { Text("Cancel") },
                onClick = onCancel,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
