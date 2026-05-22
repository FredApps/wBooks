package com.fredapp.wbooks.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.fredapp.wbooks.R
import com.fredapp.wbooks.WBooksApp
import com.fredapp.wbooks.data.settings.FontChoice
import com.fredapp.wbooks.data.settings.ReaderSettings
import com.fredapp.wbooks.data.settings.ReadingMode
import com.fredapp.wbooks.ui.ReaderViewModel
import com.fredapp.wbooks.ui.focus.ClaimRotaryFocusOnActive
import com.fredapp.wbooks.ui.layout.watchListPadding
import com.fredapp.wbooks.ui.theme.toFontFamily

private val FolderGrey = Color(0xFFB0B0B0)
private val FolderGreyText = Color(0xFF1C1C1C)

@Composable
fun SettingsScreen(vm: ReaderViewModel, isActive: Boolean = true, onBack: () -> Unit) {
    var showChangelog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    if (showChangelog) {
        BackHandler { showChangelog = false }
        ChangelogScreen(onBack = { showChangelog = false })
        return
    }
    if (showAbout) {
        BackHandler { showAbout = false }
        AboutScreen(onBack = { showAbout = false })
        return
    }
    if (showInstructions) {
        BackHandler { showInstructions = false }
        InstructionsScreen(onBack = { showInstructions = false })
        return
    }
    val state = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = state)
    val settings by vm.settings.collectAsState()
    // Hoisted out of the items below: a separate collectAsState per item costs
    // a recomposition scope per call and adds a flow subscription each.
    val transfer by vm.transferState.collectAsState()

    // Swipe-in starts at the top once, then focus is reclaimed after the pager
    // settles. Resume refocus does not re-run the scroll-to-top side effect.
    ClaimRotaryFocusOnActive(
        active = isActive,
        focusRequester = focusRequester,
        onActivated = { state.scrollToItem(0) },
    )

    // Hoisted out of the lazy item so it survives the row scrolling off-screen between
    // the user tapping the toggle and the system permission dialog returning.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether the user grants or denies notification permission, the server
        // still runs â€” without permission the foreground notification just won't
        // be visible. Start regardless.
        vm.startTransfer()
    }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = state,
            contentPadding = watchListPadding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                // CompactChip sits flush at the top of the list (smaller height
                // and width than the full Chip) so the screen doesn't waste a
                // visible band of whitespace above the first real setting.
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CompactChip(
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Back",
                                tint = FolderGreyText,
                            )
                        },
                        label = { Text("Back", color = FolderGreyText) },
                        onClick = onBack,
                        colors = ChipDefaults.chipColors(backgroundColor = FolderGrey, contentColor = FolderGreyText),
                    )
                }
            }
            item {
                Chip(
                    label = { Text("How to use") },
                    onClick = { showInstructions = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleChip(
                    checked = transfer.running,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                vm.startTransfer()
                            }
                        } else {
                            vm.stopTransfer()
                        }
                    },
                    label = {
                        Text(
                            if (transfer.running) stringResource(R.string.settings_transfer_stop)
                            else stringResource(R.string.settings_transfer_start)
                        )
                    },
                    secondaryLabel = {
                        Text(
                            transfer.url ?: stringResource(R.string.settings_transfer_subtitle)
                        )
                    },
                    toggleControl = { Switch(checked = transfer.running) },
                    colors = ToggleChipDefaults.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                if (transfer.running && transfer.pin != null) {
                    Text(
                        text = "PIN ${transfer.pin}",
                        style = MaterialTheme.typography.title3,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item { ListHeader { Text(stringResource(R.string.page_settings)) } }
            item {
                CyclerChip(
                    label = stringResource(R.string.settings_reading_mode),
                    value = settings.mode.name.lowercase().replaceFirstChar { it.titlecase() },
                    onClick = vm::cycleMode,
                )
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_text_size),
                    value = settings.textSizeSp,
                    range = ReaderSettings.TEXT_SIZE_RANGE,
                    onChange = vm::setTextSize,
                )
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_sentence_text_size),
                    value = settings.sentenceTextSizeSp,
                    range = ReaderSettings.SENTENCE_TEXT_SIZE_RANGE,
                    onChange = vm::setSentenceTextSize,
                )
            }
            item {
                ToggleChip(
                    checked = settings.autoscrollEnabled,
                    onCheckedChange = { vm.toggleAutoscroll() },
                    label = { Text(stringResource(R.string.settings_autoscroll)) },
                    toggleControl = { Switch(checked = settings.autoscrollEnabled) },
                    colors = ToggleChipDefaults.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_autoscroll_speed),
                    value = settings.autoscrollSpeed,
                    range = ReaderSettings.AUTOSCROLL_SPEED_RANGE,
                    onChange = vm::setAutoscrollSpeed,
                )
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_speedread_wpm),
                    value = settings.speedreadWpm,
                    range = ReaderSettings.WPM_RANGE,
                    step = 25,
                    onChange = vm::setSpeedreadWpm,
                )
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_screen_brightness),
                    value = settings.screenBrightness,
                    range = ReaderSettings.SCREEN_BRIGHTNESS_RANGE,
                    step = 5,
                    suffix = "%",
                    onChange = vm::setScreenBrightness,
                )
            }
            // Speedread mode emits a steady stream of frames itself, which keeps
            // the display awake without an explicit wakelock — hide the
            // keep-awake slider while it's the active mode so the user isn't
            // tweaking a value that has no effect.
            if (settings.mode != ReadingMode.SPEEDREAD) {
                item {
                    SliderRow(
                        label = stringResource(R.string.settings_keep_awake),
                        value = settings.keepAwakeMinutes,
                        range = ReaderSettings.KEEP_AWAKE_MINUTES_RANGE,
                        step = 1,
                        suffix = " min",
                        onChange = vm::setKeepAwakeMinutes,
                    )
                }
            }

            item { ListHeader { Text(stringResource(R.string.settings_font)) } }
            items(FontChoice.entries.size) { idx ->
                val font = FontChoice.entries[idx]
                ChoiceChip(
                    label = font.familyName,
                    fontFamily = font.toFontFamily(),
                    selected = settings.font == font,
                    onClick = { vm.setFont(font) },
                )
            }

            item { ListHeader { Text(stringResource(R.string.settings_text_color)) } }
            items(ReaderSettings.TEXT_COLOR_PALETTE.size) { idx ->
                val argb = ReaderSettings.TEXT_COLOR_PALETTE[idx]
                ColorChoiceChip(
                    argb = argb,
                    selected = settings.textColorArgb == argb,
                    onClick = { vm.setTextColor(argb) },
                )
            }

            item {
                Chip(
                    label = { Text(stringResource(R.string.settings_changelog)) },
                    onClick = { showChangelog = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.settings_about)) },
                    onClick = { showAbout = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val context = LocalContext.current
                val pref = remember(context) {
                    (context.applicationContext as WBooksApp).crashReportingPref
                }
                val enabled by pref.enabled.collectAsState()
                ToggleChip(
                    checked = enabled,
                    onCheckedChange = { pref.setEnabled(it) },
                    label = { Text(stringResource(R.string.settings_crash_reporting)) },
                    secondaryLabel = { Text(stringResource(R.string.settings_crash_reporting_subtitle)) },
                    toggleControl = { Switch(checked = enabled) },
                    colors = ToggleChipDefaults.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CyclerChip(label: String, value: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label) },
        secondaryLabel = { Text(value) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceChip(label: String, fontFamily: FontFamily? = null, selected: Boolean, onClick: () -> Unit) {
    Chip(
        label = { Text(if (selected) "$label (selected)" else label, fontFamily = fontFamily) },
        onClick = onClick,
        colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColorChoiceChip(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val label = colorName(argb)
    // Deselected: name + hex render in the swatch tint so the row is
    // self-describing against the dark default chip background.
    // Selected: the chip switches to the bright primary background, which
    // would wash out the same swatch-coloured text — flip both labels to
    // black so they stay legible against the highlight.
    val tint = Color(argb)
    val textColor = if (selected) Color.Black else tint
    Chip(
        label = { Text(if (selected) "$label (selected)" else label, color = textColor) },
        secondaryLabel = { Text("#%06X".format(argb and 0xFFFFFF), color = textColor) },
        onClick = onClick,
        colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: $value$suffix",
            style = MaterialTheme.typography.caption2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        InlineSlider(
            value = value,
            onValueChange = onChange,
            valueProgression = range step step,
            decreaseIcon = { Icon(InlineSliderDefaults.Decrease, contentDescription = "decrease") },
            increaseIcon = { Icon(InlineSliderDefaults.Increase, contentDescription = "increase") },
        )
    }
}

@Composable
private fun StaticChip(label: String) {
    Chip(
        label = { Text(label) },
        onClick = { /* TODO open detail */ },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun colorName(argb: Int): String = when (argb) {
    0xFFD4C19C.toInt() -> "Sepia"
    0xFFFFFFFF.toInt() -> "Cold white"
    0xFFB0B0B0.toInt() -> "Grey"
    0xFFE8E6E1.toInt() -> "Warm white"
    0xFF9CB5D4.toInt() -> "Pale blue"
    0xFFA8D49C.toInt() -> "Pale green"
    0xFFD49C9C.toInt() -> "Pale red"
    else -> "Custom"
}
