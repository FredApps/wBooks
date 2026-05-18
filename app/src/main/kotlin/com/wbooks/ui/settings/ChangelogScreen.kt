package com.wbooks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.data.changelog.CHANGELOG
import com.wbooks.data.changelog.ChangelogEntry

@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text("Changelog") } }
            item {
                Chip(
                    label = { Text("Back to settings") },
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            for (entry in CHANGELOG) {
                item(key = "v-${entry.version}") { VersionHeader(entry) }
                items(entry.notes, key = { "n-${entry.version}-${it.hashCode()}" }) { note ->
                    Text(
                        text = "• $note",
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
        text = "${entry.version} · ${entry.date}",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
