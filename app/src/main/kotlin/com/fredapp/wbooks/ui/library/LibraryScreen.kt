package com.fredapp.wbooks.ui.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Color
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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.data.book.Book
import kotlinx.coroutines.withTimeoutOrNull

// Neutral grey folder tabs â€” the old saturated yellow was too bright against
// the watch's black background. Same palette is mirrored in :companion
// (MainActivity.kt) and the LAN web UI (UploadServer.kt) so all three surfaces
// look the same.
private val FolderGrey = Color(0xFFB0B0B0)
private val FolderGreyText = Color(0xFF1C1C1C)
private val DeleteRed = Color(0xFFE53935)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    onBookOpen: (Book) -> Unit,
    onRefresh: () -> Unit,
    onMoveBook: (bookId: String, targetFolder: String) -> Unit,
    onDeleteBook: (bookId: String) -> Unit,
    isActive: Boolean = true,
) {
    var bookToMove by remember { mutableStateOf<Book?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val grouped = books.groupBy { it.id.substringBeforeLast('/', "") }
    val folderNames = grouped.keys.filter { it.isNotEmpty() }.sorted()

    pendingDelete?.let { book ->
        ConfirmDeleteScreen(
            bookTitle = book.title,
            onConfirm = { onDeleteBook(book.id); pendingDelete = null; bookToMove = null },
            onCancel = { pendingDelete = null },
        )
        return
    }

    bookToMove?.let { book ->
        FolderPickerScreen(
            bookTitle = book.title,
            folders = folderNames,
            currentFolder = book.id.substringBeforeLast('/', ""),
            onPick = { folder -> onMoveBook(book.id, folder); bookToMove = null },
            onDelete = { pendingDelete = book },
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
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }

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
            if (hasFolders) {
                item(key = "folder_chips") {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (folder in folderNames) {
                            val isSelected = folder == selectedFolder
                            val bg = if (isSelected) FolderGrey.copy(alpha = 0.55f) else FolderGrey
                            // Chevron flips to indicate the folder is expanded â€”
                            // the alpha-only state cue wasn't readable on the small
                            // round watch face. Replaces the folder glyph in the
                            // CompactChip icon slot (which only fits one icon).
                            CompactChip(
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.KeyboardArrowDown
                                                      else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = FolderGreyText,
                                    )
                                },
                                label = { Text(folder, color = FolderGreyText) },
                                onClick = { selectedFolder = if (isSelected) null else folder },
                                colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = FolderGreyText),
                            )
                        }
                    }
                }
                selectedFolder?.let { folder ->
                    val folderBooks = grouped[folder] ?: emptyList()
                    items(folderBooks, key = { "fb_${it.id}" }) { book ->
                        BookChip(book = book, onClick = { onBookOpen(book) }, onLongPress = { bookToMove = book })
                    }
                }
            }

            item(key = "root_header") {
                Chip(
                    icon = { FolderIcon() },
                    label = {
                        Text(
                            "${stringResource(R.string.uncategorized)} (${uncategorized.size})",
                            color = FolderGreyText,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {},
                    colors = ChipDefaults.chipColors(backgroundColor = FolderGrey, contentColor = FolderGreyText),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(uncategorized, key = { "ub_${it.id}" }) { book ->
                BookChip(book = book, onClick = { onBookOpen(book) }, onLongPress = { bookToMove = book })
            }
        }
    }
}
@Composable
private fun ConfirmDeleteScreen(bookTitle: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
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
                    "Delete this book?",
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item(key = "subtitle") {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item(key = "confirm") {
                Chip(
                    label = { Text("Delete", color = Color.White) },
                    onClick = onConfirm,
                    colors = ChipDefaults.chipColors(backgroundColor = DeleteRed, contentColor = Color.White),
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
private fun FolderPickerScreen(
    bookTitle: String,
    folders: List<String>,
    currentFolder: String,
    onPick: (targetFolder: String) -> Unit,
    onDelete: () -> Unit,
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
                val bg = if (currentFolder.isEmpty()) FolderGrey.copy(alpha = 0.55f) else FolderGrey
                Chip(
                    icon = { FolderIcon() },
                    label = { Text(stringResource(R.string.uncategorized), color = FolderGreyText) },
                    onClick = { onPick("") },
                    colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = FolderGreyText),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(folders, key = { "f_$it" }) { folder ->
                val bg = if (folder == currentFolder) FolderGrey.copy(alpha = 0.55f) else FolderGrey
                Chip(
                    icon = { FolderIcon() },
                    label = { Text(folder, color = FolderGreyText) },
                    onClick = { onPick(folder) },
                    colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = FolderGreyText),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "delete") {
                Chip(
                    label = { Text("Delete book", color = Color.White) },
                    onClick = onDelete,
                    colors = ChipDefaults.chipColors(backgroundColor = DeleteRed, contentColor = Color.White),
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
private fun FolderIcon() {
    Icon(
        imageVector = Icons.Default.Folder,
        contentDescription = null,
        tint = FolderGreyText,
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
