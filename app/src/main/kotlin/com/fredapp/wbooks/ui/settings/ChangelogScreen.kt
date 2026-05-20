package com.fredapp.wbooks.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
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
import com.fredapp.wbooks.data.changelog.CHANGELOG
import com.fredapp.wbooks.data.changelog.ChangelogEntry

@Composable
fun ChangelogScreen(onBack: () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text("Changelog") } }
            item {
                Chip(
                    label = { Text("Back to settings") },
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            for (entry in CHANGELOG) {
                item(key = "v-${entry.version}") { VersionHeader(entry) }
                items(entry.notes, key = { "n-${entry.version}-${it.hashCode()}" }) { note ->
                    Text(
                        text = "â€¢ $note",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.caption1,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionHeader(entry: ChangelogEntry) {
    Text(
        text = "${entry.version} Â· ${entry.date}",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
