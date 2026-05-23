package com.fredapp.wbooks.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.fredapp.wbooks.BuildConfig
import com.fredapp.wbooks.data.about.WATCH_ABOUT_SECTIONS
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.focus.pageRotaryScrollOwner
import com.fredapp.wbooks.ui.layout.watchListPadding

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    ClaimRotaryFocusOnActive(active = true, focusRequester = focusRequester)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageRotaryScrollOwner(listState)
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 4.dp, top = 32.dp, end = 4.dp, bottom = 32.dp),
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
            for (section in WATCH_ABOUT_SECTIONS) {
                item { SectionTitle(section.title) }
                item { Body(section.lines.joinToString("\n\n")) }
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
