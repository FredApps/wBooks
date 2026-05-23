import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sentry.android)
}

fun localProperty(name: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(name).orEmpty()
}

/**
 * Like [localProperty] but fails the build with a clear message when the value
 * is missing. The Android applicationId is fetched through this so the package
 * name stays out of the public repo while still being baked into every APK.
 */
fun requireLocalProperty(name: String): String {
    val value = localProperty(name)
    if (value.isBlank()) {
        error(
            "Missing '$name' in local.properties. " +
                "See README.md (Local configuration) for the required keys."
        )
    }
    return value
}

val wBooksSigningProperties by lazy {
    val file = rootProject.projectDir.parentFile.resolve(".secrets/wBooks-signing.properties")
    if (!file.isFile) {
        error("Missing signing properties: ${file.absolutePath}")
    }
    Properties().apply {
        file.inputStream().use { load(it) }
    }
}

fun wBooksSigningProperty(name: String): String =
    wBooksSigningProperties.getProperty(name)
        ?: error("Missing signing property '$name' in wBooks-signing.properties")

val wBooksKeystoreFile by lazy {
    val file = rootProject.projectDir.parentFile
        .resolve(".secrets")
        .resolve(wBooksSigningProperty("storeFile"))
    if (!file.isFile) {
        error("Missing signing keystore: ${file.absolutePath}")
    }
    file
}

android {
    namespace = "com.fredapp.wbooksutil"
    compileSdk = 36

    signingConfigs {
        create("wBooks") {
            storeFile = wBooksKeystoreFile
            storePassword = wBooksSigningProperty("storePassword")
            keyAlias = wBooksSigningProperty("keyAlias")
            keyPassword = wBooksSigningProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = requireLocalProperty("wbooks.applicationId")
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "0.8.0"

        manifestPlaceholders["sentryDsn"] = localProperty("sentry.dsn")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("wBooks")
        }

        release {
            signingConfig = signingConfigs.getByName("wBooks")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }
}

val hasSentryAuthToken = localProperty("sentry.auth.token").isNotBlank()

sentry {
    org.set("fredapps")
    projectName.set("wbooks")
    authToken.set(localProperty("sentry.auth.token"))

    autoUploadProguardMapping.set(hasSentryAuthToken)
    includeSourceContext.set(hasSentryAuthToken)
    autoUploadSourceContext.set(hasSentryAuthToken)

    tracingInstrumentation { enabled.set(false) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.sentry.android)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.jsoup)
}
