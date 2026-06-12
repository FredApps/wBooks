package com.fredapp.wbooksutil

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fredapp.wbooks.data.bookmarks.Bookmark
import com.fredapp.wbooks.data.position.BookPosition
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.parser.model.Block
import com.fredapp.wbooks.parser.model.Document
import com.fredapp.wbooks.parser.model.flatIndexOf
import com.fredapp.wbooks.parser.model.positionAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneReaderScreen(vm: PhoneReaderViewModel, onBack: () -> Unit) {
    val state by vm.document.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.currentPosition.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler {
        vm.closeBook()
        onBack()
    }

    val loadedBookId = (state as? PhoneDocumentState.Loaded)?.book?.id
    DisposableEffect(loadedBookId) {
        if (loadedBookId != null) vm.startReadingSession()
        onDispose {
            if (loadedBookId != null) vm.endReadingSession()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? PhoneDocumentState.Loaded)?.book?.title ?: "Reader", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.closeBook()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { vm.bookmarkHere() }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Bookmark")
                    }
                    IconButton(onClick = { showTools = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Tools")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                PhoneDocumentState.Idle -> CenteredText("Open a book from the library.")
                is PhoneDocumentState.Loading -> LoadingReader(s)
                is PhoneDocumentState.Failed -> CenteredText("Failed to open ${s.title}: ${s.message}")
                is PhoneDocumentState.Loaded -> when (settings.mode) {
                    ReadingMode.NORMAL -> NormalPhoneReader(s.document, position.takeUnlessStart(s.initialPosition), settings, vm)
                    ReadingMode.SENTENCE -> SentencePhoneReader(s.document, position.takeUnlessStart(s.initialPosition), settings, vm)
                    ReadingMode.SPEEDREAD -> SpeedPhoneReader(s.document, position.takeUnlessStart(s.initialPosition), settings, vm)
                }
            }
        }
    }

    if (showSearch) SearchDialog(vm = vm, onDismiss = { showSearch = false })
    if (showTools) ReaderToolsSheet(vm = vm, onDismiss = { showTools = false })
    if (showSettings) ReaderSettingsSheet(settings = settings, vm = vm, onDismiss = { showSettings = false })
}

private fun BookPosition.takeUnlessStart(fallback: BookPosition): BookPosition =
    if (this == BookPosition.START) fallback else this

@Composable
private fun LoadingReader(state: PhoneDocumentState.Loading) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Opening ${state.title}", textAlign = TextAlign.Center)
        Text("${state.progressPercent}% - ${state.status}", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun NormalPhoneReader(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: PhoneReaderViewModel,
) {
    val blocks = remember(document) { document.chapters.flatMap { it.blocks } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoscrollPaused by remember { mutableStateOf(false) }
    if (blocks.isEmpty()) {
        CenteredText("(no readable text)")
        return
    }

    LaunchedEffect(document) {
        val flat = document.flatIndexOf(initialPosition)
        if (flat in blocks.indices) listState.scrollToItem(flat)
    }
    LaunchedEffect(document) {
        vm.jumps.collect { target ->
            val flat = document.flatIndexOf(target)
            if (flat in blocks.indices) listState.scrollToItem(flat)
        }
    }
    LaunchedEffect(document) {
        val visible = snapshotFlow { listState.firstVisibleItemIndex }.drop(1).distinctUntilChanged()
        launch { visible.debounce(500).collect { vm.reportPosition(document.positionAt(it)) } }
    }
    LaunchedEffect(settings.autoscrollEnabled, settings.autoscrollSpeed, autoscrollPaused) {
        if (!settings.autoscrollEnabled || autoscrollPaused) return@LaunchedEffect
        val pxPerStep = (settings.autoscrollSpeed * 4f).coerceAtLeast(8f)
        while (true) {
            listState.scroll { scrollBy(pxPerStep) }
            delay(50)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(settings.autoscrollEnabled) {
                detectTapGestures(onTap = {
                    if (settings.autoscrollEnabled) autoscrollPaused = !autoscrollPaused
                    else scope.launch {
                        val height = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                        if (height > 0) listState.animateScrollBy(height * 0.55f)
                    }
                })
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
    ) {
        items(blocks.size) { index -> PhoneBlock(blocks[index], settings) }
    }
}

@Composable
private fun SentencePhoneReader(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: PhoneReaderViewModel,
) {
    val sentences = remember(document) { sentenceItems(document) }
    if (sentences.isEmpty()) {
        CenteredText("(no readable text)")
        return
    }
    var index by remember(document) { mutableIntStateOf(sentences.sentenceIndexFor(initialPosition)) }
    LaunchedEffect(document) {
        vm.jumps.collect { target -> index = sentences.sentenceIndexFor(target) }
    }
    LaunchedEffect(index) { vm.reportPosition(sentences[index].position) }
    LaunchedEffect(settings.autoscrollEnabled, settings.autoscrollSpeed) {
        if (!settings.autoscrollEnabled) return@LaunchedEffect
        val delayMs = (2200L - settings.autoscrollSpeed * 28L).coerceAtLeast(450L)
        while (index < sentences.lastIndex) {
            delay(delayMs)
            index++
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = sentences[index].text,
            color = Color(settings.textColorArgb),
            fontFamily = settings.font.toFontFamily(),
            fontSize = settings.sentenceTextSizeSp.sp,
            textAlign = TextAlign.Center,
            lineHeight = (settings.sentenceTextSizeSp + 8).sp,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistChip(onClick = { index = (index - 1).coerceAtLeast(0) }, label = { Text("Previous") })
            AssistChip(onClick = { index = (index + 1).coerceAtMost(sentences.lastIndex) }, label = { Text("Next") })
        }
        Text("${index + 1}/${sentences.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SpeedPhoneReader(
    document: Document,
    initialPosition: BookPosition,
    settings: ReaderSettings,
    vm: PhoneReaderViewModel,
) {
    val words = remember(document) { wordItems(document) }
    if (words.isEmpty()) {
        CenteredText("(no readable text)")
        return
    }
    var index by remember(document) { mutableIntStateOf(words.wordIndexFor(initialPosition)) }
    var playing by remember(document) { mutableStateOf(true) }
    LaunchedEffect(document) {
        vm.jumps.collect { target -> index = words.wordIndexFor(target) }
    }
    LaunchedEffect(index) { vm.reportPosition(words[index].position) }
    LaunchedEffect(playing, settings.speedreadWpm) {
        if (!playing) return@LaunchedEffect
        val delayMs = (60_000L / settings.speedreadWpm.coerceAtLeast(60)).coerceAtLeast(20L)
        while (index < words.lastIndex) {
            delay(delayMs)
            index++
        }
        playing = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .clickable { playing = !playing },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${settings.speedreadWpm} wpm", color = Color(settings.textColorArgb).copy(alpha = 0.65f))
        Spacer(Modifier.height(24.dp))
        FocalWord(words[index].text, settings)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistChip(onClick = { vm.setSpeedreadWpm(settings.speedreadWpm - 25) }, label = { Text("-25") })
            AssistChip(onClick = { playing = !playing }, label = { Text(if (playing) "Pause" else "Play") })
            AssistChip(onClick = { vm.setSpeedreadWpm(settings.speedreadWpm + 25) }, label = { Text("+25") })
        }
        Text("${index + 1}/${words.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhoneBlock(block: Block, settings: ReaderSettings) {
    val color = Color(settings.textColorArgb)
    when (block) {
        is Block.Heading -> Text(
            block.text,
            color = color,
            fontFamily = settings.font.toFontFamily(),
            fontSize = (settings.textSizeSp + 5).sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (settings.textSizeSp + 11).sp,
        )
        is Block.Paragraph -> Text(
            buildAnnotatedString {
                block.runs.forEach { run ->
                    withStyle(
                        SpanStyle(
                            fontWeight = if (run.style.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (run.style.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                    ) { append(run.text) }
                }
            },
            color = color,
            fontFamily = settings.font.toFontFamily(),
            fontSize = settings.textSizeSp.sp,
            lineHeight = (settings.textSizeSp + 8).sp,
        )
        is Block.Code -> Text(
            block.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = (settings.textSizeSp - 1).coerceAtLeast(10).sp,
        )
        Block.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun FocalWord(word: String, settings: ReaderSettings) {
    val focal = focalIndex(word)
    val fontSize = (settings.textSizeSp + 18).sp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(word.take(focal), color = Color(settings.textColorArgb), fontSize = fontSize, maxLines = 1)
        }
        Text(word.getOrNull(focal)?.toString().orEmpty(), color = Color(0xFFF06B5A), fontSize = fontSize, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(word.drop(focal + 1), color = Color(settings.textColorArgb), fontSize = fontSize, maxLines = 1)
        }
    }
}

@Composable
private fun SearchDialog(vm: PhoneReaderViewModel, onDismiss: () -> Unit) {
    val results by vm.searchResults.collectAsState()
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true)
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(results) { result ->
                        ListItem(
                            headlineContent = { Text(result.snippet, maxLines = 2) },
                            modifier = Modifier.clickable {
                                vm.jumpTo(result.position)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.runSearch(query) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderToolsSheet(vm: PhoneReaderViewModel, onDismiss: () -> Unit) {
    val state by vm.document.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        val loaded = state as? PhoneDocumentState.Loaded
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item { Text("Bookmarks", style = MaterialTheme.typography.titleMedium) }
            if (bookmarks.isEmpty()) {
                item { Text("No bookmarks yet.", modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(bookmarks) { bookmark ->
                BookmarkRow(bookmark = bookmark, onOpen = {
                    vm.jumpTo(bookmark.position)
                    onDismiss()
                }, onDelete = { vm.deleteBookmark(bookmark) })
            }
            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item { Text("Chapters", style = MaterialTheme.typography.titleMedium) }
            loaded?.metrics?.chapterJumps.orEmpty().forEach { chapter ->
                item {
                    ListItem(
                        headlineContent = { Text(chapter.title, maxLines = 2) },
                        modifier = Modifier.clickable {
                            vm.jumpTo(chapter.position)
                            onDismiss()
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, onOpen: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(bookmark.label ?: "Bookmark") },
        supportingContent = { Text(bookmark.mode.name.lowercase()) },
        trailingContent = {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, contentDescription = "Delete") }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(settings: ReaderSettings, vm: PhoneReaderViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reading mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.mode == mode,
                        onClick = { vm.setMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.titlecase() }) },
                    )
                }
            }
            Text("Text size: ${settings.textSizeSp}")
            Slider(
                value = settings.textSizeSp.toFloat(),
                onValueChange = { vm.setTextSize(it.toInt()) },
                valueRange = ReaderSettings.TEXT_SIZE_RANGE.first.toFloat()..ReaderSettings.TEXT_SIZE_RANGE.last.toFloat(),
            )
            Text("Sentence size: ${settings.sentenceTextSizeSp}")
            Slider(
                value = settings.sentenceTextSizeSp.toFloat(),
                onValueChange = { vm.setSentenceTextSize(it.toInt()) },
                valueRange = ReaderSettings.SENTENCE_TEXT_SIZE_RANGE.first.toFloat()..ReaderSettings.SENTENCE_TEXT_SIZE_RANGE.last.toFloat(),
            )
            Text("Speed-read WPM: ${settings.speedreadWpm}")
            Slider(
                value = settings.speedreadWpm.toFloat(),
                onValueChange = { vm.setSpeedreadWpm(it.toInt()) },
                valueRange = ReaderSettings.WPM_RANGE.first.toFloat()..ReaderSettings.WPM_RANGE.last.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoice.entries.take(5).forEach { font ->
                    FilterChip(selected = settings.font == font, onClick = { vm.setFont(font) }, label = { Text(font.familyName) })
                }
            }
            FilterChip(
                selected = settings.autoscrollEnabled,
                onClick = { vm.setAutoscrollEnabled(!settings.autoscrollEnabled) },
                label = { Text("Autoscroll") },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

private data class SentenceItem(val text: String, val position: BookPosition)
private data class WordItem(val text: String, val position: BookPosition)

private fun sentenceItems(document: Document): List<SentenceItem> {
    val out = mutableListOf<SentenceItem>()
    for ((ci, chapter) in document.chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            blockText(block).split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { si, sentence -> out += SentenceItem(sentence, BookPosition(ci, bi, si)) }
        }
    }
    return out
}

private fun wordItems(document: Document): List<WordItem> {
    val out = mutableListOf<WordItem>()
    for ((ci, chapter) in document.chapters.withIndex()) {
        for ((bi, block) in chapter.blocks.withIndex()) {
            blockText(block).split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { wi, word -> out += WordItem(word, BookPosition(ci, bi, wi)) }
        }
    }
    return out
}

private fun blockText(block: Block): String = when (block) {
    is Block.Heading -> block.text
    is Block.Paragraph -> block.runs.joinToString("") { it.text }
    is Block.Code -> block.text
    Block.Divider -> ""
}

private fun List<SentenceItem>.sentenceIndexFor(position: BookPosition): Int =
    indexOfFirst { it.position >= position }.takeIf { it >= 0 } ?: lastIndex

private fun List<WordItem>.wordIndexFor(position: BookPosition): Int =
    indexOfFirst { it.position >= position }.takeIf { it >= 0 } ?: lastIndex

private operator fun BookPosition.compareTo(other: BookPosition): Int =
    compareValuesBy(this, other, BookPosition::chapterIndex, BookPosition::blockIndex, BookPosition::subIndex)

private fun focalIndex(word: String): Int = when (word.length) {
    0, 1 -> 0
    in 2..5 -> 1
    in 6..9 -> 2
    in 10..13 -> 3
    else -> 4
}.coerceAtMost(word.lastIndex.coerceAtLeast(0))

private fun FontChoice.toFontFamily(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
    FontChoice.INTER_LIGHT,
    FontChoice.INTER_MEDIUM -> FontFamily.SansSerif
}
