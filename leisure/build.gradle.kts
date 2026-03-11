plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.devtools.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.dokka)
}

android {
    namespace = "org.vaachak.reader.leisure"

    // Updated to use TOML variable
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.vaachak.reader.leisure"

        // Updated to use TOML variables
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        versionCode = libs.versions.app.versionCode.get().toInt()
        versionName = libs.versions.app.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("vaachak-key.jks")
            // This reads from the GitHub Action ENV vars, or falls back to your local properties
            storePassword = System.getenv("ORG_GRADLE_PROJECT_KEYSTORE_PASSWORD") ?: "your_local_dev_password"
            keyAlias = System.getenv("ORG_GRADLE_PROJECT_KEY_ALIAS") ?: "your_local_alias"
            keyPassword = System.getenv("ORG_GRADLE_PROJECT_KEY_PASSWORD") ?: "your_local_key_password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Tell it to use the config we just made above
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        val variantName = name.replaceFirstChar { it.uppercase() }
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "LeisureVaachak-$variantName.apk" // Updated naming
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // --- CONNECT TO CORE MODULE ---
    implementation(project(":core"))
    implementation(libs.androidx.foundation.layout)

    // Desugaring
    coreLibraryDesugaring(libs.android.desugar)

    // Android & UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.ui.viewbinding)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Readium
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.opds)
    implementation(libs.readium.lcp)
    implementation(libs.readium.navigator.media.tts)
    // ADD THESE for TTS Navigator support
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // AI & Utilities
    implementation(libs.google.generativeai)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.apache.commons)

    // Database (Room)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.timber)

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Unit Testing core
    testImplementation("junit:junit:4.13.2")

    // Coroutines testing (for runTest and StandardTestDispatcher)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // MockK for mocking Dao and Repository classes
    testImplementation("io.mockk:mockk:1.14.9")

    // Turbine for cleanly testing Kotlin StateFlow/SharedFlow
    testImplementation("app.cash.turbine:turbine:1.2.1")

    testImplementation("org.robolectric:robolectric:4.16.1")

}

tasks.dokkaHtml {
    outputDirectory.set(layout.buildDirectory.dir("dokka"))
    moduleName.set("Leisure Vaachak API Reference")
    dokkaSourceSets {
        configureEach {
            skipDeprecated.set(true)
            reportUndocumented.set(true)
        }
    }
}