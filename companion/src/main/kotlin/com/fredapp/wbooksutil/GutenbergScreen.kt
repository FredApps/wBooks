package com.fredapp.wbooksutil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GutenbergScreen(vm: GutenbergViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }
    var selectedBook by remember { mutableStateOf<GutenbergBook?>(null) }

    LaunchedEffect(state.lastSentTitle) {
        val title = state.lastSentTitle ?: return@LaunchedEffect
        snackbarState.showSnackbar("Sent to watch: $title")
        vm.dismissSentToast()
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
        snackbarHost = { SnackbarHost(snackbarState) },
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
                    onOpen = { selectedBook = it },
                    onSend = vm::sendToWatch,
                )
                state.popularBooks.isEmpty() && state.recentReleases.isEmpty() -> CenteredText("No books.")
                else -> HomeSections(
                    popularBooks = state.popularBooks,
                    recentReleases = state.recentReleases,
                    downloadingId = state.downloadingId,
                    onOpen = { selectedBook = it },
                    onSend = vm::sendToWatch,
                )
            }
        }
    }

    selectedBook?.let { book ->
        BookInfoDialog(
            book = book,
            downloadingId = state.downloadingId,
            onSend = vm::sendToWatch,
            onDismiss = { selectedBook = null },
        )
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

@Composable
private fun HomeSections(
    popularBooks: List<GutenbergBook>,
    recentReleases: List<GutenbergBook>,
    downloadingId: String?,
    onOpen: (GutenbergBook) -> Unit,
    onSend: (GutenbergBook) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (popularBooks.isNotEmpty()) {
            sectionHeader("Top most popular books")
            bookItems(
                books = popularBooks,
                keyPrefix = "popular",
                downloadingId = downloadingId,
                onOpen = onOpen,
                onSend = onSend,
            )
        }
        if (recentReleases.isNotEmpty()) {
            sectionHeader("Recent releases")
            bookItems(
                books = recentReleases,
                keyPrefix = "recent",
                downloadingId = downloadingId,
                onOpen = onOpen,
                onSend = onSend,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(title: String) {
    item(key = "header:$title") {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bookItems(
    books: List<GutenbergBook>,
    keyPrefix: String,
    downloadingId: String?,
    onOpen: (GutenbergBook) -> Unit,
    onSend: (GutenbergBook) -> Unit,
) {
    items(books, key = { book -> "$keyPrefix:${book.id.ifEmpty { book.downloadUrl }}" }) { book ->
        BookListItem(
            book = book,
            downloadingId = downloadingId,
            onOpen = onOpen,
            onSend = onSend,
        )
    }
}

@Composable
private fun ResultsList(
    results: List<GutenbergBook>,
    downloadingId: String?,
    onOpen: (GutenbergBook) -> Unit,
    onSend: (GutenbergBook) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.id.ifEmpty { it.downloadUrl } }) { book ->
            BookListItem(
                book = book,
                downloadingId = downloadingId,
                onOpen = onOpen,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun BookListItem(
    book: GutenbergBook,
    downloadingId: String?,
    onOpen: (GutenbergBook) -> Unit,
    onSend: (GutenbergBook) -> Unit,
) {
    ListItem(
        headlineContent = { Text(book.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                val sub = book.author ?: ""
                Text(
                    text = if (sub.isEmpty()) book.extension.uppercase()
                    else "$sub - ${book.extension.uppercase()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    onClick = { onSend(book) },
                    enabled = downloadingId == null,
                ) { Text("Send") }
            }
        },
        modifier = Modifier.clickable { onOpen(book) },
    )
    HorizontalDivider()
}

@Composable
private fun BookInfoDialog(
    book: GutenbergBook,
    downloadingId: String?,
    onSend: (GutenbergBook) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.author ?: "Unknown author", fontWeight = FontWeight.SemiBold)
                Text("Format: ${book.extension.uppercase()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = book.summary ?: "No description available from Project Gutenberg.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(book) },
                enabled = downloadingId == null,
            ) { Text(if (downloadingId == book.id) "Sending..." else "Send") }
        },
        dismissButton = {
            Row {
                book.infoUrl?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Text("Open Gutenberg")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
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
