package com.fredapp.wbooksutil

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(vm) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.stats == null -> CenteredProgress()
                state.noWatch -> WatchReconnectPrompt(
                    text = "No watch connected.",
                    onReconnect = vm::refresh,
                )
                state.stats == null -> CenteredText("No stats yet.")
                else -> StatsContent(state.stats!!)
            }
        }
    }

    state.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
        )
    }
}

@Composable
private fun StatsContent(s: StatsSummary) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard("Today", formatDuration(s.todayMs), Modifier.weight(1f))
            StatCard("Total", formatDuration(s.totalMs), Modifier.weight(1f))
            StatCard("Finished", "${s.booksFinished}", Modifier.weight(1f))
        }
        if (s.daily.any { it.ms > 0 }) {
            SectionHeader("Last ${s.daily.size} days")
            DailyChart(s.daily)
        }
        if (s.wpm.isNotEmpty()) {
            SectionHeader("WPM trend")
            WpmChart(s.wpm)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
}

/** Minimal bar chart for daily reading time. Pure Compose Canvas; no chart library. */
@Composable
private fun DailyChart(daily: List<StatsSummary.DailyEntry>) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val peakMs = (daily.maxOfOrNull { it.ms } ?: 1L).coerceAtLeast(1L)
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (daily.size - 1)) / daily.size
        val baseline = size.height - 1f
        for ((i, d) in daily.withIndex()) {
            val h = (d.ms.toFloat() / peakMs) * (size.height - 4f)
            val x = i * (barW + gap)
            drawRect(
                color = barColor,
                topLeft = Offset(x, baseline - h),
                size = Size(barW, h),
            )
        }
        drawLine(
            color = axisColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1f,
        )
    }
    val peakMin = peakMs / 60_000L
    Text("peak: ${peakMin}m", style = MaterialTheme.typography.labelSmall)
}

/** Lightweight line chart for WPM samples over time. */
@Composable
private fun WpmChart(samples: List<StatsSummary.WpmSample>) {
    val lineColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outline
    val min = samples.minOf { it.wpm }.toFloat()
    val max = samples.maxOf { it.wpm }.toFloat()
    val span = (max - min).coerceAtLeast(1f)
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val baseline = size.height - 1f
        drawLine(
            color = axisColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1f,
        )
        if (samples.size < 2) return@Canvas
        val stepX = size.width / (samples.size - 1)
        var prev: Offset? = null
        for ((i, s) in samples.withIndex()) {
            val nx = i * stepX
            val ny = baseline - ((s.wpm - min) / span) * (size.height - 4f)
            val curr = Offset(nx, ny)
            prev?.let { drawLine(lineColor, it, curr, strokeWidth = 2f) }
            prev = curr
        }
    }
    Text(
        "${samples.size} samples, ${samples.minOf { it.wpm }}-${samples.maxOf { it.wpm }} wpm",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours <= 0 -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}
