package com.wbooks.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.BuildConfig

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text("wBooks") } }
            item {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Body(
                    "An Android Wear ebook reader. Reads epub, txt, fb2, html, docx, and odt.",
                )
            }
            item { SectionTitle("Seed books") }
            item {
                Body(
                    "Moby Dick · Pride and Prejudice · The Adventures of Sherlock Holmes · " +
                        "The Yellow Wallpaper · The Strange Case of Dr Jekyll and Mr Hyde · " +
                        "The Time Machine — bundled from Project Gutenberg public-domain editions.",
                )
            }
            item { SectionTitle("Built with") }
            item { Body("Kotlin · Jetpack Compose for Wear OS · Jsoup · NanoHTTPD") }
            item { SectionTitle("License") }
            item {
                Body(
                    "Source: github.com/FredApps/wBooks. The bundled Gutenberg texts are in " +
                        "the public domain in the United States; check your jurisdiction.",
                )
            }
            item {
                Chip(
                    label = { Text("Back to settings") },
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
    )
}
