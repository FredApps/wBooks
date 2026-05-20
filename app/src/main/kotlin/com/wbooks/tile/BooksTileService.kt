package com.wbooks.tile

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
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
import com.wbooks.R
import com.wbooks.WBooksApp
import com.wbooks.data.stats.formatDurationMs
import kotlinx.coroutines.runBlocking

/**
 * Combined "reading today + resume" tile. Shows today's accumulated reading time as the
 * primary label and the last-opened book title as the main content. The Resume chip
 * launches directly into the last book; tapping anywhere else on the tile opens the
 * library listing.
 */
class BooksTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val app = applicationContext as WBooksApp

        val lastId = runBlocking { app.positionsRepository.readLastOpenedBookId() }
        val book = lastId?.let { id ->
            runBlocking {
                app.libraryRepository.refresh()
                app.libraryRepository.books.value.firstOrNull { it.id == id }
            }
        }
        val summary = runBlocking { app.readingStatsRepository.snapshot() }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(5 * 60_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    layout(
                        bookTitle = book?.title ?: getString(R.string.tile_no_book),
                        hasBook = book != null,
                        todayMs = summary.todayMs,
                        device = requestParams.deviceConfiguration,
                    )
                )
            )
            .build()
        return resolved(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        resolved(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    BOOK_BG_RES_ID,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.tile_book_bg)
                                .build()
                        )
                        .build()
                )
                .build()
        )

    private fun layout(
        bookTitle: String,
        hasBook: Boolean,
        todayMs: Long,
        device: DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val resumeClickable = ModifiersBuilders.Clickable.Builder()
            .setId("resume")
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

        val libraryClickable = ModifiersBuilders.Clickable.Builder()
            .setId("library")
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

        val timeLabel = formatDurationMs(todayMs).let { if (todayMs > 0) "$it today" else "No reading yet" }
        val chipLabel = if (hasBook) getString(R.string.tile_resume) else getString(R.string.tile_open)

        val foreground = PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(
                Text.Builder(this, timeLabel)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(Colors.DEFAULT.onSurface))
                    .build()
            )
            .setContent(
                Text.Builder(this, bookTitle)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setMaxLines(3)
                    .build()
            )
            .setPrimaryChipContent(
                Chip.Builder(this, resumeClickable, device)
                    .setPrimaryLabelContent(chipLabel)
                    .setChipColors(ChipColors.primaryChipColors(Colors.DEFAULT))
                    .build()
            )
            .build()

        val background = LayoutElementBuilders.Image.Builder()
            .setResourceId(BOOK_BG_RES_ID)
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(libraryClickable)
                    .build()
            )
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .addContent(background)
            .addContent(foreground)
            .build()
    }

    private fun <T> resolved(value: T): ListenableFuture<T> =
        ResolvableFuture.create<T>().also { it.set(value) }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val BOOK_BG_RES_ID = "book_bg"
    }
}
