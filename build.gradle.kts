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
    alias(libs.plugins.kotlin.multiplatform) apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}
detekt {
    // Builds will fail if it finds too many performance issues
    buildUponDefaultConfig = true
    allRules = false // We only want to turn on specific rule sets like 'Performance'
}
// Optional: Clean task configuration
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
    group= "clean"
    description="Clean build directory"
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                    project.buildDir.absolutePath + "/compose_metrics"
        )
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                    project.buildDir.absolutePath + "/compose_metrics"
        )
    }
}
