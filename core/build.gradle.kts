import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.devtools.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dagger.hilt.android)
}

// 1. KOTLIN MULTIPLATFORM BLOCK
kotlin {
    // Suppress expect/actual beta warning
    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Define iOS targets for shared KMP
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "VaachakCore"
            isStatic = true
        }
    }

    // Sort dependencies by platform
    sourceSets {
        commonMain.dependencies {
            // Room KMP
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // Ktor Networking (Shared)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)

            // Readium (Shared)
            implementation(libs.readium.shared)
            implementation(libs.readium.streamer)
            implementation(libs.readium.opds)

            // Utilities
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.datastore.preferences.core)
            implementation("com.squareup.okio:okio:3.9.0")
        }

        commonTest.dependencies {
            implementation(libs.junit)
            // Replaced catalog variables with the hardcoded strings you used previously
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            implementation("app.cash.turbine:turbine:1.0.0")

            // If you use MockK in your core tests, you'll need this one too:
            implementation("io.mockk:mockk:1.13.8")
        }

        androidMain.dependencies {
            // Android-specific dependencies
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.datastore.preferences)

            // Android implementations for shared concepts
            implementation(libs.androidx.room.ktx)
            implementation(libs.hilt.android)

            // Legacy / Android-only networking & AI
            implementation(libs.retrofit)
            implementation(libs.retrofit.gson)
            implementation(libs.okhttp)
            implementation(libs.google.generativeai)
            implementation(libs.apache.commons)
            // ADD TIMBER HERE:
            implementation(libs.timber)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.androidx.junit)
                implementation("androidx.test:core:1.5.0")
                implementation("org.robolectric:robolectric:4.11.1")
            }
        }
    }
}

// 2. ANDROID CONFIGURATION BLOCK
android {
    namespace = "org.vaachak.reader.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 3. COMPILER PLUGINS (KSP) & DESUGARING
dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    // Room Compiler for KMP

    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)

    // Hilt Compiler
    add("kspAndroid", libs.hilt.compiler)

    // Instrumented tests remain here
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 4. ROOM KMP CONFIGURATION
room {
    schemaDirectory("$projectDir/schemas")
}