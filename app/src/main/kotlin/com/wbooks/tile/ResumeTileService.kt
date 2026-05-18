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
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wbooks.R
import com.wbooks.WBooksApp
import kotlinx.coroutines.runBlocking

/**
 * "Resume reading" Tile. Shows the title of the last-opened book and a Resume chip;
 * tapping the chip launches MainActivity, which auto-opens the last book.
 *
 * onTileRequest runs on a Binder thread (not the main thread), so the small amount
 * of work we do — reading a DataStore key + refreshing the library — is fine to
 * block on. If we ever need to do real I/O here we should switch to suspend +
 * CallbackToFutureAdapter.
 */
class ResumeTileService : TileService() {

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

        val title = book?.title ?: getString(R.string.tile_no_book)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    layout(title, book != null, requestParams.deviceConfiguration)
                )
            )
            .build()
        return resolved(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        resolved(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun layout(
        title: String,
        hasBook: Boolean,
        device: DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
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

        val chipText = if (hasBook) getString(R.string.tile_resume) else "Open"

        return PrimaryLayout.Builder(device)
            .setContent(
                Text.Builder(this, title)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setMaxLines(3)
                    .build()
            )
            .setPrimaryChipContent(
                Chip.Builder(this, clickable, device)
                    .setPrimaryLabelContent(chipText)
                    .setChipColors(ChipColors.primaryChipColors(androidx.wear.protolayout.material.Colors.DEFAULT))
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
