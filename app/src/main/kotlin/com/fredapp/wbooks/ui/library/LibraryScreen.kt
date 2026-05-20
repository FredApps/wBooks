package com.fredapp.wbooks.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.data.book.Book

@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookOpen: (Book) -> Unit,
    onRefresh: () -> Unit,
    isActive: Boolean = true,
) {
    LaunchedEffect(Unit) { onRefresh() }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(isActive, books.isNotEmpty()) {
        if (isActive && books.isNotEmpty()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Scaffold(timeText = { TimeText() }) {
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_library))
            }
            return@Scaffold
        }

        // Derive folder groupings from book ID path prefixes.
        // A book with id "Fiction/moby-dick.epub" belongs to folder "Fiction".
        // A book with id "moby-dick.epub" is uncategorized.
        val grouped = books.groupBy { it.id.substringBeforeLast('/', "") }
        val folderNames = grouped.keys.filter { it.isNotEmpty() }.sorted()
        val uncategorized = grouped[""] ?: emptyList()
        val hasFolders = folderNames.isNotEmpty()

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (folder in folderNames) {
                val folderBooks = grouped[folder] ?: emptyList()
                item(key = "fh_$folder") {
                    FolderHeaderChip(name = folder)
                }
                items(folderBooks, key = { "b_${it.id}" }) { book ->
                    BookChip(book = book, onClick = { onBookOpen(book) })
                }
            }
            if (uncategorized.isNotEmpty()) {
                if (hasFolders) {
                    item(key = "uncategorized_header") {
                        FolderHeaderChip(name = stringResource(R.string.uncategorized))
                    }
                }
                items(uncategorized, key = { "b_${it.id}" }) { book ->
                    BookChip(book = book, onClick = { onBookOpen(book) })
                }
            }
        }
    }
}

@Composable
private fun FolderHeaderChip(name: String) {
    Chip(
        label = { Text(name, fontWeight = FontWeight.Bold) },
        onClick = {},
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BookChip(book: Book, onClick: () -> Unit) {
    Chip(
        label = { Text("${book.title} [${book.format.name}]") },
        secondaryLabel = book.author?.let { { Text(it) } },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
