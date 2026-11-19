import java.util.Properties

plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

// Optional external build directory (via local.properties `bgoom.buildDir` or env `BGOOM_BUILD_DIR`)
// Useful on macOS when repo resides on exFAT to avoid AppleDouble (._*) files in Gradle intermediates.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val customBuildRoot = localProps.getProperty("bgoom.buildDir")
    ?: System.getenv("BGOOM_BUILD_DIR")
if (!customBuildRoot.isNullOrBlank()) {
    allprojects {
        layout.buildDirectory.set(file("$customBuildRoot/${project.name}"))
    }
}
