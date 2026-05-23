package com.fredapp.wbooks.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.data.gutenberg.GutenbergBook
import com.fredapp.wbooks.data.gutenberg.GutenbergPage
import com.fredapp.wbooks.data.gutenberg.GutenbergRepository
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.focus.pageRotaryScrollOwner
import com.fredapp.wbooks.ui.layout.watchListPadding
import com.fredapp.wbooks.util.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WatchGutenbergScreen(onBack: () -> Unit, onLibraryChanged: () -> Unit) {
    val app = LocalContext.current.applicationContext as WBooksApp
    val scope = rememberCoroutineScope()
    val repo = remember { GutenbergRepository() }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(GutenbergHomeSection.POPULAR) }
    var popular by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var recent by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var results by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var popularHasMore by remember { mutableStateOf(false) }
    var recentHasMore by remember { mutableStateOf(false) }
    var searchHasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var addingId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    fun loadHome() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                val popularPage = repo.popular()
                val recentPage = repo.recentReleases()
                popular = popularPage.books
                popularHasMore = popularPage.hasMore
                recent = recentPage.books
                recentHasMore = recentPage.hasMore
            }.onFailure { error = it.message ?: "Could not reach Project Gutenberg" }
            loading = false
        }
    }

    fun submitSearch(text: String) {
        query = text.trim()
        searchOpen = false
        if (query.isBlank()) {
            results = emptyList()
            searchHasMore = false
            return
        }
        scope.launch {
            loading = true
            error = null
            runCatching { repo.search(query) }
                .onSuccess {
                    results = it.books
                    searchHasMore = it.hasMore
                }
                .onFailure { error = it.message ?: "Search failed" }
            loading = false
        }
    }

    fun loadMore(target: GutenbergTarget) {
        scope.launch {
            loading = true
            error = null
            val start = when (target) {
                GutenbergTarget.POPULAR -> popular.size + 1
                GutenbergTarget.RECENT -> recent.size + 1
                GutenbergTarget.SEARCH -> results.size + 1
            }
            runCatching {
                when (target) {
                    GutenbergTarget.POPULAR -> repo.popular(start)
                    GutenbergTarget.RECENT -> repo.recentReleases(start)
                    GutenbergTarget.SEARCH -> repo.search(query, start)
                }
            }.onSuccess { page ->
                when (target) {
                    GutenbergTarget.POPULAR -> {
                        popular = mergeBooks(popular, page)
                        popularHasMore = page.hasMore
                    }
                    GutenbergTarget.RECENT -> {
                        recent = mergeBooks(recent, page)
                        recentHasMore = page.hasMore
                    }
                    GutenbergTarget.SEARCH -> {
                        results = mergeBooks(results, page)
                        searchHasMore = page.hasMore
                    }
                }
            }.onFailure { error = it.message ?: "Load more failed" }
            loading = false
        }
    }

    fun addBook(book: GutenbergBook) {
        scope.launch {
            addingId = book.id
            message = null
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.withDownload(book) { input, _ ->
                        val safeName = filenameFor(book).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val dest = uniqueFile(app.booksDir, safeName)
                        input.use { source -> dest.outputStream().use { source.copyTo(it) } }
                    }
                    app.libraryRepository.refresh()
                }
            }.onSuccess {
                message = "Added ${book.title}"
                onLibraryChanged()
            }.onFailure { error = it.message ?: "Add failed" }
            addingId = null
        }
    }

    BackHandler {
        if (searchOpen) searchOpen = false else onBack()
    }
    ClaimRotaryFocusOnActive(!searchOpen, focusRequester, query, popular.size, recent.size, results.size)
    LaunchedEffect(Unit) { loadHome() }

    Scaffold(timeText = { TimeText() }) {
        if (searchOpen) {
            GutenbergSearchPanel(initial = query, onSubmit = ::submitSearch, onCancel = { searchOpen = false })
            return@Scaffold
        }
        val showingSearch = query.isNotBlank()
        val books = if (showingSearch) results else if (section == GutenbergHomeSection.POPULAR) popular else recent
        val hasMore = if (showingSearch) searchHasMore else if (section == GutenbergHomeSection.POPULAR) popularHasMore else recentHasMore
        val target = if (showingSearch) GutenbergTarget.SEARCH else if (section == GutenbergHomeSection.POPULAR) GutenbergTarget.POPULAR else GutenbergTarget.RECENT

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "back") { BackChipRow(onClick = onBack) }
            item(key = "search") {
                Chip(
                    label = { Text("Search Gutenberg") },
                    onClick = { searchOpen = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showingSearch) {
                item(key = "search_header") { ListHeader { Text("\"$query\"") } }
                item(key = "clear") {
                    Chip(
                        label = { Text("Clear search") },
                        onClick = {
                            query = ""
                            results = emptyList()
                            searchHasMore = false
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item(key = "switcher") {
                    val next = if (section == GutenbergHomeSection.POPULAR) GutenbergHomeSection.RECENT else GutenbergHomeSection.POPULAR
                    Chip(
                        label = { Text(section.label) },
                        onClick = { section = next },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message?.let { msg ->
                item(key = "message") { Text(msg, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            error?.let { msg ->
                item(key = "error") { Text("Stopped: $msg", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (loading && books.isEmpty()) {
                item(key = "loading") { Text("Loading...", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            } else if (books.isEmpty()) {
                item(key = "empty") { Text("No books", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            } else {
                items(books, key = { it.id.ifEmpty { it.downloadUrl } }) { book ->
                    GutenbergBookChip(book = book, adding = addingId == book.id, onAdd = { addBook(book) })
                }
            }
            item(key = "load_more") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompactChip(
                        label = { Text(if (loading && books.isNotEmpty()) "Loading..." else if (hasMore) "Load more" else "No more") },
                        onClick = { if (hasMore && !loading) loadMore(target) },
                        enabled = hasMore && !loading,
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GutenbergBookChip(book: GutenbergBook, adding: Boolean, onAdd: () -> Unit) {
    Chip(
        label = {
            Text(
                book.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
        },
        secondaryLabel = {
            Text(
                book.metaLines().joinToString("\n"),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onAdd,
        enabled = !adding,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GutenbergSearchPanel(initial: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colors.onSurface
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search Gutenberg")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, onSurface.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isBlank()) Text("Title or author", color = onSurface.copy(alpha = 0.5f))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = onSurface, fontSize = 14.sp, textAlign = TextAlign.Start),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSubmit(text)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Chip(
                label = { Text("Go") },
                onClick = { onSubmit(text) },
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

private enum class GutenbergHomeSection(val label: String) {
    POPULAR("Top most popular books"),
    RECENT("Recent releases"),
}

private enum class GutenbergTarget { POPULAR, RECENT, SEARCH }

private fun mergeBooks(current: List<GutenbergBook>, page: GutenbergPage): List<GutenbergBook> =
    (current + page.books).distinctBy { it.id.ifEmpty { it.downloadUrl } }.take(MAX_LIST_ITEMS)

private fun GutenbergBook.metaLines(): List<String> = buildList {
    author?.takeIf { it.isNotBlank() }?.let(::add)
    releaseDate?.takeIf { it.isNotBlank() }?.let { add("Released $it") }
    add("${extension.uppercase()} - ${sizeBytes?.let(::formatBytes) ?: "Size unknown"}")
}

private fun filenameFor(book: GutenbergBook): String {
    val clean = book.title.trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(80)
        .ifBlank { "gutenberg-book" }
    val ext = if (BookFormat.fromExtension(book.extension) != null) book.extension else "epub"
    return "$clean.$ext"
}

private fun formatBytes(bytes: Long): String {
    val mib = 1024.0 * 1024.0
    val kib = 1024.0
    return when {
        bytes >= mib -> "%.1f MB".format(bytes / mib)
        bytes >= kib -> "%d KB".format((bytes + 1023L) / 1024L)
        else -> "$bytes B"
    }
}

private const val MAX_LIST_ITEMS = 150
