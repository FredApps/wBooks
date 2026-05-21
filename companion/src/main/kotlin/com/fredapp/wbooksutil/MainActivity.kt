package com.fredapp.wbooksutil

import android.content.ClipData
import android.content.ClipDescription
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels { MainViewModel.Factory(application) }
    private val gutenbergViewModel: GutenbergViewModel by viewModels { GutenbergViewModel.Factory(application) }
    private val statsViewModel: StatsViewModel by viewModels { StatsViewModel.Factory(application) }
    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                DisposableEffect(mainViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> mainViewModel.startForegroundWatchPolling()
                            Lifecycle.Event.ON_STOP -> mainViewModel.stopForegroundWatchPolling()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    mainViewModel.startForegroundWatchPolling()
                    onDispose {
                        lifecycle.removeObserver(observer)
                        mainViewModel.stopForegroundWatchPolling()
                    }
                }
                when (screen) {
                    Screen.GUTENBERG -> GutenbergScreen(
                        vm = gutenbergViewModel,
                        onBack = {
                            screen = Screen.LIBRARY
                            mainViewModel.refresh()
                        },
                    )
                    Screen.STATS -> StatsScreen(
                        vm = statsViewModel,
                        onBack = { screen = Screen.LIBRARY },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        vm = settingsViewModel,
                        onBack = { screen = Screen.LIBRARY },
                    )
                    Screen.LIBRARY -> CompanionScreen(
                        vm = mainViewModel,
                        onBrowseGutenberg = { screen = Screen.GUTENBERG },
                        onShowStats = {
                            screen = Screen.STATS
                            statsViewModel.refresh()
                        },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                }
            }
        }
    }

    private enum class Screen { LIBRARY, GUTENBERG, STATS, SETTINGS }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    vm: MainViewModel,
    onBrowseGutenberg: () -> Unit,
    onShowStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::upload) }

    var pendingDelete by remember { mutableStateOf<BookSummary?>(null) }
    var pendingDeleteFolder by remember { mutableStateOf<Folder?>(null) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onShowStats) {
                        Icon(Icons.Default.DateRange, contentDescription = "Reading stats")
                    }
                    IconButton(onClick = onBrowseGutenberg) {
                        Icon(Icons.Default.Search, contentDescription = "Project Gutenberg")
                    }
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.new_folder))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Watch settings")
                    }
                    // No explicit refresh button — the polling loop in MainViewModel
                    // refetches the library + connection state every 5 seconds.
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFile.launch(SUPPORTED_MIME) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_book)) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.books.isEmpty() -> CenteredProgress()
                state.noWatch -> CenteredText(stringResource(R.string.no_watch))
                state.books.isEmpty() && state.folders.isEmpty() ->
                    CenteredText(stringResource(R.string.empty_library))
                else -> BookList(
                    books = state.books,
                    folders = state.folders,
                    bookFolders = state.bookFolders,
                    onDelete = { pendingDelete = it },
                    onDeleteFolder = { pendingDeleteFolder = it },
                    onRenameFolder = { folderToRename = it },
                    onAssignToFolder = vm::assignBookToFolder,
                )
            }
        }
    }
    if (state.sending) {
        UploadProgressDialog(
            filename = state.sendingFilename,
            sentBytes = state.sendingProgressBytes,
            totalBytes = state.sendingProgressTotal,
        )
    }

    // Delete book
    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(book.title) },
            text = { Text(stringResource(R.string.confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(book.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Delete folder
    pendingDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFolder = null },
            title = { Text(folder.name) },
            text = { Text(stringResource(R.string.confirm_delete_folder)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFolder(folder.id)
                    pendingDeleteFolder = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFolder = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Rename folder
    folderToRename?.let { folder ->
        var newName by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameFolder(folder.id, newName)
                        folderToRename = null
                    },
                    enabled = newName.isNotBlank() && newName.trim() != folder.name,
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // New folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.createFolder(folderName); showNewFolderDialog = false },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    state.pendingPdf?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelPdfConversion,
            title = { Text(stringResource(R.string.pdf_warning_title)) },
            text = { Text(stringResource(R.string.pdf_warning_body)) },
            confirmButton = {
                TextButton(onClick = vm::confirmPdfConversion) {
                    Text(stringResource(R.string.pdf_warning_convert))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPdfConversion) {
                    Text(stringResource(R.string.cancel))
                }
            },
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

/**
 * Single scrolling list. Each folder chip is followed inline by the books that
 * live in it when expanded. Root sits at the end of the list — when there are
 * many folders, the natural scroll position settles with Root in the middle of
 * the screen. The whole list scrolls together so users don't have to manage two
 * scroll viewports.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<BookSummary>,
    folders: List<Folder>,
    bookFolders: Map<String, String>,
    onDelete: (BookSummary) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onAssignToFolder: (bookId: String, folderId: String?) -> Unit,
) {
    var expandedFolders by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var rootExpanded by rememberSaveable { mutableStateOf(true) }
    val rootBooks = books.filter { it.id !in bookFolders }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (folder in folders) {
            val folderBooks = books.filter { bookFolders[it.id] == folder.id }
            val isExpanded = folder.id in expandedFolders
            item(key = "fc_${folder.id}") {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FolderChip(
                        folder = folder,
                        bookCount = folderBooks.size,
                        selected = isExpanded,
                        onToggle = {
                            expandedFolders = if (isExpanded) expandedFolders - folder.id
                                              else expandedFolders + folder.id
                        },
                        onRename = { onRenameFolder(folder) },
                        onDelete = { onDeleteFolder(folder) },
                        onDrop = { bookId -> onAssignToFolder(bookId, folder.id) },
                    )
                }
            }
            if (isExpanded) {
                items(folderBooks, key = { "b_${folder.id}_${it.id}" }) { book ->
                    BookItem(book = book, onDelete = onDelete)
                    HorizontalDivider()
                }
                if (folderBooks.isEmpty()) {
                    item(key = "fe_${folder.id}") {
                        Text(
                            text = "Empty — drag a book onto the folder chip to add it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        item(key = "root_header") {
            RootHeader(
                rootBooks = rootBooks,
                expanded = rootExpanded,
                onToggle = { rootExpanded = !rootExpanded },
                onDrop = { bookId -> onAssignToFolder(bookId, null) },
            )
        }
        if (rootExpanded) {
            if (rootBooks.isEmpty()) {
                item(key = "root_empty") {
                    Text(
                        "Drop books here to move them to Root",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(rootBooks, key = { "rb_${it.id}" }) { book ->
                    BookItem(book = book, onDelete = onDelete)
                    HorizontalDivider()
                }
            }
        }
        // Trailing spacer so the last book row scrolls clear of the FAB.
        item(key = "fab_spacer") { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RootHeader(
    rootBooks: List<BookSummary>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDrop: (bookId: String) -> Unit,
) {
    val isDragOver = remember { mutableStateOf(false) }
    val onDropRef = rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val bookId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                    ?: return false
                onDropRef.value(bookId)
                isDragOver.value = false
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { isDragOver.value = true }
            override fun onExited(event: DragAndDropEvent) { isDragOver.value = false }
            override fun onEnded(event: DragAndDropEvent) { isDragOver.value = false }
        }
    }
    val sectionBg = if (isDragOver.value) MaterialTheme.colorScheme.surfaceContainerLow
                    else MaterialTheme.colorScheme.surfaceVariant
    ListItem(
        leadingContent = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${stringResource(R.string.uncategorized)} (${rootBooks.size})", fontWeight = FontWeight.SemiBold)
            }
        },
        colors = ListItemDefaults.colors(containerColor = sectionBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.toAndroidDragEvent().clipDescription
                        ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                },
                target = target,
            ),
    )
}

@Composable
private fun UploadProgressDialog(filename: String?, sentBytes: Long, totalBytes: Long) {
    val title = filename ?: stringResource(R.string.sending)
    val pct: Float? = if (totalBytes > 0L) (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    AlertDialog(
        onDismissRequest = { /* Upload is in flight; ignore taps outside. */ },
        title = { Text("Sending") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, maxLines = 2)
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${humanBytes(sentBytes)} of ${humanBytes(totalBytes)} (${(pct * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = if (sentBytes > 0) "${humanBytes(sentBytes)} sent" else "Starting…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

private fun humanBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChip(
    folder: Folder,
    bookCount: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDrop: (bookId: String) -> Unit,
) {
    val isDragOver = remember { mutableStateOf(false) }
    val onDropRef = rememberUpdatedState(onDrop)
    val target = remember(folder.id) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val bookId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                    ?: return false
                onDropRef.value(bookId)
                isDragOver.value = false
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { isDragOver.value = true }
            override fun onExited(event: DragAndDropEvent) { isDragOver.value = false }
            override fun onEnded(event: DragAndDropEvent) { isDragOver.value = false }
        }
    }
    // Neutral grey palette to match the watch (FolderGrey) and web (.folder-row).
    // Drag-over keeps a slight tint shift via surfaceContainerLow as the
    // affordance signal; selected vs default differ by one container tier.
    val container = when {
        isDragOver.value -> MaterialTheme.colorScheme.surfaceContainerLow
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = container,
        shadowElevation = if (isSystemInDarkTheme()) 0.dp else 3.dp,
        tonalElevation = if (isSystemInDarkTheme()) 2.dp else 0.dp,
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.toAndroidDragEvent().clipDescription
                    ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            },
            target = target,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // Chevron matches the Root section header: right-pointing when
            // collapsed, down-pointing when this folder's books are expanded
            // below. Same visual language as the watch and the web UI.
            Icon(
                if (selected) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${folder.name} ($bookCount)", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename folder")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

// (RootSection removed — Root is now an inline row in the single BookList LazyColumn,
//  handled by RootHeader.)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookItem(book: BookSummary, onDelete: (BookSummary) -> Unit) {
    ListItem(
        headlineContent = { Text(book.title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(book.format) },
        trailingContent = {
            IconButton(onClick = { onDelete(book) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
        modifier = Modifier.dragAndDropSource {
            detectTapGestures(onLongPress = {
                startTransfer(DragAndDropTransferData(ClipData.newPlainText("bookId", book.id)))
            })
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

/** MIME types that SAF should offer. Matches the parsers in `:app`, plus
 *  application/pdf for the experimental PDF→HTML converter (handled in this
 *  utility app only; the watch never receives a raw PDF). */
private val SUPPORTED_MIME = arrayOf(
    "application/epub+zip",
    "text/plain",
    "application/x-fictionbook+xml",
    "text/html",
    "application/xhtml+xml",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
    "application/pdf",
    "application/octet-stream",
)
