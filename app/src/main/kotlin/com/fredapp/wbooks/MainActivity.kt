package com.fredapp.wbooks

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_LIBRARY = "show_library"
    }

    /** Pings the VM's keep-awake timer when the user touches the screen, spins
     *  the bezel, or presses a key. Composition wires this to vm.noteInteraction(). */
    private val userInteractions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onUserInteraction() {
        super.onUserInteraction()
        userInteractions.tryEmit(Unit)
    }

    /**
     * Bezel rotation arrives as a generic motion event, NOT a touch — so
     * [onUserInteraction] is never called for it. Without this override the
     * user can spin the bezel for the full keep-awake timeout and still get
     * booted to the launcher as if they were idle.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        userInteractions.tryEmit(Unit)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // Treat returning to the foreground as an interaction so the keep-awake
        // timer restarts from a fresh baseline, not from a stale lastInteractionAt
        // that could fire moveTaskToBack immediately after relaunch.
        userInteractions.tryEmit(Unit)
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
            val keepAwakeActive by vm.keepAwakeActive.collectAsState()

            // Brightness is a window attribute, not a Compose state. Pushing it
            // from a SideEffect would run after EVERY recomposition, allocating
            // a new LayoutParams each frame; gate on the actual value so it only
            // runs when the user moves the slider.
            val brightnessFraction = settings.screenBrightness
                .coerceIn(ReaderSettings.SCREEN_BRIGHTNESS_RANGE) / 100f
            LaunchedEffect(brightnessFraction) {
                window.attributes = window.attributes.apply {
                    screenBrightness = brightnessFraction
                }
            }

            // Bridge Activity-level interaction signals into the VM so the
            // timer resets on every touch/key event, not just on block advances.
            LaunchedEffect(Unit) {
                userInteractions.collect { vm.noteInteraction() }
            }

            // Hold the screen awake while keepAwakeActive is true; clear when
            // it flips. SPEEDREAD keeps it permanently high; the other two
            // modes release on the keep-awake timeout.
            DisposableEffect(keepAwakeActive) {
                if (keepAwakeActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            // moveTaskToBack only fires on a genuine true→false transition,
            // not on the initial composition. Skipping the first emission
            // prevents a stale lastInteractionAt (e.g. after a config change
            // recreates the activity) from booting the user out on launch —
            // onResume's interaction ping has a chance to bump the timer
            // before we react.
            LaunchedEffect(Unit) {
                var previouslyActive: Boolean? = null
                vm.keepAwakeActive.collect { active ->
                    if (previouslyActive == true && !active) moveTaskToBack(true)
                    previouslyActive = active
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
