plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// -- Redirect build output out of OneDrive (Windows local dev only) --
// OneDrive intermittently holds open files inside app/build/, which causes
// random "Unable to delete directory" failures from Gradle's clean phase. On
// Windows we move every subproject's build dir to a local-disk scratch path.
// Skipped on other OSes (e.g. Linux CI runners) where OneDrive isn't a factor.
//
// Path is C:\GradleTmp\<user>\wbooks-build, NOT under %LOCALAPPDATA%: this
// machine's AppContainer/sandbox blocks AF_UNIX connect() for files under the
// user profile's AppData\Local tree, and the Kotlin daemon's NIO pipes use
// java.io.tmpdir under buildDir — keeping the buildDir outside LocalAppData
// avoids that failure mode. See gradlew.bat for the matching tmpdir redirect.
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    val userName = System.getenv("USERNAME") ?: System.getProperty("user.name")
    val wbooksBuildRoot = file("C:/GradleTmp/$userName/wbooks-build")
    subprojects {
        layout.buildDirectory.set(file("$wbooksBuildRoot/${project.name}"))
    }
}
