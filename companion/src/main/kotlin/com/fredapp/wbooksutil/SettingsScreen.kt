package com.fredapp.wbooksutil

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    // Per the watch-authoritative model, every entry into this screen pulls a
    // fresh snapshot — UI is never edited against stale data. While the screen
    // is on stage we also poll every 5 s so background changes on the watch
    // surface here without a manual refresh.
    LaunchedEffect(Unit) { vm.refresh() }
    DisposableEffect(Unit) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is SettingsViewModel.SyncState.Idle,
                SettingsViewModel.SyncState.Loading -> CenteredProgress()
                SettingsViewModel.SyncState.NoWatch -> CenteredText(
                    "No watch connected. Connect your watch to view and edit settings.",
                )
                is SettingsViewModel.SyncState.Error -> CenteredText("Couldn't reach watch: ${s.message}")
                is SettingsViewModel.SyncState.Refreshing -> Column(Modifier.fillMaxSize()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    // Controls are disabled during the refresh â€” if the user managed to
                    // edit between the LaunchedEffect firing and the fetch completing,
                    // the SET would race the GET and the wrong snapshot could land last.
                    SettingsAppearance(s.stale) {
                        SettingsList(snapshot = s.stale, enabled = false, vm = vm)
                    }
                }
                is SettingsViewModel.SyncState.Synced -> SettingsAppearance(s.snapshot) {
                    SettingsList(
                        snapshot = s.snapshot,
                        enabled = !saving,
                        vm = vm,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAppearance(snapshot: SettingsSnapshot, content: @Composable () -> Unit) {
    val color = Color(snapshot.textColorArgb)
    CompositionLocalProvider(LocalContentColor provides color) {
        androidx.compose.material3.ProvideTextStyle(
            MaterialTheme.typography.bodyLarge.copy(color = color, fontFamily = snapshot.fontChoice().toFontFamily()),
            content = content,
        )
    }
}

@Composable
private fun SettingsList(
    snapshot: SettingsSnapshot,
    enabled: Boolean,
    vm: SettingsViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Reading mode")
        EnumPicker(
            options = ReadingMode.entries,
            selected = runCatching { ReadingMode.valueOf(snapshot.mode) }.getOrDefault(ReadingMode.NORMAL),
            labelFor = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } },
            enabled = enabled,
            onSelect = vm::setMode,
        )

        SectionHeader("Font")
        EnumPicker(
            options = FontChoice.entries,
            selected = snapshot.fontChoice(),
            labelFor = { it.familyName },
            fontFor = { it.toFontFamily() },
            enabled = enabled,
            onSelect = vm::setFont,
        )

        SectionHeader("Text size")
        SliderRow(
            value = snapshot.textSizeSp,
            range = SettingsRanges.TEXT_SIZE,
            enabled = enabled,
            onCommit = vm::setTextSize,
        )

        SectionHeader("Sentence-mode text size")
        SliderRow(
            value = snapshot.sentenceTextSizeSp,
            range = SettingsRanges.SENTENCE_TEXT_SIZE,
            enabled = enabled,
            onCommit = vm::setSentenceTextSize,
        )

        SectionHeader("Text color")
        ColorPalette(
            selected = snapshot.textColorArgb,
            enabled = enabled,
            onSelect = vm::setTextColor,
        )

        SectionHeader("Autoscroll")
        ToggleRow(
            label = "Enabled",
            checked = snapshot.autoscrollEnabled,
            enabled = enabled,
            onChange = vm::setAutoscrollEnabled,
        )
        SliderRow(
            label = "Speed",
            value = snapshot.autoscrollSpeed,
            range = SettingsRanges.AUTOSCROLL_SPEED,
            enabled = enabled && snapshot.autoscrollEnabled,
            onCommit = vm::setAutoscrollSpeed,
        )

        SectionHeader("Speed-read WPM")
        SliderRow(
            value = snapshot.speedreadWpm,
            range = SettingsRanges.WPM,
            step = 25,
            enabled = enabled,
            onCommit = vm::setSpeedreadWpm,
        )

        SectionHeader("Screen brightness")
        SliderRow(
            value = snapshot.screenBrightness,
            range = SettingsRanges.SCREEN_BRIGHTNESS,
            step = 5,
            suffix = "%",
            enabled = enabled,
            onCommit = vm::setScreenBrightness,
        )

        SectionHeader("Keep awake")
        SliderRow(
            value = snapshot.keepAwakeMinutes,
            range = SettingsRanges.KEEP_AWAKE_MINUTES,
            step = 1,
            suffix = " min",
            enabled = enabled,
            onCommit = vm::setKeepAwakeMinutes,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("Privacy")
        ToggleRow(
            label = "Crash reports",
            secondary = "Send anonymous crash data from watch + phone",
            checked = snapshot.crashReportingEnabled,
            enabled = enabled,
            onChange = vm::setCrashReportingEnabled,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("How to use")
        InstructionsBlock()

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("Changelog")
        ChangelogBlock()

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("About")
        AboutBlock()
    }
}

@Composable
private fun ChangelogBlock() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        for (entry in CompanionChangelog.ENTRIES) {
            Text(
                text = "${entry.version} — ${entry.date}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            for (note in entry.notes) {
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun InstructionsBlock() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "Sending books to the watch",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "Tap the \"Add\" button to pick files from your phone. Supported formats: EPUB, DOCX, ODT, TXT, HTML, FB2, and PDF.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "Organizing with folders",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "Tap the folder icon to create folders. Drag book titles onto folders to organize them. The watch syncs these changes automatically.\n\n" +
                "Folders are top-level only. You can have up to ${FolderPolicy.MAX_FOLDERS} folders, and each folder name can be up to ${FolderPolicy.MAX_NAME_LENGTH} characters. Names cannot contain path or reserved filesystem characters.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "PDF support",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "When you upload a PDF, this app automatically converts it to HTML for the watch. The PDF appears in your library marked [PDF].",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "Browsing Project Gutenberg",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "Tap the Project Gutenberg icon (top) to search and download public-domain books directly to the watch.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "Reading stats",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "Tap the calendar icon (top) to view reading statistics: daily totals, 30-day trends, and books finished. Data is synced from the watch.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "Watch settings",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "Tap the settings icon (top) to adjust font, text size, color, and reading modes on the watch without picking up your watch.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )

        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "The app syncs with your watch automatically once paired. Make sure both devices are on the same Wi-Fi network or connected via Bluetooth.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
    }
}

@Composable
private fun AboutBlock() {
    val versionName = androidx.compose.ui.platform.LocalContext.current.let {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "wBooks companion — version $versionName",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Phone-side helper for the wBooks Wear OS reader. Sends books to the watch over the Wear Data Layer, browses Project Gutenberg, and edits watch settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Source: github.com/FredApps/wBooks — GPLv3.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T : Enum<T>> EnumPicker(
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    fontFor: (T) -> FontFamily? = { null },
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { opt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = opt == selected,
                    onClick = { if (enabled && opt != selected) onSelect(opt) },
                    enabled = enabled,
                )
                Spacer(Modifier.size(4.dp))
                Text(labelFor(opt), fontFamily = fontFor(opt))
            }
        }
    }
}

private fun SettingsSnapshot.fontChoice(): FontChoice =
    runCatching { FontChoice.valueOf(font) }.getOrDefault(FontChoice.SERIF)

private fun FontChoice.toFontFamily(): FontFamily = when (this) {
    FontChoice.DEFAULT -> FontFamily.Default
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.CURSIVE -> FontFamily.Cursive
}

@Composable
private fun SliderRow(
    label: String? = null,
    value: Int,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = (label?.let { "$it: " } ?: "") + "$value$suffix",
            style = MaterialTheme.typography.bodyMedium,
        )
        // The slider produces a stream of values as the user drags. We only
        // call into the watch on release (onValueChangeFinished) so we don't
        // saturate the Wear transport with intermediate values.
        var pending by remember(value) { mutableStateOf(value.toFloat()) }
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = {
                val snapped = snap(pending.toInt(), range, step)
                if (snapped != value) onCommit(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step) - 1,
            enabled = enabled,
        )
    }
}

private fun snap(raw: Int, range: IntRange, step: Int): Int {
    val clamped = raw.coerceIn(range)
    val offset = clamped - range.first
    val snapped = range.first + (offset / step) * step
    return snapped.coerceIn(range)
}

@Composable
private fun ColorPalette(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsRanges.TEXT_COLOR_PALETTE.forEach { argb ->
            val isSelected = argb == selected
            val size by animateDpAsState(
                targetValue = if (isSelected) 36.dp else 28.dp,
                label = "colorSwatchSize",
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(argb), CircleShape)
                    .clickable(enabled = enabled && !isSelected) { onSelect(argb) },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    secondary: String? = null,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (secondary != null) {
                Text(secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
            enabled = enabled,
        )
    }
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text) }
}
