plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// -- Redirect build output out of OneDrive --
// OneDrive intermittently holds open files inside app/build/, which causes
// random "Unable to delete directory" failures from Gradle's clean phase. Move
// every subproject's build dir to a local-disk scratch path. The project source
// itself stays in OneDrive (it's git-tracked anyway).
val wbooksBuildRoot = file("C:/GradleTmp/wbooks-build")
subprojects {
    layout.buildDirectory.set(file("$wbooksBuildRoot/${project.name}"))
}
