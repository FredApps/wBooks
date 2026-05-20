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
import com.fredapp.wbooks.transfer.FoldersJson

@Composable
fun LibraryScreen(
    books: List<Book>,
    folderState: FoldersJson.State,
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

        val hasFolders = folderState.folders.isNotEmpty()
        val assignments = folderState.assignments

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
            if (hasFolders) {
                for (folder in folderState.folders) {
                    val folderBooks = books.filter { assignments[it.id] == folder.id }
                    if (folderBooks.isEmpty()) continue
                    item(key = "fh_${folder.id}") {
                        FolderHeaderChip(name = folder.name)
                    }
                    items(folderBooks, key = { "b_${it.id}" }) { book ->
                        BookChip(book = book, onClick = { onBookOpen(book) })
                    }
                }
                val uncategorized = books.filter { it.id !in assignments }
                if (uncategorized.isNotEmpty()) {
                    item(key = "uncategorized_header") {
                        FolderHeaderChip(name = stringResource(R.string.uncategorized))
                    }
                    items(uncategorized, key = { "b_${it.id}" }) { book ->
                        BookChip(book = book, onClick = { onBookOpen(book) })
                    }
                }
            } else {
                items(books, key = { it.id }) { book ->
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
