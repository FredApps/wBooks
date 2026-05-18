package com.wbooks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wbooks.R

/**
 * Settings page (pager page 2). Populated as stub chips; each will become its own
 * detail screen (font picker, color picker, sliders) once wired to DataStore.
 */
@Composable
fun SettingsScreen() {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text(stringResource(R.string.page_settings)) } }
            item { settingChip(stringResource(R.string.settings_reading_mode)) }
            item { settingChip(stringResource(R.string.settings_font)) }
            item { settingChip(stringResource(R.string.settings_text_size)) }
            item { settingChip(stringResource(R.string.settings_text_color)) }
            item { settingChip(stringResource(R.string.settings_autoscroll)) }
            item { settingChip(stringResource(R.string.settings_autoscroll_speed)) }
            item { settingChip(stringResource(R.string.settings_speedread_wpm)) }
            item { settingChip(stringResource(R.string.settings_sentence_text_size)) }
            item { settingChip(stringResource(R.string.settings_transfer)) }
            item { settingChip(stringResource(R.string.settings_changelog)) }
            item { settingChip(stringResource(R.string.settings_about)) }
        }
    }
}

@Composable
private fun settingChip(label: String) {
    Chip(
        label = { Text(label) },
        onClick = { /* TODO open detail */ },
        colors = ChipDefaults.secondaryChipColors(),
    )
}
