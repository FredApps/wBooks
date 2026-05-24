package com.fredapp.wbooksutil

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
                BackHandler(enabled = screen != Screen.LIBRARY) {
                    if (screen == Screen.GUTENBERG) mainViewModel.refresh()
                    screen = Screen.LIBRARY
                }
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
        handleSharedBooks(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedBooks(intent)
    }

    private fun handleSharedBooks(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtraCompat<android.net.Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtraCompat<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            mainViewModel.uploadShared(uris)
            setIntent(Intent(intent).apply {
                action = Intent.ACTION_MAIN
                removeExtra(Intent.EXTRA_STREAM)
            })
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
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.Info, contentDescription = "How to use")
                    }
                    IconButton(onClick = onShowStats) {
                        Icon(Icons.Default.DateRange, contentDescription = "Reading stats")
                    }
                    IconButton(onClick = onBrowseGutenberg) {
                        // PG monogram (drawable/ic_gutenberg_pg) instead of a generic
                        // search glyph — the Project Gutenberg button now reads as the
                        // PG brand mark, distinct from the in-library title search.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_gutenberg_pg),
                            contentDescription = "Project Gutenberg",
                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                        )
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
                else -> BoundedBookList(
                    books = state.books,
                    folders = state.folders,
                    bookFolders = state.bookFolders,
                    storage = state.storage,
                    onDelete = { pendingDelete = it },
                    onDeleteFolder = { pendingDeleteFolder = it },
                    onRenameFolder = { folderToRename = it },
                    onAssignToFolder = vm::assignBookToFolder,
                    onReorderBook = vm::reorderBook,
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
        val folderNames = remember(state.folders) { state.folders.map { it.id } }
        val validation = FolderPolicy.validateRename(folder.id, newName, folderNames)
        val canRename = validation.error == null && validation.name != null && validation.name != folder.name
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(FolderPolicy.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.folder_name)) },
                    supportingText = {
                        Text(validation.error ?: "${newName.trim().length}/${FolderPolicy.MAX_NAME_LENGTH}")
                    },
                    isError = validation.error != null,
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
                    enabled = canRename,
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
        val folderNames = remember(state.folders) { state.folders.map { it.id } }
        val validation = FolderPolicy.validateCreate(folderName, folderNames)
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it.take(FolderPolicy.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.folder_name)) },
                    supportingText = {
                        Text(validation.error ?: "${folderName.trim().length}/${FolderPolicy.MAX_NAME_LENGTH}; ${folderNames.size}/${FolderPolicy.MAX_FOLDERS} folders")
                    },
                    isError = validation.error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.createFolder(folderName); showNewFolderDialog = false },
                    enabled = validation.error == null,
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

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("How to use") },
            text = {
                Box(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    InstructionsBlock()
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("OK") }
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
 * Keeps Root pinned below the folder area. The folder stack can grow to half
 * the available height; after that it scrolls independently.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BoundedBookList(
    books: List<BookSummary>,
    folders: List<Folder>,
    bookFolders: Map<String, String>,
    storage: StorageSummary?,
    onDelete: (BookSummary) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onAssignToFolder: (bookId: String, folderId: String?) -> Unit,
    onReorderBook: (fromId: String, targetId: String, placeAfterTarget: Boolean) -> Unit,
) {
    var expandedFolders by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var rootExpanded by rememberSaveable { mutableStateOf(true) }
    var rootOffsetDp by rememberSaveable { mutableFloatStateOf(0f) }
    val rootBooks = books.filter { it.id !in bookFolders }
    val folderListState = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val folderMaxHeight = maxHeight / 2
        val maxRootOffsetDp = (maxHeight / 4).value.coerceAtLeast(0f)
        val folderPaneHeight = folderPaneHeight(
            folders = folders,
            books = books,
            bookFolders = bookFolders,
            expandedFolders = expandedFolders,
            maxHeight = folderMaxHeight,
        )
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryStorageSummary(storage)
            if (folders.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(folderPaneHeight),
                ) {
                    LazyColumn(
                        state = folderListState,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        folderItems(
                            books = books,
                            folders = folders,
                            bookFolders = bookFolders,
                            expandedFolders = expandedFolders,
                            onExpandedFoldersChange = { expandedFolders = it },
                            onDelete = onDelete,
                            onDeleteFolder = onDeleteFolder,
                            onRenameFolder = onRenameFolder,
                            onAssignToFolder = onAssignToFolder,
                            onReorderBook = onReorderBook,
                        )
                    }
                    FolderScrollbar(state = folderListState)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (folders.isNotEmpty()) {
                    item(key = "root_drag_gap") {
                        RootPositionHandle(
                            offsetDp = rootOffsetDp,
                            onDrag = { dragPixels ->
                                val deltaDp = with(density) { dragPixels.toDp().value }
                                rootOffsetDp = (rootOffsetDp + deltaDp).coerceIn(0f, maxRootOffsetDp)
                            },
                        )
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
                            BookItem(book = book, onDelete = onDelete, onReorderBook = onReorderBook)
                            HorizontalDivider()
                        }
                    }
                }
                item(key = "fab_spacer") { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryStorageSummary(storage: StorageSummary?) {
    val used = storage?.usedBytes?.let(::humanBytes) ?: "..."
    val free = storage?.freeBytes?.let(::humanBytes) ?: "..."
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = "Library: $used\nFree: $free",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RootPositionHandle(
    offsetDp: Float,
    onDrag: (dragPixels: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp + offsetDp.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier
                .padding(bottom = 5.dp)
                .width(44.dp)
                .height(4.dp),
            content = {},
        )
    }
}

private fun folderPaneHeight(
    folders: List<Folder>,
    books: List<BookSummary>,
    bookFolders: Map<String, String>,
    expandedFolders: Set<String>,
    maxHeight: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp {
    val expandedRows = folders.sumOf { folder ->
        if (folder.id !in expandedFolders) 0
        else {
            val count = books.count { bookFolders[it.id] == folder.id }
            if (count == 0) 1 else count
        }
    }
    val estimated = (folders.size * 64 + expandedRows * 56).dp
    return minOf(estimated, maxHeight)
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.folderItems(
    books: List<BookSummary>,
    folders: List<Folder>,
    bookFolders: Map<String, String>,
    expandedFolders: Set<String>,
    onExpandedFoldersChange: (Set<String>) -> Unit,
    onDelete: (BookSummary) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onAssignToFolder: (bookId: String, folderId: String?) -> Unit,
    onReorderBook: (fromId: String, targetId: String, placeAfterTarget: Boolean) -> Unit,
) {
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
                        onExpandedFoldersChange(
                            if (isExpanded) expandedFolders - folder.id
                            else expandedFolders + folder.id,
                        )
                    },
                    onRename = { onRenameFolder(folder) },
                    onDelete = { onDeleteFolder(folder) },
                    onDrop = { bookId -> onAssignToFolder(bookId, folder.id) },
                )
            }
        }
        if (isExpanded) {
            items(folderBooks, key = { "b_${folder.id}_${it.id}" }) { book ->
                BookItem(book = book, onDelete = onDelete, onReorderBook = onReorderBook)
                HorizontalDivider()
            }
            if (folderBooks.isEmpty()) {
                item(key = "fe_${folder.id}") {
                    Text(
                        text = "Empty - drag a book onto the folder chip to add it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java)
    else @Suppress("DEPRECATION") (getParcelableExtra(name) as? T)

private inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java)
    else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)

@Composable
private fun BoxScope.FolderScrollbar(state: LazyListState) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Canvas(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(10.dp)
            .padding(vertical = 6.dp, horizontal = 3.dp),
    ) {
        val visibleCount = state.layoutInfo.visibleItemsInfo.size
        val totalCount = state.layoutInfo.totalItemsCount
        if (totalCount <= visibleCount || visibleCount == 0) return@Canvas

        val trackWidth = 2.dp.toPx()
        val corner = CornerRadius(trackWidth, trackWidth)
        val x = (size.width - trackWidth) / 2f
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(x, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = corner,
        )

        val thumbHeight = (size.height * visibleCount / totalCount)
            .coerceAtLeast(24.dp.toPx())
            .coerceAtMost(size.height)
        val maxFirst = (totalCount - visibleCount).coerceAtLeast(1)
        val progress = state.firstVisibleItemIndex.toFloat() / maxFirst.toFloat()
        val thumbTop = (size.height - thumbHeight) * progress.coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, thumbTop),
            size = Size(trackWidth, thumbHeight),
            cornerRadius = corner,
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
    onReorderBook: (fromId: String, targetId: String, placeAfterTarget: Boolean) -> Unit,
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
                    BookItem(book = book, onDelete = onDelete, onReorderBook = onReorderBook)
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
                    BookItem(book = book, onDelete = onDelete, onReorderBook = onReorderBook)
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
private fun BookItem(
    book: BookSummary,
    onDelete: (BookSummary) -> Unit,
    onReorderBook: (fromId: String, targetId: String, placeAfterTarget: Boolean) -> Unit,
) {
    val heightPx = remember { mutableStateOf(1) }
    val target = remember(book.id) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val draggedId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                    ?: return false
                if (draggedId == book.id) return false
                val placeAfter = event.toAndroidDragEvent().y > heightPx.value / 2f
                onReorderBook(draggedId, book.id, placeAfter)
                return true
            }
        }
    }
    ListItem(
        headlineContent = { Text(displayTitleWithTag(book.title, book.format), fontWeight = FontWeight.Medium) },
        trailingContent = {
            IconButton(onClick = { onDelete(book) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
        modifier = Modifier.dragAndDropSource {
            detectTapGestures(onLongPress = {
                startTransfer(DragAndDropTransferData(ClipData.newPlainText("bookId", book.id)))
            })
        }
            .onSizeChanged { heightPx.value = it.height.coerceAtLeast(1) }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.toAndroidDragEvent().clipDescription
                        ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                },
                target = target,
            ),
    )
}

/**
 * Mirrors the web library's book label: hide the on-disk extension and show
 * a single bracketed format tag instead. PDFs are stored as
 * `Title [PDF].html`; in that case keep the `[PDF]` and drop only `.html`.
 */
private fun displayTitleWithTag(rawTitle: String, format: String): String {
    val baseNoExt = if (rawTitle.contains('.')) rawTitle.substringBeforeLast('.') else rawTitle
    if (baseNoExt.endsWith(" [PDF]")) return baseNoExt
    val tag = format.uppercase().ifBlank { "FILE" }
    return "$baseNoExt [$tag]"
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
