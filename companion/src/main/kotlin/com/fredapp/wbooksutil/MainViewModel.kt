package com.fredapp.wbooksutil

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)
    private var watchPollingJob: Job? = null

    data class UiState(
        val books: List<BookSummary> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val bookFolders: Map<String, String> = emptyMap(),
        val loading: Boolean = false,
        val sending: Boolean = false,
        /** When [sending], the filename being uploaded — shown in the progress dialog. */
        val sendingFilename: String? = null,
        /** Cumulative bytes sent for the current upload. -1 if unknown / not started. */
        val sendingProgressBytes: Long = 0L,
        /** Total expected bytes for the current upload, or -1 if unknown (indeterminate bar). */
        val sendingProgressTotal: Long = -1L,
        val noWatch: Boolean = false,
        val errorMessage: String? = null,
        // Folders created locally but not yet confirmed by a book assignment.
        // Kept so newly created folders remain visible until the user drags a book in.
        val pendingFolders: Set<String> = emptySet(),
        // Set when the user picks a .pdf; the UI shows the experimental-PDF
        // warning before any conversion happens. Cleared on confirm or cancel.
        val pendingPdf: PendingPdf? = null,
        // Sticky for the ViewModel's lifetime once the user clicks "Convert" on
        // the warning dialog — subsequent PDF picks bypass the dialog. Reset on
        // process death, which is the right scope for "this session".
        val pdfWarningAcknowledged: Boolean = false,
    )

    data class PendingPdf(val uri: Uri, val filename: String)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        refreshLibrary(showLoading = true)
    }

    fun startForegroundWatchPolling() {
        if (watchPollingJob?.isActive == true) return
        watchPollingJob = viewModelScope.launch {
            while (isActive) {
                pollWatchConnection()
                delay(WATCH_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopForegroundWatchPolling() {
        watchPollingJob?.cancel()
        watchPollingJob = null
    }

    fun createFolder(name: String) {
        val validation = FolderPolicy.validateCreate(name, _state.value.folders.map { it.id })
        val trimmed = validation.name
        if (trimmed == null) {
            _state.value = _state.value.copy(errorMessage = validation.error)
            return
        }
        val newFolder = Folder(id = trimmed, name = trimmed)
        _state.value = _state.value.copy(
            folders = (_state.value.folders + newFolder).distinctBy { it.id },
            pendingFolders = _state.value.pendingFolders + trimmed,
        )
        viewModelScope.launch {
            applyResult(repo.mkdirBook(trimmed))
        }
    }

    fun renameFolder(oldName: String, newName: String) {
        val validation = FolderPolicy.validateRename(oldName, newName, _state.value.folders.map { it.id })
        val trimmed = validation.name
        if (trimmed == null) {
            _state.value = _state.value.copy(errorMessage = validation.error)
            return
        }
        if (trimmed == oldName) return
        viewModelScope.launch {
            applyResult(repo.renameFolder(oldName, trimmed))
        }
    }

    fun deleteFolder(folderId: String) {
        _state.value = _state.value.copy(
            folders = _state.value.folders.filter { it.id != folderId },
            pendingFolders = _state.value.pendingFolders - folderId,
        )
        viewModelScope.launch {
            applyResult(repo.deleteBook(folderId))
        }
    }

    fun assignBookToFolder(bookId: String, folderId: String?) {
        val targetFolder = folderId ?: ""
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = repo.moveBook(bookId, targetFolder)
            _state.value = _state.value.copy(sending = false)
            applyResult(result)
        }
    }

    private suspend fun pollWatchConnection() {
        if (_state.value.sending) return
        val reachable = repo.hasReachableWatch()
        val state = _state.value
        when {
            reachable && state.noWatch -> refreshLibrary(showLoading = false)
            reachable && !state.noWatch -> refreshLibrary(showLoading = false)
            !reachable && !state.noWatch -> {
                _state.value = state.disconnectedCopy()
            }
        }
    }

    private suspend fun refreshLibrary(showLoading: Boolean) {
        if (showLoading) {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
        }
        applyResult(repo.fetchLibrary())
        if (showLoading) {
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        applyResult(repo.deleteBook(id))
    }

    fun upload(uri: Uri) {
        val filename = displayNameFor(uri) ?: "book"
        if (filename.substringAfterLast('.', "").equals("pdf", ignoreCase = true)) {
            val pending = PendingPdf(uri, filename)
            if (_state.value.pdfWarningAcknowledged) {
                // User already saw the warning in this session — skip the dialog
                // and convert immediately.
                runPdfConversion(pending)
            } else {
                _state.value = _state.value.copy(pendingPdf = pending)
            }
            return
        }
        uploadDirect(uri, filename)
    }

    fun confirmPdfConversion() {
        val pending = _state.value.pendingPdf ?: return
        _state.value = _state.value.copy(pendingPdf = null, pdfWarningAcknowledged = true)
        runPdfConversion(pending)
    }

    fun cancelPdfConversion() {
        _state.value = _state.value.copy(pendingPdf = null)
    }

    private fun runPdfConversion(pending: PendingPdf) = viewModelScope.launch {
        _state.value = _state.value.copy(
            sending = true,
            sendingFilename = pending.filename,
            sendingProgressBytes = 0L,
            sendingProgressTotal = -1L,
            errorMessage = null,
        )
        val converted = convertPdf(pending)
        if (converted == null) {
            _state.value = _state.value.copy(
                sending = false,
                sendingFilename = null,
                errorMessage = "Could not read PDF. It may be encrypted or corrupted.",
            )
            return@launch
        }
        val outName = pdfOutputName(pending.filename)
        val bytes = converted.html.toByteArray(Charsets.UTF_8)
        val result = repo.uploadStream(
            bytes.inputStream(),
            outName,
            totalBytes = bytes.size.toLong(),
        ) { sent, total ->
            _state.value = _state.value.copy(
                sendingProgressBytes = sent,
                sendingProgressTotal = total,
            )
        }
        _state.value = _state.value.copy(
            sending = false,
            sendingFilename = null,
            sendingProgressBytes = 0L,
            sendingProgressTotal = -1L,
        )
        when (result) {
            is WatchRepository.Result.Ok -> refresh()
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.disconnectedCopy()
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    private fun uploadDirect(uri: Uri, filename: String) = viewModelScope.launch {
        _state.value = _state.value.copy(
            sending = true,
            sendingFilename = filename,
            sendingProgressBytes = 0L,
            sendingProgressTotal = -1L,
            errorMessage = null,
        )
        val result = repo.uploadBook(uri, filename) { sent, total ->
            _state.value = _state.value.copy(
                sendingProgressBytes = sent,
                sendingProgressTotal = total,
            )
        }
        _state.value = _state.value.copy(
            sending = false,
            sendingFilename = null,
            sendingProgressBytes = 0L,
            sendingProgressTotal = -1L,
        )
        when (result) {
            is WatchRepository.Result.Ok -> refresh()
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.disconnectedCopy()
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    private suspend fun convertPdf(pending: PendingPdf): PdfConverter.Result? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.openInputStream(pending.uri)?.use { input ->
                PdfConverter.convert(input, baseTitle(pending.filename))
            }
        }.getOrNull()
    }

    private fun baseTitle(filename: String): String {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0) filename.substring(0, dot) else filename
    }

    private fun pdfOutputName(filename: String): String =
        "${baseTitle(filename)} [PDF].html"

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun applyResult(result: WatchRepository.Result<LibrarySnapshot>) {
        when (result) {
            is WatchRepository.Result.Ok -> {
                val snapshot = result.value
                val books = snapshot.books
                // Derive folder membership from book ID path prefixes.
                // "Fiction/moby-dick.epub" -> folder "Fiction"; "moby-dick.epub" -> uncategorized.
                val bookFolders = books
                    .mapNotNull { book ->
                        val folder = book.id.substringBeforeLast('/', "")
                        if (folder.isNotEmpty()) book.id to folder else null
                    }
                    .toMap()
                val derivedFolderNames = (bookFolders.values + snapshot.folders).distinct().sorted().toSet()
                val pending = _state.value.pendingFolders - derivedFolderNames
                val allFolders = (derivedFolderNames + pending).sorted()
                    .map { Folder(id = it, name = it) }
                _state.value = _state.value.copy(
                    books = books,
                    folders = allFolders,
                    bookFolders = bookFolders,
                    noWatch = false,
                    pendingFolders = pending,
                )
            }
            is WatchRepository.Result.NoWatch ->
                _state.value = _state.value.disconnectedCopy()
            is WatchRepository.Result.Error ->
                _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    private fun UiState.disconnectedCopy(): UiState = copy(
        books = emptyList(),
        folders = emptyList(),
        bookFolders = emptyMap(),
        loading = false,
        noWatch = true,
        pendingFolders = emptySet(),
        pendingPdf = null,
    )

    private fun displayNameFor(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(app) as T
    }

    companion object {
        private const val WATCH_POLL_INTERVAL_MS = 5_000L
    }
}
