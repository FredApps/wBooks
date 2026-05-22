package com.fredapp.wbooks.ui.secondary

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.data.bookmarks.Bookmark
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.data.stats.formatDurationMs
import com.fredapp.wbooks.parser.model.ChapterJump
import com.fredapp.wbooks.parser.model.DocumentMetrics
import com.fredapp.wbooks.ui.DocumentState
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.SearchResult
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.focus.claimRotaryFocusAfterSettle
import com.fredapp.wbooks.ui.layout.watchListPadding
import java.text.DateFormat
import java.util.Date

private val FolderGrey = Color(0xFFB0B0B0)
private val FolderGreyText = Color(0xFF1C1C1C)
private val BookmarkRowBackground = Color(0xFF2A2A2A)

/**
 * Page 0 â€” Tools. Either the standard tools/bookmarks/chapters list, or, when
 * a search query is active, the search results.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SecondaryScreen(
    state: DocumentState,
    vm: ReaderViewModel,
    onSearchActiveChanged: (Boolean) -> Unit,
    onReaderPageRequested: () -> Unit,
    isActive: Boolean = true,
) {
    val listState = rememberScalingLazyListState()
    val searchResultsListState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    Scaffold(timeText = { TimeText() }) {
        if (state !is DocumentState.Loaded) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Open a book to see tools")
            }
            return@Scaffold
        }

        val query by vm.searchQuery.collectAsState()
        val results by vm.searchResults.collectAsState()
        var searchPanelOpen by remember { mutableStateOf(false) }
        var lastSearchResultsResetQuery by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(searchPanelOpen, query) {
            onSearchActiveChanged(searchPanelOpen || query.isNotBlank())
        }

        if (query.isNotBlank()) {
            SearchResultsList(
                query = query,
                results = results,
                listState = searchResultsListState,
                shouldResetToTop = lastSearchResultsResetQuery != query,
                onResetToTop = { lastSearchResultsResetQuery = query },
                onOpen = { result ->
                    vm.openSearchResult(result)
                    onReaderPageRequested()
                },
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
        val eta by vm.readingEta.collectAsState()
        var pendingDelete by remember { mutableStateOf<BookPosition?>(null) }
        var bookmarkedFlashAt by remember { mutableStateOf(0L) }
        val flashVisible = remember(bookmarkedFlashAt, bookmarks) {
            bookmarkedFlashAt != 0L && bookmarks.any { it.savedAtMs >= bookmarkedFlashAt }
        }
        LaunchedEffect(bookmarkedFlashAt) {
            if (bookmarkedFlashAt != 0L) {
                kotlinx.coroutines.delay(1500)
                bookmarkedFlashAt = 0L
            }
        }
        // Metrics are pre-computed on Dispatchers.Default by the VM when the
        // doc loads. Until they arrive (~tens of ms after the reader paints)
        // we still render the page — just without word/sentence labels.
        val metrics: DocumentMetrics? = state.metrics
        val chapters: List<ChapterJump> = metrics?.chapterJumps ?: emptyList()

        // Swiping into Tools resets the list once, then claims focus after the
        // pager has released the previous rotary owner.
        ClaimRotaryFocusOnActive(
            active = isActive,
            focusRequester = focusRequester,
            onActivated = { listState.scrollToItem(0) },
        )

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompactChip(
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("Back", color = FolderGreyText)
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Back",
                                    tint = FolderGreyText,
                                )
                            }
                        },
                        onClick = onReaderPageRequested,
                        colors = ChipDefaults.chipColors(backgroundColor = FolderGrey, contentColor = FolderGreyText),
                    )
                }
            }
            item {
                Chip(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.tools_search)) },
                    onClick = { searchPanelOpen = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = {
                        Text(
                            if (flashVisible) "Bookmarked"
                            else stringResource(R.string.tools_bookmark_here),
                        )
                    },
                    onClick = {
                        vm.bookmarkHere()
                        bookmarkedFlashAt = System.currentTimeMillis()
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (bookmarks.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.tools_bookmarks)) } }
                items(
                    bookmarks,
                    key = { "bm:${it.mode.name}:${it.position.chapterIndex}-${it.position.blockIndex}-${it.position.subIndex}" },
                ) { bm ->
                    val chapterTitle = bm.label ?: chapterTitleAt(chapters, bm.position) ?: "Start"
                    val resolvedLabel = when (bm.mode) {
                        ReadingMode.SPEEDREAD -> metrics?.let {
                            "$chapterTitle · ${it.wordIndexAt(bm.position)}/${it.totalWords}"
                        } ?: chapterTitle
                        ReadingMode.SENTENCE -> metrics?.let {
                            "$chapterTitle · ${it.sentenceIndexAt(bm.position)}/${it.totalSentences}"
                        } ?: chapterTitle
                        ReadingMode.NORMAL -> chapterTitle
                    }
                    BookmarkRow(
                        bookmark = bm,
                        displayLabel = resolvedLabel,
                        pendingDelete = pendingDelete,
                        onJump = {
                            vm.jumpTo(bm.position)
                            onReaderPageRequested()
                        },
                        onToggleDelete = {
                            // Long-press toggles the trashcan: same bookmark
                            // clears it, any other bookmark replaces it.
                            pendingDelete = if (pendingDelete == bm.position) null else bm.position
                        },
                        onConfirmDelete = {
                            vm.deleteBookmark(bm.position, bm.mode)
                            pendingDelete = null
                        },
                    )
                }
            }

            item { ListHeader { Text("Time left") } }
            item {
                val e = eta
                if (e == null) {
                    Text(
                        text = "Calculating…",
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        if (e.chapterMs != e.bookMs) {
                            Text(
                                text = "~ ${formatDurationMs(e.chapterMs)} in chapter",
                                style = MaterialTheme.typography.body2,
                            )
                        }
                        Text(
                            text = "~ ${formatDurationMs(e.bookMs)} in book",
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }

            if (chapters.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.tools_chapters)) } }
                items(chapters, key = { "${it.position.chapterIndex}-${it.position.blockIndex}" }) { chapter ->
                    Chip(
                        label = { Text(chapter.title) },
                        onClick = {
                            vm.jumpTo(chapter.position)
                            onReaderPageRequested()
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    query: String,
    results: List<SearchResult>,
    listState: ScalingLazyListState,
    shouldResetToTop: Boolean,
    onResetToTop: () -> Unit,
    onOpen: (SearchResult) -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(query, results.size, shouldResetToTop) {
        if (shouldResetToTop) {
            listState.scrollToItem(0)
            onResetToTop()
        }
        claimRotaryFocusAfterSettle(focusRequester)
    }
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
        state = listState,
        contentPadding = watchListPadding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text("\"$query\" - ${results.size}") } }
        item {
            Chip(
                label = { Text("Clear") },
                onClick = onClear,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    displayLabel: String,
    pendingDelete: BookPosition?,
    onJump: () -> Unit,
    onToggleDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val isPending = pendingDelete == bookmark.position
    val label = displayLabel
    val subtitle = remember(bookmark.savedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(bookmark.savedAtMs))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(BookmarkRowBackground)
                .combinedClickable(
                    onClick = onJump,
                    onLongClick = onToggleDelete,
                )
                .defaultMinSize(minHeight = 52.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.button, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
            )
        }
        if (isPending) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFD32F2F))
                    .combinedClickable(
                        onClick = onConfirmDelete,
                        onLongClick = onToggleDelete,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = "Delete bookmark",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Find the title of the heading-derived chapter that contains [position]. Returns
 * null if [position] is before the first chapter start. Chapters are assumed to
 * be in document order (which [DocumentMetrics.chapterJumps] guarantees).
 */
private fun chapterTitleAt(chapters: List<ChapterJump>, position: BookPosition): String? {
    var best: String? = null
    for (c in chapters) {
        val cp = c.position
        val atOrBefore = cp.chapterIndex < position.chapterIndex ||
            (cp.chapterIndex == position.chapterIndex && cp.blockIndex <= position.blockIndex)
        if (atOrBefore) best = c.title else break
    }
    return best
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
            if (query.isBlank()) {
                Text(
                    text = "Text",
                    color = onSurface,
                    style = MaterialTheme.typography.button,
                )
            }
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
