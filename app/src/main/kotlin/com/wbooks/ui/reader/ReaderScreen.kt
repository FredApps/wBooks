package com.wbooks.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.wbooks.data.settings.ReadingMode
import com.wbooks.ui.DocumentState
import com.wbooks.ui.ReaderViewModel

/**
 * Renders whichever variant ([NormalMode], [SpeedReadMode], [SentenceMode]) the
 * current settings select. Empty / loading / failed are handled here so the modes
 * can assume they always get a real Document.
 */
@Composable
fun ReaderScreen(
    state: DocumentState,
    vm: ReaderViewModel,
    isActive: Boolean,
    onExit: () -> Unit,
) {
    val settings by vm.settings.collectAsState()

    // Count reading time while the Reader page is the active pager page and we
    // have a loaded book. Pause when the user swipes to Tools / Settings or
    // navigates away; resume on next entry.
    val activeWithBook = isActive && state is DocumentState.Loaded
    DisposableEffect(activeWithBook) {
        if (activeWithBook) vm.startReadingSession()
        onDispose { vm.endReadingSession() }
    }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            DocumentState.Idle -> Unit // never rendered: root routes Idle to LibraryScreen
            is DocumentState.Loading -> Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Opening ${state.book.title}",
                        textAlign = TextAlign.Center,
                    )
                    if (state.isFirstOpen) {
                        Text(
                            text = "First open may take a moment",
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            is DocumentState.Failed -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Failed to open ${state.book.title}: ${state.message}")
            }
            is DocumentState.Loaded -> when (settings.mode) {
                ReadingMode.NORMAL -> NormalMode(
                    document = state.doc,
                    initialPosition = state.initialPosition,
                    settings = settings,
                    vm = vm,
                    isActive = isActive,
                )
                ReadingMode.SPEEDREAD -> SpeedReadMode(
                    document = state.doc,
                    settings = settings,
                    isActive = isActive,
                    onWpmChange = vm::setSpeedreadWpm,
                )
                ReadingMode.SENTENCE -> SentenceMode(
                    document = state.doc,
                    initialPosition = state.initialPosition,
                    settings = settings,
                    vm = vm,
                    isActive = isActive,
                )
            }
        }
    }
}
