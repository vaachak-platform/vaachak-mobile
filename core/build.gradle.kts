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
    add("kspCommonMainMetadata", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)

    // Hilt Compiler
    // Hilt Compiler
    add("kspAndroid", libs.hilt.compiler)
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 4. ROOM KMP CONFIGURATION
room {
    schemaDirectory("$projectDir/schemas")
}
