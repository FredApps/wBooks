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
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)
    private val app = application as CompanionApp
    private val localLibrary = app.libraryRepository
    private var watchPollingJob: Job? = null
    private var uploadJob: Job? = null

    data class UiState(
        val books: List<BookSummary> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val bookFolders: Map<String, String> = emptyMap(),
        val storage: StorageSummary? = null,
        val loading: Boolean = false,
        val sending: Boolean = false,
        /** When [sending], the filename being uploaded, shown in the progress dialog. */
        val sendingFilename: String? = null,
        /** Cumulative bytes sent for the current upload. -1 if unknown / not started. */
        val sendingProgressBytes: Long = 0L,
        /** Total expected bytes for the current upload, or -1 if unknown (indeterminate bar). */
        val sendingProgressTotal: Long = -1L,
        val noWatch: Boolean = false,
        val errorMessage: String? = null,
        val syncMessage: String? = null,
        // Folders created locally but not yet confirmed by a book assignment.
        // Kept so newly created folders remain visible until the user drags a book in.
        val pendingFolders: Set<String> = emptySet(),
        // Set when the user picks a .pdf; the UI shows the experimental-PDF
        // warning before any conversion happens. Cleared on confirm or cancel.
        val pendingPdf: PendingPdf? = null,
        // Sticky for the ViewModel's lifetime once the user clicks "Convert" on
        // the warning dialog; subsequent PDF picks bypass the dialog. Reset on
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
        val state = _state.value
        val currentFolder = state.bookFolders[bookId] ?: ""
        if (currentFolder == targetFolder) {
            moveBookToTopOfFolder(bookId, targetFolder)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = repo.moveBook(bookId, targetFolder)
            _state.value = _state.value.copy(sending = false)
            applyResult(result)
        }
    }

    private fun moveBookToTopOfFolder(bookId: String, folder: String) {
        val state = _state.value
        val folderBooks = state.books.filter { (state.bookFolders[it.id] ?: "") == folder }
        if (folderBooks.firstOrNull()?.id == bookId) return
        val reordered = listOfNotNull(folderBooks.firstOrNull { it.id == bookId }) +
            folderBooks.filterNot { it.id == bookId }
        if (reordered.size != folderBooks.size) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val result = repo.reorderBooks(folder, reordered.map { it.id })
            _state.value = _state.value.copy(sending = false)
            applyResult(result)
        }
    }

    fun reorderBook(fromId: String, targetId: String, placeAfterTarget: Boolean) {
        if (fromId == targetId) return
        val state = _state.value
        val fromFolder = state.bookFolders[fromId] ?: ""
        val targetFolder = state.bookFolders[targetId] ?: ""
        if (fromFolder != targetFolder) {
            assignBookToFolder(fromId, targetFolder.ifEmpty { null })
            return
        }
        val folderBooks = state.books.filter { (state.bookFolders[it.id] ?: "") == fromFolder }
        val reordered = folderBooks.toMutableList()
        val moved = reordered.firstOrNull { it.id == fromId } ?: return
        reordered.removeAll { it.id == fromId }
        val targetIndex = reordered.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return
        val insertAt = if (placeAfterTarget) targetIndex + 1 else targetIndex
        reordered.add(insertAt.coerceIn(0, reordered.size), moved)
        viewModelScope.launch {
            applyResult(repo.reorderBooks(fromFolder, reordered.map { it.id }))
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
        when (val result = repo.fetchLibrary()) {
            is WatchRepository.Result.Ok -> {
                val snapshot = if (pushPendingUploads(result.value)) {
                    when (val refreshed = repo.fetchLibrary()) {
                        is WatchRepository.Result.Ok -> refreshed.value
                        else -> result.value
                    }
                } else {
                    result.value
                }
                reconcileLocalCache(snapshot)
                applySnapshot(snapshot, noWatch = false, syncMessage = null)
            }
            WatchRepository.Result.NoWatch -> {
                applySnapshot(localSnapshot(), noWatch = true, syncMessage = pendingSyncBanner())
            }
            is WatchRepository.Result.Error -> {
                // Background sync hiccup — the local library still shows, so keep it
                // quiet (no central error dialog) and just reflect any pending sync.
                applySnapshot(
                    localSnapshot(),
                    noWatch = false,
                    syncMessage = pendingSyncBanner(),
                )
            }
        }
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
                // User already saw the warning in this session; skip the dialog
                // and convert immediately.
                runPdfConversion(pending)
            } else {
                _state.value = _state.value.copy(pendingPdf = pending)
            }
            return
        }
        uploadDirect(uri, filename)
    }

    fun uploadShared(uris: List<Uri>) {
        val supported = uris.mapNotNull { uri ->
            val filename = displayNameFor(uri) ?: uri.lastPathSegment ?: return@mapNotNull null
            if (isSupportedInputFilename(filename)) uri to filename else null
        }
        if (supported.isEmpty()) {
            _state.value = _state.value.copy(errorMessage = "No supported book files found in share.")
            return
        }
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            for ((uri, filename) in supported) {
                val ok = if (filename.substringAfterLast('.', "").equals("pdf", ignoreCase = true)) {
                    uploadPdfNow(PendingPdf(uri, filename))
                } else {
                    uploadDirectNow(uri, filename)
                }
                if (!ok) break
            }
            if (!_state.value.noWatch) refreshLibrary(showLoading = false)
        }
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
        if (uploadPdfNow(pending)) refresh()
    }

    private suspend fun uploadPdfNow(pending: PendingPdf): Boolean {
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
            return false
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
        cacheBytes(outName, bytes, pendingUpload = result !is WatchRepository.Result.Ok)
        when (result) {
            is WatchRepository.Result.Ok -> return true
            is WatchRepository.Result.NoWatch -> {
                applySnapshot(
                    localSnapshot(),
                    noWatch = true,
                    syncMessage = pendingSyncBanner(),
                )
            }
            is WatchRepository.Result.Error -> {
                applySnapshot(
                    localSnapshot(),
                    noWatch = false,
                    syncMessage = pendingSyncBanner(),
                    errorMessage = result.message,
                )
            }
        }
        return false
    }

    private fun uploadDirect(uri: Uri, filename: String) = viewModelScope.launch {
        if (uploadDirectNow(uri, filename)) refresh()
    }

    private suspend fun uploadDirectNow(uri: Uri, filename: String): Boolean {
        val cachedId = cacheUri(uri, filename, pendingUpload = false)
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
            is WatchRepository.Result.Ok -> return true
            is WatchRepository.Result.NoWatch -> {
                cachedId?.let(::markPendingUpload)
                applySnapshot(
                    localSnapshot(),
                    noWatch = true,
                    syncMessage = pendingSyncBanner(),
                )
            }
            is WatchRepository.Result.Error -> {
                if (!result.message.contains("Unsupported file type", ignoreCase = true)) {
                    cachedId?.let(::markPendingUpload)
                }
                applySnapshot(
                    localSnapshot(),
                    noWatch = false,
                    syncMessage = if (cachedId != null) pendingSyncBanner() else null,
                    errorMessage = result.message,
                )
            }
        }
        return false
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
            is WatchRepository.Result.Ok -> applySnapshot(result.value, noWatch = false, syncMessage = null)
            is WatchRepository.Result.NoWatch -> _state.value = _state.value.disconnectedCopy()
            is WatchRepository.Result.Error -> _state.value = _state.value.copy(errorMessage = result.message)
        }
    }

    private fun applySnapshot(
        snapshot: LibrarySnapshot,
        noWatch: Boolean,
        syncMessage: String?,
        errorMessage: String? = null,
    ) {
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
            storage = snapshot.storage,
            noWatch = noWatch,
            pendingFolders = pending,
            syncMessage = syncMessage,
            errorMessage = errorMessage,
        )
    }

    private fun UiState.disconnectedCopy(): UiState = copy(
        loading = false,
        noWatch = true,
        pendingPdf = null,
        syncMessage = pendingSyncBanner(),
    )

    private suspend fun localSnapshot(): LibrarySnapshot {
        localLibrary.refresh()
        val books = localLibrary.books.value.map { book ->
            BookSummary(
                id = book.id,
                title = book.title,
                format = book.format.name,
                sizeBytes = book.file.length(),
                modifiedAtMs = book.file.lastModified(),
            )
        }
        return LibrarySnapshot(
            books = books,
            folders = localLibrary.folders.value,
            storage = app.booksDir.storageSummary(),
        )
    }

    private suspend fun reconcileLocalCache(snapshot: LibrarySnapshot) = withContext(Dispatchers.IO) {
        val pending = pendingUploads()
        snapshot.folders.forEach { folder -> File(app.booksDir, folder).mkdirs() }
        val watchIds = snapshot.books.map { it.id }.toSet()
        for (book in snapshot.books) {
            val dest = File(app.booksDir, book.id)
            dest.parentFile?.mkdirs()
            val stale = !dest.isFile ||
                (book.sizeBytes > 0L && dest.length() != book.sizeBytes) ||
                (book.modifiedAtMs > 0L && dest.lastModified() != book.modifiedAtMs)
            if (stale && book.id !in pending) {
                val result = repo.downloadBook(book.id, dest, book.sizeBytes)
                if (result is WatchRepository.Result.Ok && book.modifiedAtMs > 0L) {
                    dest.setLastModified(book.modifiedAtMs)
                }
            }
        }
        app.booksDir.walkTopDown()
            .filter { it.isFile && it.name != PENDING_UPLOADS_FILE && !it.name.startsWith(".wbooks-") }
            .forEach { file ->
                val id = file.relativeTo(app.booksDir).invariantSeparatorsPath
                if (id !in watchIds && id !in pending) file.delete()
            }
        localLibrary.refresh()
    }

    private suspend fun pushPendingUploads(snapshot: LibrarySnapshot): Boolean {
        val watchIds = snapshot.books.map { it.id }.toSet()
        val pending = pendingUploads().toMutableSet()
        var pushedAny = false
        for (id in pending.toList()) {
            val file = File(app.booksDir, id)
            if (!file.isFile) {
                pending -= id
                continue
            }
            if (id in watchIds) {
                pending -= id
                continue
            }
            val result = repo.uploadStream(file.inputStream(), file.name, file.length(), overwrite = true)
            if (result is WatchRepository.Result.Ok) {
                pending -= id
                pushedAny = true
            }
        }
        writePendingUploads(pending)
        return pushedAny
    }

    private suspend fun cacheUri(uri: Uri, filename: String, pendingUpload: Boolean): String? =
        withContext(Dispatchers.IO) {
            val safe = safeFilename(filename)
            val dest = uniqueLocalFile(app.booksDir, safe)
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@withContext null
            dest.parentFile?.mkdirs()
            input.use { source -> Files.copy(source, dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            if (pendingUpload) markPendingUpload(dest.name)
            localLibrary.refresh()
            dest.relativeTo(app.booksDir).invariantSeparatorsPath
        }

    private suspend fun cacheBytes(filename: String, bytes: ByteArray, pendingUpload: Boolean): String =
        withContext(Dispatchers.IO) {
            val dest = uniqueLocalFile(app.booksDir, safeFilename(filename))
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            if (pendingUpload) markPendingUpload(dest.name)
            localLibrary.refresh()
            dest.relativeTo(app.booksDir).invariantSeparatorsPath
        }

    private fun safeFilename(filename: String): String =
        filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "book" }

    private fun uniqueLocalFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    /**
     * Peripheral, non-alarming status line for the library header. The phone is a
     * fully usable reader on its own, so a missing watch is never an error — we
     * only mention sync when books are actually waiting to reach the watch.
     */
    private fun pendingSyncBanner(): String? {
        val n = pendingUploads().size
        return when {
            n <= 0 -> null
            n == 1 -> "1 book will sync when your watch is connected"
            else -> "$n books will sync when your watch is connected"
        }
    }

    private fun pendingUploadsFile(): File = File(app.booksDir, PENDING_UPLOADS_FILE)

    private fun pendingUploads(): Set<String> =
        pendingUploadsFile().takeIf { it.isFile }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private fun markPendingUpload(id: String) {
        writePendingUploads(pendingUploads() + id)
    }

    private fun writePendingUploads(ids: Set<String>) {
        val file = pendingUploadsFile()
        file.parentFile?.mkdirs()
        if (ids.isEmpty()) file.delete()
        else file.writeText(ids.sorted().joinToString(separator = "\n", postfix = "\n"))
    }

    private fun File.storageSummary(): StorageSummary {
        val used = if (exists()) walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val root = takeIf { exists() } ?: parentFile ?: this
        return StorageSummary(usedBytes = used, freeBytes = root.usableSpace, totalBytes = root.totalSpace)
    }

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
        private const val PENDING_UPLOADS_FILE = ".wbooks-pending-uploads"
        private val SUPPORTED_INPUT_EXTENSIONS = setOf(
            "epub",
            "txt",
            "fb2",
            "html",
            "htm",
            "xhtml",
            "docx",
            "odt",
            "pdf",
        )

        private fun isSupportedInputFilename(filename: String): Boolean =
            filename.substringAfterLast('.', "").lowercase() in SUPPORTED_INPUT_EXTENSIONS
    }
}
