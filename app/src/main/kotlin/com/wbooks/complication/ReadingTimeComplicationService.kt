package com.wbooks.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wbooks.R
import com.wbooks.WBooksApp

/**
 * Watch-face complication that surfaces today's reading minutes. Supports
 * SHORT_TEXT ("12m" + "Read" label) and RANGED_VALUE (12 / 30 min default
 * daily goal — useful on watch faces with arc / progress complications).
 *
 * Tapping the complication launches MainActivity, which auto-resumes the
 * last opened book. UPDATE_PERIOD_SECONDS in the manifest controls the
 * system poll rate (5 minutes is plenty for a minute counter).
 */
class ReadingTimeComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> buildShortText(15)
        ComplicationType.RANGED_VALUE -> buildRangedValue(15)
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = applicationContext as WBooksApp
        val todayMs = app.readingStatsRepository.snapshot().todayMs
        val totalMinutes = (todayMs / 60_000L).toInt().coerceAtLeast(0)
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> buildShortText(totalMinutes)
            ComplicationType.RANGED_VALUE -> buildRangedValue(totalMinutes)
            else -> null
        }
    }

    private fun buildShortText(minutes: Int): ShortTextComplicationData {
        val display = if (minutes < 60) "${minutes}m" else "${minutes / 60}h${(minutes % 60).takeIf { it > 0 }?.let { "${it}m" } ?: ""}"
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(display).build(),
            contentDescription = PlainComplicationText.Builder(
                "$minutes minutes read today",
            ).build(),
        )
            .setMonochromaticImage(launcherIcon())
            .setTapAction(openAppIntent())
            .build()
    }

    private fun buildRangedValue(minutes: Int): RangedValueComplicationData {
        val goal = DAILY_GOAL_MINUTES.toFloat()
        return RangedValueComplicationData.Builder(
            value = minutes.toFloat().coerceIn(0f, goal),
            min = 0f,
            max = goal,
            contentDescription = PlainComplicationText.Builder(
                "$minutes of ${DAILY_GOAL_MINUTES} minutes read today",
            ).build(),
        )
            .setText(PlainComplicationText.Builder("${minutes}m").build())
            .setMonochromaticImage(launcherIcon())
            .setTapAction(openAppIntent())
            .build()
    }

    private fun launcherIcon(): MonochromaticImage =
        MonochromaticImage.Builder(
            image = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
        ).build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent().apply {
            component = ComponentName(packageName, "com.wbooks.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val DAILY_GOAL_MINUTES = 30
    }
}
