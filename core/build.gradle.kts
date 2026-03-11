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
    // Register the Android target properly first
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Suppress expect/actual beta warning
    sourceSets.all {
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.readium.shared)
            implementation(libs.readium.streamer)
            implementation(libs.readium.opds)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.datastore.preferences.core)
            implementation("com.squareup.okio:okio:3.16.4")
        }

        // commonTest block removed entirely as requested

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.room.ktx)
            implementation(libs.hilt.android)
            implementation(libs.retrofit)
            implementation(libs.retrofit.gson)
            implementation(libs.okhttp)
            implementation(libs.google.generativeai)
            implementation(libs.apache.commons)
            implementation(libs.timber)
        }

        val androidUnitTest by getting {
            dependencies {
                // Core Android Test Infrastructure
                implementation(libs.androidx.junit)
                implementation("androidx.test:core-ktx:1.7.0")
                implementation("androidx.test.ext:junit-ktx:1.3.0")
                implementation("androidx.room:room-testing:2.8.4")
                implementation("org.robolectric:robolectric:4.16.1")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")

                // Migrated from commonTest to support your BaseDaoTest and Security Domain tests
                implementation(libs.junit)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("app.cash.turbine:turbine:1.2.1")

                // Added here to fix the iOS compilation crash in KMP
                implementation("io.mockk:mockk:1.14.9")
            }
        }
    }
}

// 2. ANDROID CONFIGURATION BLOCK
android {
    namespace = "org.vaachak.reader.core"

    // Bumped to 36 to satisfy the androidx.core:core-ktx:1.17.0 requirement from your CI logs
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// 3. COMPILER PLUGINS (KSP) & DESUGARING
dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspAndroid", libs.hilt.compiler)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

room {
    schemaDirectory("$projectDir/schemas")
}