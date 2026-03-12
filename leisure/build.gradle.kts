import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream

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
                "For CI release builds, provide ORG_GRADLE_PROJECT_$name."
    )
}

val requestedTasks = gradle.startParameter.taskNames.map { it.lowercase() }
val isReleaseLikeTaskRequested = requestedTasks.any { task ->
    "release" in task || "bundle" in task || "publish" in task
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

    val releaseSigningConfig = if (isReleaseLikeTaskRequested) {
        signingConfigs.create("release") {
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
    } else {
        null
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
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
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