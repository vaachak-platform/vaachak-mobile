// Root build.gradle.kts
plugins {
    // Define versions here, but don't apply them to the root project (apply false)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dagger.hilt.android) apply false
    alias(libs.plugins.devtools.ksp) apply false

    // Fixes your specific error: Defines the version for all submodules
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    alias(libs.plugins.jetbrains.dokka) apply false
}

// Optional: Clean task configuration
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
