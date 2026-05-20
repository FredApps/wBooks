package com.fredapp.wbooksutil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var pendingAssign by remember { mutableStateOf<BookSummary?>(null) }
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
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
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
                state.books.isEmpty() -> CenteredText(stringResource(R.string.empty_library))
                else -> BookList(
                    books = state.books,
                    folders = state.folders,
                    bookFolders = state.bookFolders,
                    onDelete = { pendingDelete = it },
                    onAssignFolder = { pendingAssign = it },
                    onDeleteFolder = { pendingDeleteFolder = it },
                )
            }
            if (state.sending) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(tonalElevation = 4.dp) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.sending))
                        }
                    }
                }
            }
        }
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
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
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
                TextButton(onClick = { pendingDeleteFolder = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Assign book to folder
    pendingAssign?.let { book ->
        val currentFolderId = state.bookFolders[book.id]
        AlertDialog(
            onDismissRequest = { pendingAssign = null },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (currentFolderId != null) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.remove_from_folder)) },
                            modifier = Modifier.clickable {
                                vm.assignBookToFolder(book.id, null)
                                pendingAssign = null
                            },
                        )
                        HorizontalDivider()
                    }
                    if (state.folders.isEmpty()) {
                        Text(
                            stringResource(R.string.no_folders_hint),
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        state.folders.forEach { folder ->
                            val isCurrent = folder.id == currentFolderId
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                               else LocalContentColor.current,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        folder.name,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                else Color.Unspecified,
                                    )
                                },
                                modifier = if (!isCurrent) Modifier.clickable {
                                    vm.assignBookToFolder(book.id, folder.id)
                                    pendingAssign = null
                                } else Modifier,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingAssign = null }) { Text(stringResource(R.string.cancel)) }
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
                    onClick = {
                        vm.createFolder(folderName)
                        showNewFolderDialog = false
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.cancel)) }
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

@Composable
private fun BookList(
    books: List<BookSummary>,
    folders: List<Folder>,
    bookFolders: Map<String, String>,
    onDelete: (BookSummary) -> Unit,
    onAssignFolder: (BookSummary) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (folder in folders) {
            val folderBooks = books.filter { bookFolders[it.id] == folder.id }
            item(key = "fh_${folder.id}") {
                FolderHeader(folder = folder, onDelete = { onDeleteFolder(folder) })
            }
            items(folderBooks, key = { "b_${it.id}" }) { book ->
                BookItem(book = book, onDelete = onDelete, onAssignFolder = onAssignFolder)
                HorizontalDivider()
            }
        }

        val uncategorized = books.filter { it.id !in bookFolders }
        if (uncategorized.isNotEmpty()) {
            if (folders.isNotEmpty()) {
                item(key = "uncategorized_header") {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.uncategorized),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
            items(uncategorized, key = { "b_${it.id}" }) { book ->
                BookItem(book = book, onDelete = onDelete, onAssignFolder = onAssignFolder)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FolderHeader(folder: Folder, onDelete: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(folder.name, fontWeight = FontWeight.SemiBold) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
private fun BookItem(
    book: BookSummary,
    onDelete: (BookSummary) -> Unit,
    onAssignFolder: (BookSummary) -> Unit,
) {
    ListItem(
        headlineContent = { Text(book.title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(book.format) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onAssignFolder(book) }) {
                    Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.move_to_folder))
                }
                IconButton(onClick = { onDelete(book) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
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

/** MIME types that SAF should offer. Matches the parsers in `:app`. */
private val SUPPORTED_MIME = arrayOf(
    "application/epub+zip",
    "text/plain",
    "application/x-fictionbook+xml",
    "text/html",
    "application/xhtml+xml",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
    "application/octet-stream",
)
