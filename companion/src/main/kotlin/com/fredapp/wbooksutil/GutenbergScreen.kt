package com.fredapp.wbooksutil

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GutenbergScreen(vm: GutenbergViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }

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
                state.loading && state.results.isEmpty() -> CenteredProgress()
                state.results.isEmpty() -> CenteredText("No results.")
                else -> ResultsList(
                    results = state.results,
                    downloadingId = state.downloadingId,
                    onSend = vm::sendToWatch,
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
        trailingIcon = {
            TextButton(onClick = onSubmit) { Text("Go") }
        },
    )
}

@Composable
private fun ResultsList(
    results: List<GutenbergBook>,
    downloadingId: String?,
    onSend: (GutenbergBook) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.id.ifEmpty { it.downloadUrl } }) { book ->
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
            )
            HorizontalDivider()
        }
    }
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
