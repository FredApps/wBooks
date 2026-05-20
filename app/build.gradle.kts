import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sentry.android)
}

/**
 * Read a key from `local.properties` (gitignored) so each dev / CI can supply
 * their own Sentry DSN without committing it. Empty string when absent — Sentry
 * then auto-inits with no DSN and silently no-ops.
 */
fun localProperty(name: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(name).orEmpty()
}

android {
    namespace = "com.wbooks"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wbooks"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"

        manifestPlaceholders["sentryDsn"] = localProperty("sentry.dsn")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

sentry {
    org.set("fredapps")
    projectName.set("wbooks")
    authToken.set(localProperty("sentry.auth.token"))

    // Upload ProGuard/R8 mapping so minified release stack traces are readable.
    // Only runs on release variants where minify is on; debug builds are untouched.
    autoUploadProguardMapping.set(true)
    includeSourceContext.set(true)
    autoUploadSourceContext.set(true)

    // We don't want the plugin to bytecode-rewrite for performance tracing —
    // we're using Sentry for crashes only.
    tracingInstrumentation { enabled.set(false) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.jsoup)
    implementation(libs.nanohttpd)
    implementation(libs.play.services.wearable)
    implementation(libs.sentry.android)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)
    implementation(libs.guava.listenablefuture)
    implementation(libs.androidx.concurrent.futures)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.jsoup)
}
