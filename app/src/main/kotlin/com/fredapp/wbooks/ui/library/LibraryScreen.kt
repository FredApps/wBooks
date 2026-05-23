package com.fredapp.wbooks.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.data.book.Book
import com.fredapp.wbooks.data.folder.FolderPolicy
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.focus.pageRotaryScrollOwner
import com.fredapp.wbooks.ui.layout.watchListPadding
import com.fredapp.wbooks.ui.settings.InstructionsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Neutral grey folder tabs — the old saturated yellow was too bright against
// the watch's black background. Same palette is mirrored in :companion
// (MainActivity.kt) and the LAN web UI (UploadServer.kt) so all three surfaces
// look the same.
private val FolderGrey = Color(0xFFB0B0B0)
private val FolderGreyText = Color(0xFF1C1C1C)
private val BookChipBackground = Color(0xFF2A2A2A)
private val DeleteRed = Color(0xFFE53935)

private data class LibraryStorageInfo(val usedBytes: Long, val freeBytes: Long)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    folderNames: List<String>,
    onBookOpen: (Book) -> Unit,
    onRefresh: () -> Unit,
    onMoveBook: (bookId: String, targetFolder: String) -> Unit,
    onRenameBook: (bookId: String, newTitle: String) -> Unit,
    onDeleteBook: (bookId: String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (oldName: String, newName: String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    isActive: Boolean = true,
) {
    var bookToMove by remember { mutableStateOf<Book?>(null) }
    var bookToRename by remember { mutableStateOf<Book?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var folderAction by remember { mutableStateOf<String?>(null) }
    var folderToRename by remember { mutableStateOf<String?>(null) }
    var pendingFolderDelete by remember { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }

    // One-shot "How to use" hint above the New folder button. Visible only on
    // the first launch after install — FirstRunPref marks the launch consumed
    // the moment it's first read, so subsequent process starts return false.
    // Local state lets the chip disappear immediately on tap within this run.
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as WBooksApp }
    var showFirstRunHint by rememberSaveable { mutableStateOf(app.firstRunPref.isFirstRun) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val storageInfo by produceState<LibraryStorageInfo?>(initialValue = null, books, folderNames) {
        value = withContext(Dispatchers.IO) { app.booksDir.storageInfo() }
    }

    if (showInstructions) {
        BackHandler { showInstructions = false }
        InstructionsScreen(onBack = { showInstructions = false })
        return
    }

    // Derived collections are stable across recompositions caused by unrelated
    // state (selectedFolder taps, dialog flips) — only the books/folderNames
    // inputs actually change them. Re-running groupBy + sort on every tap was
    // the dominant source of frame jank when paging chip-heavy folders.
    val grouped = remember(books) { books.groupBy { it.id.substringBeforeLast('/', "") } }
    val allFolderNames = remember(folderNames, grouped) {
        (folderNames + grouped.keys.filter { it.isNotEmpty() }).distinct().sorted()
    }
    val uncategorized = grouped[""] ?: emptyList()

    pendingFolderDelete?.let { folder ->
        BackHandler { pendingFolderDelete = null }
        ConfirmDeleteScreen(
            bookTitle = "$folder/",
            heading = "Delete folder?",
            onConfirm = { onDeleteFolder(folder); pendingFolderDelete = null; folderAction = null },
            onCancel = { pendingFolderDelete = null },
        )
        return
    }
    folderToRename?.let { folder ->
        BackHandler { folderToRename = null }
        RenameFolderScreen(
            currentName = folder,
            maxLength = FolderPolicy.MAX_NAME_LENGTH,
            validator = { candidate ->
                FolderPolicy.validateRename(folder, candidate, allFolderNames).error
            },
            onSubmit = { onRenameFolder(folder, it); folderToRename = null; folderAction = null },
            onCancel = { folderToRename = null },
        )
        return
    }
    folderAction?.let { folder ->
        BackHandler { folderAction = null }
        FolderActionsScreen(
            folderName = folder,
            rootBooks = uncategorized,
            onRename = { folderToRename = folder },
            onMoveRootBook = { book -> onMoveBook(book.id, folder) },
            onDelete = { pendingFolderDelete = folder },
            onCancel = { folderAction = null },
        )
        return
    }
    if (showNewFolder) {
        BackHandler { showNewFolder = false }
        RenameFolderScreen(
            currentName = "",
            heading = "New folder",
            maxLength = FolderPolicy.MAX_NAME_LENGTH,
            validator = { candidate ->
                FolderPolicy.validateCreate(candidate, allFolderNames).error
            },
            onSubmit = { onCreateFolder(it); showNewFolder = false },
            onCancel = { showNewFolder = false },
        )
        return
    }

    pendingDelete?.let { book ->
        BackHandler { pendingDelete = null }
        ConfirmDeleteScreen(
            bookTitle = book.title,
            heading = "Delete this book?",
            onConfirm = { onDeleteBook(book.id); pendingDelete = null; bookToMove = null },
            onCancel = { pendingDelete = null },
        )
        return
    }

    bookToRename?.let { book ->
        BackHandler { bookToRename = null }
        RenameFolderScreen(
            currentName = book.title,
            heading = "Rename book",
            onSubmit = {
                onRenameBook(book.id, it)
                bookToRename = null
                bookToMove = null
            },
            onCancel = { bookToRename = null },
        )
        return
    }

    bookToMove?.let { book ->
        BackHandler { bookToMove = null }
        FolderPickerScreen(
            bookTitle = book.title,
            folders = allFolderNames,
            currentFolder = book.id.substringBeforeLast('/', ""),
            onPick = { folder -> onMoveBook(book.id, folder); bookToMove = null },
            onRename = { bookToRename = book },
            onDelete = { pendingDelete = book },
            onCancel = { bookToMove = null },
        )
        return
    }

    // Refresh once when the screen first composes, and again whenever the user
    // swipes back to the library page (isActive flips false→true). Walking the
    // books directory is fast but does run on Dispatchers.IO and can stutter the
    // pager if it lands mid-swipe — gating on isActive keeps it off the
    // critical path while the user is still scrubbing through pages.
    LaunchedEffect(isActive) { if (isActive) onRefresh() }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    // Re-claim rotary focus on page activation AND on Activity resume —
    // backgrounding the app dropped focus silently, leaving the bezel inert
    // until the user touched the screen. Keyed on isActive plus resume tick.
    ClaimRotaryFocusOnActive(
        isActive,
        focusRequester,
        books.size,
        folderNames.size,
    )

    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(allFolderNames, selectedFolder) {
        if (selectedFolder != null && selectedFolder !in allFolderNames) {
            selectedFolder = null
        }
    }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showFirstRunHint) {
                item(key = "how_to_use_hint") {
                    Chip(
                        label = { Text("How to use") },
                        onClick = {
                            showFirstRunHint = false
                            showInstructions = true
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Explicit "New folder +" button at the top — discoverable affordance
            // that replaces the long-press-empty-area gesture. Centered, compact.
            item(key = "new_folder_btn") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CompactChip(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = FolderGreyText,
                            )
                        },
                        label = { Text("New folder", color = FolderGreyText) },
                        onClick = { showNewFolder = true },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = FolderGrey,
                            contentColor = FolderGreyText,
                        ),
                    )
                }
            }
            if (allFolderNames.isNotEmpty()) {
                item(key = "folder_chips") {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (folder in allFolderNames) {
                            val isSelected = folder == selectedFolder
                            val count = grouped[folder]?.size ?: 0
                            // Don't use pointerInput for the long-press — it interferes
                            // with the parent pager's swipe. combinedClickable cooperates
                            // with the gesture system and lets drags propagate.
                            val onFolderClick = { selectedFolder = if (isSelected) null else folder }
                            // No icon — keeps chips compact so more fit per row.
                            // Selection is conveyed by background alpha and by the
                            // book list expanding directly below.
                            FolderPill(
                                text = "$folder ($count)",
                                selected = isSelected,
                                onClick = onFolderClick,
                                onLongPress = { folderAction = folder },
                            )
                        }
                    }
                }
                selectedFolder?.let { folder ->
                    val folderBooks = grouped[folder] ?: emptyList()
                    if (folderBooks.isEmpty()) {
                        item(key = "empty_folder_${folder}") {
                            Text(
                                "(empty)",
                                style = MaterialTheme.typography.caption2,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
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

            if (books.isEmpty() && allFolderNames.isEmpty()) {
                item(key = "empty_state") {
                    Text(
                        text = stringResource(R.string.empty_library),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = "library_storage") {
                LibraryStorageFooter(storageInfo)
            }
        }
    }
}

@Composable
private fun LibraryStorageFooter(info: LibraryStorageInfo?) {
    val used = info?.usedBytes?.let(::formatBytes) ?: "..."
    val free = info?.freeBytes?.let(::formatBytes) ?: "..."
    Text(
        text = "Library: $used\nFree: $free",
        color = Color.White.copy(alpha = 0.58f),
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private fun File.storageInfo(): LibraryStorageInfo {
    val used = if (exists()) {
        walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    } else {
        0L
    }
    val storageRoot = takeIf { exists() } ?: parentFile ?: this
    return LibraryStorageInfo(usedBytes = used, freeBytes = storageRoot.usableSpace)
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.1f MB".format(bytes / mb)
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

@Composable
private fun ConfirmDeleteScreen(
    bookTitle: String,
    heading: String = "Delete this book?",
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    ClaimRotaryFocusOnActive(active = true, focusRequester = focusRequester)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "back") { BackChipRow(onClick = onCancel) }
            item(key = "title") {
                Text(
                    heading,
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var moveExpanded by rememberSaveable(bookTitle) { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    ClaimRotaryFocusOnActive(active = true, focusRequester = focusRequester)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "back") {
                BackChipRow(onClick = onCancel)
            }
            item(key = "title") {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item(key = "rename") {
                Chip(
                    label = { Text("Rename book") },
                    onClick = onRename,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "move") {
                Chip(
                    icon = {
                        Icon(
                            imageVector = if (moveExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Move book") },
                    onClick = { moveExpanded = !moveExpanded },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (moveExpanded) {
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
            }
            item(key = "delete") {
                Chip(
                    label = { Text("Delete book", color = Color.White) },
                    onClick = onDelete,
                    colors = ChipDefaults.chipColors(backgroundColor = DeleteRed, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FolderActionsScreen(
    folderName: String,
    rootBooks: List<Book>,
    onRename: () -> Unit,
    onMoveRootBook: (Book) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var moveExpanded by rememberSaveable(folderName) { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    ClaimRotaryFocusOnActive(active = true, focusRequester = focusRequester)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "back") { BackChipRow(onClick = onCancel) }
            item(key = "title") {
                Text(
                    folderName,
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item(key = "rename") {
                Chip(
                    label = { Text("Rename") },
                    onClick = onRename,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "move_to") {
                Chip(
                    icon = {
                        Icon(
                            imageVector = if (moveExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Move to") },
                    onClick = { moveExpanded = !moveExpanded },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (moveExpanded) {
                if (rootBooks.isEmpty()) {
                    item(key = "move_empty") {
                        Text(
                            "No Root books",
                            style = MaterialTheme.typography.caption2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    items(rootBooks, key = { "move_${it.id}" }) { book ->
                        Chip(
                            label = { Text(book.title) },
                            secondaryLabel = { Text(book.format.name.lowercase()) },
                            onClick = { onMoveRootBook(book) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item(key = "delete") {
                Chip(
                    label = { Text("Delete folder", color = Color.White) },
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RenameFolderScreen(
    currentName: String,
    heading: String = "Rename folder",
    maxLength: Int = Int.MAX_VALUE,
    validator: (String) -> String? = { null },
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colors.onSurface
    val trimmed = text.trim()
    val error = validator(trimmed)
    val canSubmit = trimmed.isNotBlank() && error == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(heading)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, onSurface.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isBlank()) {
                Text(
                    text = "Name",
                    color = onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.button,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                singleLine = true,
                textStyle = TextStyle(
                    color = onSurface,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start,
                ),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (canSubmit) onSubmit(trimmed)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val helperText = error ?: if (maxLength != Int.MAX_VALUE) "${trimmed.length}/$maxLength" else ""
        if (helperText.isNotEmpty()) {
            Text(
                text = helperText,
                color = if (error == null) onSurface.copy(alpha = 0.65f) else DeleteRed,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                textAlign = TextAlign.Center,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Chip(
                label = { Text("OK") },
                onClick = { if (canSubmit) onSubmit(trimmed) },
                colors = ChipDefaults.primaryChipColors(),
                enabled = canSubmit,
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

@Composable
internal fun BackChipRow(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CompactChip(
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = FolderGreyText,
                )
            },
            label = { Text("Back", color = FolderGreyText) },
            onClick = onClick,
            colors = ChipDefaults.chipColors(backgroundColor = FolderGrey, contentColor = FolderGreyText),
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) FolderGrey.copy(alpha = 0.55f) else FolderGrey)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = FolderGreyText,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookChip(book: Book, onClick: () -> Unit, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(BookChipBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(book.title, style = MaterialTheme.typography.button, maxLines = 1)
        Text(
            book.format.name,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            maxLines = 1,
        )
    }
}
