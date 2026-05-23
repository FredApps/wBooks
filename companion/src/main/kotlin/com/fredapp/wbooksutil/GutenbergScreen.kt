package com.fredapp.wbooksutil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
                    onAdd = vm::sendToWatch,
                )
                state.popularBooks.isEmpty() && state.recentReleases.isEmpty() -> CenteredText("No books.")
                else -> HomeSections(
                    popularBooks = state.popularBooks,
                    recentReleases = state.recentReleases,
                    selectedSection = homeSection,
                    onSectionChange = { homeSection = it },
                    downloadingId = state.downloadingId,
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
    onAdd: (GutenbergBook) -> Unit,
) {
    val books = when (selectedSection) {
        GutenbergHomeSection.POPULAR -> popularBooks
        GutenbergHomeSection.RECENT -> recentReleases
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
            showReleaseDateOnOwnLine = selectedSection == GutenbergHomeSection.RECENT,
            onAdd = onAdd,
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
    showReleaseDateOnOwnLine: Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    items(books, key = { book -> "$keyPrefix:${book.id.ifEmpty { book.downloadUrl }}" }) { book ->
        BookListItem(
            book = book,
            downloadingId = downloadingId,
            showReleaseDateOnOwnLine = showReleaseDateOnOwnLine,
            onAdd = onAdd,
        )
    }
}

@Composable
private fun ResultsList(
    results: List<GutenbergBook>,
    downloadingId: String?,
    onAdd: (GutenbergBook) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.id.ifEmpty { it.downloadUrl } }) { book ->
            BookListItem(
                book = book,
                downloadingId = downloadingId,
                showReleaseDateOnOwnLine = false,
                onAdd = onAdd,
            )
        }
    }
}

@Composable
private fun BookListItem(
    book: GutenbergBook,
    downloadingId: String?,
    showReleaseDateOnOwnLine: Boolean,
    onAdd: (GutenbergBook) -> Unit,
) {
    ListItem(
        headlineContent = { Text(book.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                if (showReleaseDateOnOwnLine) {
                    Text(
                        text = book.authorLine(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
                        Text(
                            text = "Released $date",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        text = book.resultInfo(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                    enabled = downloadingId == null,
                ) { Text("Add") }
            }
        },
    )
    HorizontalDivider()
}

private fun GutenbergBook.resultInfo(): String {
    val parts = buildList {
        author?.takeIf { it.isNotBlank() }?.let(::add)
        releaseDate?.takeIf { it.isNotBlank() }?.let { add("Released $it") }
        add(extension.uppercase())
    }
    return parts.joinToString(" - ")
}

private fun GutenbergBook.authorLine(): String =
    author?.takeIf { it.isNotBlank() } ?: extension.uppercase()

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
