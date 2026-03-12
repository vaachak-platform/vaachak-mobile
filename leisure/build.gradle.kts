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

fun gitSha(): String {
    return try {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim().ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

fun requireSigningValue(name: String, value: String?): String {
    return value ?: throw GradleException(
        "Missing signing value: $name. " +
                "For local release builds, set it in ~/.gradle/gradle.properties. " +
                "For CI, provide ORG_GRADLE_PROJECT_$name through GitHub Actions secrets."
    )
}

val releaseKeystoreFile = file("vaachak-key.jks")
val releaseStorePassword =
    System.getenv("ORG_GRADLE_PROJECT_VAACHAK_KEYSTORE_PASSWORD")
        ?: providers.gradleProperty("VAACHAK_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias =
    System.getenv("ORG_GRADLE_PROJECT_VAACHAK_KEY_ALIAS")
        ?: providers.gradleProperty("VAACHAK_KEY_ALIAS").orNull
val releaseKeyPassword =
    System.getenv("ORG_GRADLE_PROJECT_VAACHAK_KEY_PASSWORD")
        ?: providers.gradleProperty("VAACHAK_KEY_PASSWORD").orNull

android {
    namespace = "org.vaachak.reader.leisure"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.vaachak.reader.leisure"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        versionCode = libs.versions.app.versionCode.get().toInt()
        versionName = libs.versions.app.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
    }

    val releaseSigningConfig = signingConfigs.create("release") {
        if (!releaseKeystoreFile.exists()) {
            throw GradleException(
                "Missing release keystore file: ${releaseKeystoreFile.absolutePath}"
            )
        }

        storeFile = releaseKeystoreFile
        storePassword = requireSigningValue("VAACHAK_KEYSTORE_PASSWORD", releaseStorePassword)
        keyAlias = requireSigningValue("VAACHAK_KEY_ALIAS", releaseKeyAlias)
        keyPassword = requireSigningValue("VAACHAK_KEY_PASSWORD", releaseKeyPassword)
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "SHOW_GIT_INFO", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigningConfig
            buildConfigField("boolean", "SHOW_GIT_INFO", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val variantNameLower = name.lowercase()
        val appVersionName = versionName ?: "0.0.0"

        val normalizedFileName = if (appVersionName.endsWith("-$variantNameLower")) {
            "LeisureVaachak-v$appVersionName.apk"
        } else {
            "LeisureVaachak-v$appVersionName-$variantNameLower.apk"
        }

        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = normalizedFileName
        }
    }
}
dependencies {
    implementation(project(":core"))

    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    coreLibraryDesugaring(libs.android.desugar)

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
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.opds)
    implementation(libs.readium.lcp)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.google.generativeai)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.apache.commons)
    implementation(libs.timber)
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
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