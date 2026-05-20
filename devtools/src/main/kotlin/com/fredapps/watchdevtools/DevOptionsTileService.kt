package com.fredapps.watchdevtools

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
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

private const val RESOURCES_VERSION = "1"

/**
 * Tile that opens Developer options in one tap. The tile itself can't `startActivity`
 * directly (it runs in a sandboxed renderer); it declares a [ActionBuilders.LaunchAction]
 * that the system uses to launch [LaunchDevOptionsActivity], which then fires the
 * settings intent and finishes.
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

        // Wrap the whole layout in a Clickable so tapping anywhere on the tile
        // launches the activity, not just the chip.
        val tileClickable = ModifiersBuilders.Clickable.Builder()
            .setId("tile_root")
            .setOnClick(launchAction)
            .build()

        val chipClickable = ModifiersBuilders.Clickable.Builder()
            .setId("tile_chip")
            .setOnClick(launchAction)
            .build()

        val layout = PrimaryLayout.Builder(deviceParams)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Dev tools")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFFAAAAAA.toInt()))
                    .build()
            )
            .setContent(
                Text.Builder(this, "Wireless\ndebug")
                    .setTypography(Typography.TYPOGRAPHY_TITLE2)
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .setMaxLines(2)
                    .build()
            )
            .setPrimaryChipContent(
                CompactChip.Builder(this, "Open", chipClickable, deviceParams)
                    .setChipColors(ChipColors.primaryChipColors(Colors.DEFAULT))
                    .build()
            )
            .build()

        // Make the whole tile tappable by wrapping it in a Box with a clickable
        // modifier. PrimaryLayout itself doesn't expose a click modifier.
        val tappableRoot = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(tileClickable)
                    .build()
            )
            .addContent(layout)
            .build()

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(tappableRoot)
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
