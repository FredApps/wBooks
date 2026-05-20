package com.fredapps.watchdevtools

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

private const val RESOURCES_VERSION = "1"

/**
 * Tile that opens Developer options in one tap. The tile itself can't `startActivity`
 * directly (it runs in a sandboxed renderer); the CompactChip declares a
 * [ActionBuilders.LaunchAction] which the system uses to launch
 * [LaunchDevOptionsActivity], which then fires the settings intent and finishes.
 *
 * IMPORTANT: PrimaryLayout must be the root of the layout tree. Wrapping it in a
 * Box (e.g. to make the whole tile tappable) breaks rendering — the tile shows
 * up all-black. The chip is the only tap target, which is the standard tile UX.
 */
class DevOptionsTileService : TileService() {

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        ResolvableFuture.create<ResourceBuilders.Resources>().apply {
            set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .build()
            )
        }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<Tile> {
        val deviceParams = requestParams.deviceConfiguration

        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("com.fredapps.watchdevtools.LaunchDevOptionsActivity")
                    .build()
            )
            .build()

        val chipClickable = ModifiersBuilders.Clickable.Builder()
            .setId("open_dev_options")
            .setOnClick(launchAction)
            .build()

        val titleText = Text.Builder(this, "Wireless")
            .setTypography(Typography.TYPOGRAPHY_TITLE2)
            .setColor(argb(0xFFFFFFFF.toInt()))
            .build()

        val subtitleText = Text.Builder(this, "debug")
            .setTypography(Typography.TYPOGRAPHY_TITLE2)
            .setColor(argb(0xFFFFFFFF.toInt()))
            .build()

        val centerColumn = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(titleText)
            .addContent(subtitleText)
            .build()

        val chip = CompactChip.Builder(this, "Open", chipClickable, deviceParams)
            .setChipColors(ChipColors.primaryChipColors(Colors.DEFAULT))
            .build()

        val layout = PrimaryLayout.Builder(deviceParams)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Dev tools")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFFAAAAAA.toInt()))
                    .build()
            )
            .setContent(centerColumn)
            .setPrimaryChipContent(chip)
            .build()

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return ResolvableFuture.create<Tile>().apply { set(tile) }
    }
}
