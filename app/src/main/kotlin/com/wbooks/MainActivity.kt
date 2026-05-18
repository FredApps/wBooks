package com.wbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wbooks.ui.WBooksRoot
import com.wbooks.ui.theme.WBooksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WBooksTheme {
                WBooksRoot()
            }
        }
    }
}
