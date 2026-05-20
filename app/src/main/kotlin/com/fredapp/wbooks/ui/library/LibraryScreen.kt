package com.fredapp.wbooks.ui.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookOpen: (Book) -> Unit,
    onRefresh: () -> Unit,
    onMoveBook: (bookId: String, targetFolder: String) -> Unit,
    isActive: Boolean = true,
) {
    var bookToMove by remember { mutableStateOf<Book?>(null) }

    val grouped = books.groupBy { it.id.substringBeforeLast('/', "") }
    val folderNames = grouped.keys.filter { it.isNotEmpty() }.sorted()

    bookToMove?.let { book ->
        FolderPickerScreen(
            bookTitle = book.title,
            folders = folderNames,
            currentFolder = book.id.substringBeforeLast('/', ""),
            onPick = { folder -> onMoveBook(book.id, folder); bookToMove = null },
            onCancel = { bookToMove = null },
        )
        return
    }

    LaunchedEffect(Unit) { onRefresh() }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(isActive, books.isNotEmpty()) {
        if (isActive && books.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }

    val uncategorized = grouped[""] ?: emptyList()
    val hasFolders = folderNames.isNotEmpty()
    var expandedFolders by rememberSaveable { mutableStateOf(emptySet<String>()) }

    Scaffold(timeText = { TimeText() }) {
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_library))
            }
            return@Scaffold
        }

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
                val isExpanded = folder in expandedFolders
                item(key = "fh_$folder") {
                    FolderHeaderChip(
                        name = folder,
                        bookCount = folderBooks.size,
                        expanded = isExpanded,
                        onClick = {
                            expandedFolders = if (isExpanded) expandedFolders - folder
                                              else expandedFolders + folder
                        },
                    )
                }
                if (isExpanded) {
                    items(folderBooks, key = { "b_${it.id}" }) { book ->
                        BookChip(book = book, onClick = { onBookOpen(book) }, onLongPress = { bookToMove = book })
                    }
                }
            }
            if (uncategorized.isNotEmpty()) {
                if (hasFolders) {
                    item(key = "uncategorized_header") {
                        FolderHeaderChip(
                            name = stringResource(R.string.uncategorized),
                            bookCount = uncategorized.size,
                            expanded = true,
                            onClick = {},
                        )
                    }
                }
                items(uncategorized, key = { "b_${it.id}" }) { book ->
                    BookChip(book = book, onClick = { onBookOpen(book) }, onLongPress = { bookToMove = book })
                }
            }
        }
    }
}

@Composable
private fun FolderPickerScreen(
    bookTitle: String,
    folders: List<String>,
    currentFolder: String,
    onPick: (targetFolder: String) -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Scaffold(timeText = { TimeText() }) {
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
            item(key = "title") {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item(key = "root") {
                Chip(
                    label = { Text(stringResource(R.string.uncategorized)) },
                    onClick = { onPick("") },
                    colors = if (currentFolder.isEmpty()) ChipDefaults.primaryChipColors()
                             else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(folders, key = { "f_$it" }) { folder ->
                Chip(
                    label = { Text(folder) },
                    onClick = { onPick(folder) },
                    colors = if (folder == currentFolder) ChipDefaults.primaryChipColors()
                             else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "cancel") {
                Chip(
                    label = { Text("Cancel") },
                    onClick = onCancel,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FolderHeaderChip(name: String, bookCount: Int, expanded: Boolean, onClick: () -> Unit) {
    val chevron = if (expanded) "▼" else "▶"
    Chip(
        label = { Text("$chevron $name ($bookCount)", fontWeight = FontWeight.Bold) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BookChip(book: Book, onClick: () -> Unit, onLongPress: () -> Unit) {
    Chip(
        label = { Text(book.title) },
        secondaryLabel = { Text(book.format.name) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(book.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up == null) {
                        onLongPress()
                        var stillPressed = true
                        while (stillPressed) {
                            val evt = awaitPointerEvent()
                            evt.changes.forEach { it.consume() }
                            stillPressed = evt.changes.any { it.pressed }
                        }
                    }
                }
            },
    )
}
