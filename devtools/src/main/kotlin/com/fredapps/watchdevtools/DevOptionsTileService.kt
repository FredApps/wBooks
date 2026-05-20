package com.fredapps.watchdevtools

import android.provider.Settings
import android.util.Log
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

// Bump this string whenever the layout schema changes — it forces the Wear OS
// renderer to discard cached visuals and re-fetch from us.
private const val RESOURCES_VERSION = "4"
private const val TAG = "DevOptionsTile"

private const val BUTTON_BG = 0xFF1F4A8C.toInt()
private const val TITLE_COLOR = 0xFFAAAAAA.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/**
 * Tile with three quick-toggle shortcuts: Dev options (for wireless debugging),
 * Bluetooth settings, and Wi-Fi settings. None of these can actually flip the
 * toggle from a third-party app on modern Android — Bluetooth and Wi-Fi both
 * require user consent — but landing on the right settings page is one tap
 * away from the toggle.
 *
 * Each button's [ActionBuilders.LaunchAction] points at the same
 * [LaunchDevOptionsActivity] with a string extra carrying the settings action;
 * that activity reads the extra and fires the matching Settings.ACTION_*.
 */
class DevOptionsTileService : TileService() {

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        Log.i(TAG, "onTileResourcesRequest version=${requestParams.version}")
        return ResolvableFuture.create<ResourceBuilders.Resources>().apply {
            set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        }
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<Tile> {
        Log.i(TAG, "onTileRequest tileId=${requestParams.tileId}")

        val title = LayoutElementBuilders.Text.Builder()
            .setText("Quick toggles")
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(12f))
                    .setColor(argb(TITLE_COLOR))
                    .build()
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setWidth(expand())
            .addContent(title)
            .addContent(verticalSpacer(10))
            .addContent(button("Dev options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "dev"))
            .addContent(verticalSpacer(10))
            .addContent(button("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "bt"))
            .addContent(verticalSpacer(10))
            .addContent(button("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi"))
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column)
            .build()

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return ResolvableFuture.create<Tile>().apply { set(tile) }
    }

    /**
     * Build a pill-shaped clickable button. Each button launches
     * [LaunchDevOptionsActivity] with the [settingsAction] passed through as a
     * string extra; that activity does the actual `startActivity(Intent(action))`.
     */
    private fun button(
        label: String,
        settingsAction: String,
        clickId: String,
    ): LayoutElementBuilders.LayoutElement {
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("com.fredapps.watchdevtools.LaunchDevOptionsActivity")
                    .addKeyToExtraMapping(
                        LaunchDevOptionsActivity.EXTRA_SETTINGS_ACTION,
                        ActionBuilders.AndroidStringExtra.Builder()
                            .setValue(settingsAction)
                            .build()
                    )
                    .build()
            )
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(launchAction)
            .build()

        val text = LayoutElementBuilders.Text.Builder()
            .setText(label)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(18f))
                    .setColor(argb(WHITE))
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(BUTTON_BG))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(26f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(24f))
                            .setEnd(dp(24f))
                            .setTop(dp(12f))
                            .setBottom(dp(12f))
                            .build()
                    )
                    .build()
            )
            .addContent(text)
            .build()
    }

    private fun verticalSpacer(heightDp: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(dp(heightDp.toFloat()))
            .build()
}
