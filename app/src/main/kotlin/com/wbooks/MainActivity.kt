package com.wbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wbooks.ui.ReaderViewModel
import com.wbooks.ui.WBooksRoot
import com.wbooks.ui.theme.WBooksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WBooksApp
        setContent {
            WBooksTheme {
                val vm: ReaderViewModel = viewModel(
                    factory = ReaderViewModel.Factory(
                        app.settingsRepository,
                        app.libraryRepository,
                        app.positionsRepository,
                        app.bookmarksRepository,
                        app.transferController,
                    ),
                )
                WBooksRoot(vm = vm)
            }
        }
    }
}
