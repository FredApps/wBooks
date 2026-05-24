# wBooks-companion (phone) — R8 keep rules.

# --- Jsoup: OPDS feed parsing uses Jsoup's XML mode.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- PDFBox treats JPEG-2000 support as optional; the converter still works
# for normal text PDFs when the JP2 decoder artifact is absent.
-dontwarn com.gemalto.jp2.JP2Decoder

# --- Sentry auto-init reflection (see app/proguard-rules.pro for context).
-keep class io.sentry.android.core.SentryAndroidOptions { *; }
-keep class io.sentry.android.core.SentryInitProvider { *; }
-dontwarn io.sentry.**

# --- Coroutines internals.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
