package com.fredapp.wbooks.ui.library

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
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
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.data.book.BookFormat
import com.fredapp.wbooks.data.gutenberg.GutenbergBook
import com.fredapp.wbooks.data.gutenberg.GutenbergPage
import com.fredapp.wbooks.data.gutenberg.GutenbergRepository
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.focus.pageRotaryScrollOwner
import com.fredapp.wbooks.ui.layout.watchListPadding
import com.fredapp.wbooks.util.uniqueFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    var searchText by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(GutenbergHomeSection.POPULAR) }
    var popular by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var recent by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var results by remember { mutableStateOf(emptyList<GutenbergBook>()) }
    var popularHasMore by remember { mutableStateOf(false) }
    var recentHasMore by remember { mutableStateOf(false) }
    var searchHasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var addingId by remember { mutableStateOf<String?>(null) }
    var addingTitle by remember { mutableStateOf<String?>(null) }
    var downloadProgressBytes by remember { mutableStateOf(0L) }
    var downloadProgressTotal by remember { mutableStateOf(-1L) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var addedFilenames by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    fun refreshAddedFilenames() {
        addedFilenames = app.booksDir
            .listFiles()
            ?.filter { it.isFile }
            ?.mapTo(mutableSetOf()) { it.name.normalizedFilename() }
            ?: emptySet()
    }

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
        searchText = query
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

    val searchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) submitSearch(text)
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

    fun isPresent(book: GutenbergBook): Boolean =
        filenameFor(book).normalizedFilename() in addedFilenames

    fun addBook(book: GutenbergBook) {
        if (downloadJob?.isActive == true || isPresent(book)) return
        downloadJob = scope.launch {
            addingId = book.id
            addingTitle = book.title
            downloadProgressBytes = 0L
            downloadProgressTotal = book.sizeBytes ?: -1L
            message = null
            error = null
            var destFile: File? = null
            try {
                withContext(Dispatchers.IO) {
                    repo.withDownload(book) { input, totalBytes ->
                        withContext(Dispatchers.Main) {
                            downloadProgressTotal = totalBytes.takeIf { it > 0L } ?: book.sizeBytes ?: -1L
                        }
                        val safeName = filenameFor(book).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val dest = uniqueFile(app.booksDir, safeName)
                        destFile = dest
                        input.use { source ->
                            dest.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var copied = 0L
                                while (true) {
                                    val read = source.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    copied += read
                                    withContext(Dispatchers.Main) {
                                        downloadProgressBytes = copied
                                    }
                                }
                            }
                        }
                    }
                    app.libraryRepository.refresh()
                }
                destFile?.let { addedFilenames = addedFilenames + it.name.normalizedFilename() }
                message = "Added ${book.title}"
                onLibraryChanged()
            } catch (_: CancellationException) {
                destFile?.delete()
                message = "Add canceled"
            } catch (t: Throwable) {
                destFile?.delete()
                error = t.message ?: "Add failed"
            } finally {
                addingId = null
                addingTitle = null
                downloadProgressBytes = 0L
                downloadProgressTotal = -1L
                downloadJob = null
                refreshAddedFilenames()
            }
        }
    }

    BackHandler {
        onBack()
    }
    ClaimRotaryFocusOnActive(true, focusRequester, query, searchText, popular.size, recent.size, results.size)
    LaunchedEffect(Unit) {
        refreshAddedFilenames()
        loadHome()
    }

    Scaffold(timeText = { TimeText() }) {
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
                GutenbergSearchInput(
                    value = searchText,
                    onValueChange = { searchText = it },
                    onSubmit = { submitSearch(searchText) },
                    onVoice = {
                        runCatching { searchLauncher.launch(buildSearchIntent()) }
                            .onFailure { message = "Voice search unavailable" }
                    },
                )
            }
            if (showingSearch) {
                item(key = "search_header") { ListHeader { Text("\"$query\"") } }
                item(key = "clear") {
                    Chip(
                        label = { Text("Clear search") },
                        onClick = {
                            query = ""
                            searchText = ""
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
            if (addingId != null) {
                item(key = "download_progress") {
                    GutenbergDownloadStatus(
                        title = addingTitle ?: "book",
                        bytes = downloadProgressBytes,
                        total = downloadProgressTotal,
                        onCancel = { downloadJob?.cancel() },
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
                    GutenbergBookChip(
                        book = book,
                        adding = addingId == book.id,
                        added = isPresent(book),
                        progressBytes = downloadProgressBytes,
                        progressTotal = downloadProgressTotal,
                        onAdd = { addBook(book) },
                    )
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
private fun GutenbergSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colors.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = onSurface,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, onSurface.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isBlank()) Text("Search Gutenberg", color = onSurface.copy(alpha = 0.5f))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = onSurface, fontSize = 14.sp, textAlign = TextAlign.Start),
                    cursorBrush = SolidColor(onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        onSubmit()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CompactChip(
                label = { Text("Voice") },
                onClick = onVoice,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.weight(1f),
            )
            CompactChip(
                label = { Text("Go") },
                onClick = {
                    keyboard?.hide()
                    onSubmit()
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GutenbergDownloadStatus(title: String, bytes: Long, total: Long, onCancel: () -> Unit) {
    val progress = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    Chip(
        icon = {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.size(24.dp),
            )
        },
        label = {
            Text(
                "Adding $title",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            Text(if (total > 0L) "${downloadProgressPercent(bytes, total)}%" else "Downloading...")
        },
        onClick = onCancel,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GutenbergBookChip(
    book: GutenbergBook,
    adding: Boolean,
    added: Boolean,
    progressBytes: Long,
    progressTotal: Long,
    onAdd: () -> Unit,
) {
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
                book.metaLines(
                    extra = when {
                        added -> "Added"
                        adding && progressTotal > 0L -> "${downloadProgressPercent(progressBytes, progressTotal)}%"
                        adding -> "Adding..."
                        else -> null
                    },
                ).joinToString("\n"),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onAdd,
        enabled = !adding && !added,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class GutenbergHomeSection(val label: String) {
    POPULAR("Top most popular books"),
    RECENT("Recent releases"),
}

private enum class GutenbergTarget { POPULAR, RECENT, SEARCH }

private fun mergeBooks(current: List<GutenbergBook>, page: GutenbergPage): List<GutenbergBook> =
    (current + page.books).distinctBy { it.id.ifEmpty { it.downloadUrl } }.take(MAX_LIST_ITEMS)

private fun GutenbergBook.metaLines(extra: String? = null): List<String> = buildList {
    author?.takeIf { it.isNotBlank() }?.let(::add)
    releaseDate?.takeIf { it.isNotBlank() }?.let { add("Released $it") }
    add("${extension.uppercase()} - ${sizeBytes?.let(::formatBytes) ?: "Size unknown"}")
    extra?.let(::add)
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

private fun downloadProgressPercent(bytes: Long, total: Long): Int {
    if (total <= 0L) return 0
    return ((bytes * 100L) / total).coerceIn(0L, 100L).toInt()
}

private fun String.normalizedFilename(): String = trim().lowercase()

private fun buildSearchIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Gutenberg")
}

private const val MAX_LIST_ITEMS = 150
