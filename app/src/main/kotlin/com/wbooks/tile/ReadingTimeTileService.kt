package com.wbooks.tile

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wbooks.WBooksApp
import kotlinx.coroutines.runBlocking

/**
 * "Reading today" Tile. Shows today's accumulated reading minutes and a chip that
 * resumes the last book. Sibling of [ResumeTileService] — both are listed in the
 * manifest so the user can place whichever they prefer (or both).
 *
 * onTileRequest runs on a Binder thread, so the DataStore snapshot via runBlocking
 * is acceptable: it's a single Preferences read, in the millisecond range.
 */
class ReadingTimeTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val app = applicationContext as WBooksApp
        val summary = runBlocking { app.readingStatsRepository.snapshot() }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // 5-minute freshness window matches the rate at which the minute
            // count can meaningfully change.
            .setFreshnessIntervalMillis(5 * 60_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    layout(summary.todayMs, requestParams.deviceConfiguration)
                )
            )
            .build()
        return resolved(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        resolved(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun layout(todayMs: Long, device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName("com.wbooks.MainActivity")
                            .build()
                    )
                    .build()
            )
            .build()

        val totalMinutes = (todayMs / 60_000L).coerceAtLeast(0)
        val display = if (totalMinutes < 60) "${totalMinutes}m"
        else "${totalMinutes / 60}h ${totalMinutes % 60}m"

        return PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Today")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(Colors.DEFAULT.onSurface))
                    .build()
            )
            .setContent(
                Text.Builder(this, display)
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                    .setMaxLines(1)
                    .build()
            )
            .setPrimaryChipContent(
                Chip.Builder(this, clickable, device)
                    .setPrimaryLabelContent("Resume")
                    .setChipColors(ChipColors.primaryChipColors(Colors.DEFAULT))
                    .build()
            )
            .build()
    }

    private fun <T> resolved(value: T): ListenableFuture<T> =
        ResolvableFuture.create<T>().also { it.set(value) }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
