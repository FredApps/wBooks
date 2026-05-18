# NanoHTTPD reflects internally on a few classes; keep its API surface.
-keep class org.nanohttpd.** { *; }

# Jsoup uses reflection on selector classes.
-keep class org.jsoup.** { *; }
