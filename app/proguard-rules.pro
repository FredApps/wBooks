# wBooks (watch) â€” R8 keep rules.
#
# Most AndroidX / GMS / Compose libraries ship consumer proguard rules in their
# AARs, so we only list the things R8 can't infer on its own.

# --- NanoHTTPD: reflects internally on response handlers / mime types.
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# --- Jsoup: parser uses reflection for tag handlers and namespaces.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Sentry: auto-init reads manifest meta-data and instantiates options
# reflectively. The SDK ships its own consumer rules but we add safety nets.
-keep class io.sentry.android.core.SentryAndroidOptions { *; }
-keep class io.sentry.android.core.SentryInitProvider { *; }
-dontwarn io.sentry.**

# --- WearableListenerService: framework instantiates the service class by name
# from the manifest; the override of onRequest / onChannelOpened is called via
# the parent dispatch which uses reflection on method signatures.
-keep class com.fredapp.wbooks.transfer.BookReceiverService { *; }
-keep class * extends com.google.android.gms.wearable.WearableListenerService

# --- Tile services are instantiated by the system from the manifest.
-keep class * extends androidx.wear.tiles.TileService

# --- Complication data sources: ditto. Defensive â€” AGP auto-keeps the
# manifest-declared class names but R8 can strip abstract-method overrides
# if it can't see the contract.
-keep class * extends androidx.wear.watchface.complications.datasource.ComplicationDataSourceService

# --- Document cache codec uses our model classes directly; the codec is hand
# rolled (no reflection), so the regular minification is safe, but keep the
# model classes' field names since they appear in cached binaries' field order.
# (DocumentCodec is positional, not name-keyed, but we keep these defensively.)
-keep class com.fredapp.wbooks.parser.model.** { *; }

# --- Coroutines internals (kotlinx-coroutines-core ships rules but the
# play-services bridge sometimes misses).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
