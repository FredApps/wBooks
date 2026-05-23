package com.fredapp.wbooksutil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GutenbergScreen(vm: GutenbergViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }
    var homeSection by rememberSaveable { mutableStateOf(GutenbergHomeSection.POPULAR) }

    LaunchedEffect(state.lastSentTitle) {
        val title = state.lastSentTitle ?: return@LaunchedEffect
        snackbarState.showSnackbar("Added to watch: $title")
        vm.dismissSentToast()
    }
    LaunchedEffect(state.lastStatusMessage) {
        val message = state.lastStatusMessage ?: return@LaunchedEffect
        snackbarState.showSnackbar(message)
        vm.dismissStatusMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Project Gutenberg") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { GutenbergSnackbarHost(state, snackbarState, onCancelDownload = vm::cancelDownload) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchBar(
                value = state.query,
                onValueChange = vm::onQueryChange,
                onSubmit = vm::submitSearch,
            )
            when {
                state.loading && state.visibleBooks.isEmpty() -> CenteredProgress()
                state.showingSearch && state.searchResults.isEmpty() -> CenteredText("No results.")
                state.showingSearch -> ResultsList(
                    results = state.searchResults,
                    downloadingId = state.downloadingId,
                    loadingMore = state.loadingMore,
                    hasMore = state.searchHasMore,
                    onLoadMore = { vm.loadMore(GutenbergListTarget.SEARCH) },
                    isPresentOnDevice = vm::isPresentOnDevice,
                    onAdd = vm::sendToWatch,
                )
                state.popularBooks.isEmpty() && state.recentReleases.isEmpty() -> CenteredText("No books.")
                else -> HomeSections(
                    popularBooks = state.popularBooks,
                    recentReleases = state.recentReleases,
                    selectedSection = homeSection,
                    onSectionChange = { homeSection = it },
                    downloadingId = state.downloadingId,
                    loadingMore = state.loadingMore,
                    popularHasMore = state.popularHasMore,
                    recentHasMore = state.recentHasMore,
                    onLoadMore = { section ->
                        vm.loadMore(
                            when (section) {
                                GutenbergHomeSection.POPULAR -> GutenbergListTarget.POPULAR
                                GutenbergHomeSection.RECENT -> GutenbergListTarget.RECENT
                            },
                        )
                    },
                    isPresentOnDevice = vm::isPresentOnDevice,
                    onAdd = vm::sendToWatch,
                )
            }
        }
    }

    state.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = vm::dismissError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun GutenbergSnackbarHost(
    state: GutenbergViewModel.UiState,
    snackbarState: SnackbarHostState,
    onCancelDownload: () -> Unit,
) {
    if (state.downloadingId != null) {
        Snackbar(modifier = Modifier.padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Adding ${state.downloadingTitle ?: "book"}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel add")
                    }
                }
                val total = state.downloadProgressTotal
                if (total > 0L) {
                    LinearProgressIndicator(
                        progress = { (state.downloadProgressBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${state.downloadProgressPercent()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        SnackbarHost(snackbarState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search title or author") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { onSubmit() },
        ),
        trailingIcon = {
            TextButton(onClick = onSubmit) { Text("Go") }
        },
    )
}

private enum class GutenbergHomeSection(val label: String) {
    POPULAR("Top most popular books"),
    RECENT("Recent releases"),
}

@Composable
private fun HomeSections(
    popularBooks: List<GutenbergBook>,
    recentReleases: List<GutenbergBook>,
    selectedSection: GutenbergHomeSection,
    onSectionChange: (GutenbergHomeSection) -> Unit,
    downloadingId: String?,
    loadingMore: Boolean,
    popularHasMore: Boolean,
    recentHasMore: Boolean,
    onLoadMore: (GutenbergHomeSection) -> Unit,
    isPresentOnDevice: (GutenbergBook) -> Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    val books = when (selectedSection) {
        GutenbergHomeSection.POPULAR -> popularBooks
        GutenbergHomeSection.RECENT -> recentReleases
    }
    val hasMore = when (selectedSection) {
        GutenbergHomeSection.POPULAR -> popularHasMore
        GutenbergHomeSection.RECENT -> recentHasMore
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sectionSwitcher(
            selectedSection = selectedSection,
            onSectionChange = onSectionChange,
        )
        bookItems(
            books = books,
            keyPrefix = selectedSection.name.lowercase(),
            downloadingId = downloadingId,
            isPresentOnDevice = isPresentOnDevice,
            onAdd = onAdd,
        )
        loadMoreItem(
            hasMore = hasMore,
            loadingMore = loadingMore,
            onLoadMore = { onLoadMore(selectedSection) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionSwitcher(
    selectedSection: GutenbergHomeSection,
    onSectionChange: (GutenbergHomeSection) -> Unit,
) {
    item(key = "home_section_switcher") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val nextSection = when (selectedSection) {
                GutenbergHomeSection.POPULAR -> GutenbergHomeSection.RECENT
                GutenbergHomeSection.RECENT -> GutenbergHomeSection.POPULAR
            }
            ListItem(
                headlineContent = {
                    Text(
                        text = selectedSection.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.clickable { onSectionChange(nextSection) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bookItems(
    books: List<GutenbergBook>,
    keyPrefix: String,
    downloadingId: String?,
    isPresentOnDevice: (GutenbergBook) -> Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    items(books, key = { book -> "$keyPrefix:${book.id.ifEmpty { book.downloadUrl }}" }) { book ->
        BookListItem(
            book = book,
            downloadingId = downloadingId,
            isPresentOnDevice = isPresentOnDevice,
            onAdd = onAdd,
        )
    }
}

@Composable
private fun ResultsList(
    results: List<GutenbergBook>,
    downloadingId: String?,
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    isPresentOnDevice: (GutenbergBook) -> Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.id.ifEmpty { it.downloadUrl } }) { book ->
            BookListItem(
                book = book,
                downloadingId = downloadingId,
                isPresentOnDevice = isPresentOnDevice,
                onAdd = onAdd,
            )
        }
        loadMoreItem(
            hasMore = hasMore,
            loadingMore = loadingMore,
            onLoadMore = onLoadMore,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.loadMoreItem(
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    item(key = "load_more") {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hasMore) {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loadingMore,
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (loadingMore) "Loading..." else "Load more")
                }
            } else {
                Text(
                    text = "No more results",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookListItem(
    book: GutenbergBook,
    downloadingId: String?,
    isPresentOnDevice: (GutenbergBook) -> Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    val alreadyPresent = isPresentOnDevice(book)
    ListItem(
        headlineContent = { Text(book.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                book.author?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                book.releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
                    Text(
                        text = "Released $date",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "${book.extension.uppercase()} - ${book.sizeLabel()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                book.summary?.let { s ->
                    Text(
                        text = s,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            val isDownloading = downloadingId == book.id
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(
                    onClick = { onAdd(book) },
                    enabled = downloadingId == null && !alreadyPresent,
                ) { Text(if (alreadyPresent) "Added" else "Add") }
            }
        },
    )
    HorizontalDivider()
}

private fun GutenbergBook.sizeLabel(): String =
    sizeBytes?.let(::formatBytes) ?: "Size unknown"

private fun formatBytes(bytes: Long): String {
    val mib = 1024.0 * 1024.0
    val kib = 1024.0
    return when {
        bytes >= mib -> "%.1f MB".format(bytes / mib)
        bytes >= kib -> "%d KB".format((bytes + 1023L) / 1024L)
        else -> "$bytes B"
    }
}

private fun GutenbergViewModel.UiState.downloadProgressPercent(): Int {
    val total = downloadProgressTotal
    if (total <= 0L) return 0
    return ((downloadProgressBytes * 100) / total).coerceIn(0L, 100L).toInt()
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text) }
}
