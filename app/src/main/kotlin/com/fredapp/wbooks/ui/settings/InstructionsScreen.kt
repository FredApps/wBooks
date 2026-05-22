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
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.layout.watchListPadding

@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)
    ClaimRotaryFocusOnActive(active = true, focusRequester = focusRequester)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = watchListPadding(start = 4.dp, top = 32.dp, end = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text("How to use") } }

            item { SectionTitle("Opening a book") }
            item {
                Body(
                    "Swipe right to Search. Tap a book to open it. Swipe left to return to the library.",
                )
            }

            item { SectionTitle("Reading modes") }
            item {
                Body(
                    "Normal: Tap to page through. Swipe up/down for finer control.\n\n" +
                        "Speed Reading: Tap to advance word by word. Adjust WPM in settings.\n\n" +
                        "Sentence: One sentence at a time. Instant navigation.",
                )
            }

            item { SectionTitle("Navigation") }
            item {
                Body(
                    "Swipe left while reading to open Tools page. Tap a chapter heading to jump instantly.\n\n" +
                        "Check reading time and see chapter progress at the top.",
                )
            }

            item { SectionTitle("Bookmarks") }
            item {
                Body(
                    "Tap the bookmark icon (top right) while reading to save your position. View all bookmarks on the Tools page.",
                )
            }

            item { SectionTitle("Settings") }
            item {
                Body(
                    "Swipe left from library to Settings. Adjust text size, color, reading mode, and autoscroll speed.\n\n" +
                        "Enable \"Keep awake\" to prevent the screen from dimming.",
                )
            }

            item { SectionTitle("File transfer") }
            item {
                Body(
                    "Open Settings, toggle \"File transfer\" on. Use the phone companion app or visit the web address shown (from any browser on your network) to upload books.\n\n" +
                        "PDF files are converted to HTML automatically.",
                )
            }

            item { SectionTitle("Folders") }
            item {
                Body(
                    "Create folders in Settings to organize books. Use the companion app or web UI to drag books between folders.",
                )
            }

            item { SectionTitle("Touch-first design") }
            item {
                Body(
                    "Every feature works with touch. If your watch has a rotary bezel or crown, use it to scroll—it's optional and just speeds up navigation.",
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
