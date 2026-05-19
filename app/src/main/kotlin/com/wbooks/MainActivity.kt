package com.wbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.wbooks.data.settings.ReaderSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wbooks.ui.ReaderViewModel
import com.wbooks.ui.WBooksRoot
import com.wbooks.ui.theme.WBooksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WBooksApp
        val isFreshLaunch = savedInstanceState == null
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
                if (freshLaunch) vm.resumeLastBook()
            }

            WBooksTheme(choice = settings.theme) {
                WBooksRoot(vm = vm)
            }
        }
    }
}
