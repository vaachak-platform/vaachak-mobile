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
    id("org.sonarqube") version "7.2.3.7755"
}
val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
val appVersionName = libs.findVersion("app-versionName").get().requiredVersion

version = appVersionName
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
    // Modern layout.buildDirectory instead of the deprecated buildDir
    val composeMetricsDir = layout.buildDirectory.dir("compose_metrics").get().asFile.absolutePath

    compilerOptions {
        freeCompilerArgs.addAll(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$composeMetricsDir",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$composeMetricsDir"
        )
    }
}

sonar {
    properties {
        property("sonar.projectKey", "vaachak-platform_vaachak-mobile")
        property("sonar.organization", "vaachak-platform")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.projectVersion", appVersionName)
        // Keep the scanner fast by excluding auto-generated Android files and KSP outputs
        property("sonar.exclusions", "**/build/**, **/ksp/**, **/*.xml, **/R.class, **/BuildConfig.class")
    }
}