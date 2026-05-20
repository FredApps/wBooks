package com.fredapp.wbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.fredapp.wbooks.data.settings.ReaderSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.WBooksRoot
import com.fredapp.wbooks.ui.theme.WBooksTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_LIBRARY = "show_library"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WBooksApp
        val isFreshLaunch = savedInstanceState == null
        val showLibrary = intent?.getBooleanExtra(EXTRA_SHOW_LIBRARY, false) == true
        setContent {
            val freshLaunch = remember { isFreshLaunch }
            val vm: ReaderViewModel = viewModel(
                factory = ReaderViewModel.Factory(
                    settingsRepo = app.settingsRepository,
                    libraryRepo = app.libraryRepository,
                    positionsRepo = app.positionsRepository,
                    bookmarksRepo = app.bookmarksRepository,
                    transferController = app.transferController,
                    documentCache = app.documentCache,
                    paceRepo = app.readingPaceRepository,
                    statsRepo = app.readingStatsRepository,
                    appScope = app.appScope,
                ),
            )
            val settings by vm.settings.collectAsState()

            SideEffect {
                window.attributes = window.attributes.apply {
                    screenBrightness = settings.screenBrightness
                        .coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE) / 100f
                }
            }

            LaunchedEffect(Unit) {
                if (freshLaunch && !showLibrary) vm.resumeLastBook()
            }

            WBooksTheme(choice = settings.theme) {
                WBooksRoot(vm = vm)
            }
        }
    }
}
