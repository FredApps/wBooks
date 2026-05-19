# wBooks-companion (phone) — R8 keep rules.

# --- Jsoup: OPDS feed parsing uses Jsoup's XML mode.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Sentry auto-init reflection (see app/proguard-rules.pro for context).
-keep class io.sentry.android.core.SentryAndroidOptions { *; }
-keep class io.sentry.android.core.SentryInitProvider { *; }
-dontwarn io.sentry.**

# --- Coroutines internals.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
