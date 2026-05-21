package com.fredapp.wbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.fredapp.wbooks.data.settings.ReaderSettings
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.WBooksRoot
import com.fredapp.wbooks.ui.theme.WBooksTheme
import com.fredapp.wbooks.ui.theme.toFontFamily
import androidx.wear.compose.material.LocalContentColor
import androidx.wear.compose.material.ProvideTextStyle

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

            val contentColor = Color(settings.textColorArgb)
            WBooksTheme(choice = settings.theme, textColor = contentColor) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    ProvideTextStyle(TextStyle(color = contentColor, fontFamily = settings.font.toFontFamily())) {
                        WBooksRoot(vm = vm)
                    }
                }
            }
        }
    }
}
