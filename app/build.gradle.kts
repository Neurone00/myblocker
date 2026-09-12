import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Versioning -----------------------------------------------------------
// Base semantic version lives here. CI passes BUILD_NUMBER (the workflow run
// number) and GIT_SHA so every build gets a unique, sortable version and the
// APK file name carries it: MyBlocker-v1.0.0-b42-1a2b3c4-release.apk
val baseVersion = "1.0.0"
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val gitSha: String = System.getenv("GIT_SHA")?.take(7)
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }
            .standardOutput.asText.get().trim()
    }.getOrDefault("local")
val fullVersionName = if (buildNumber > 0) "$baseVersion-b$buildNumber" else "$baseVersion-dev"

android {
    namespace = "com.neurone.myblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neurone.myblocker"
        minSdk = 29
        targetSdk = 35
        versionCode = if (buildNumber > 0) buildNumber else 1
        versionName = fullVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        create("release") {
            // CI can provide a private keystore through secrets; otherwise the
            // committed development keystore is used so builds stay installable
            // over each other. See README "Signing".
            val ksPath = System.getenv("KEYSTORE_PATH") ?: rootProject.file("keystore/myblocker-dev.jks").path
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "myblocker"
            keyAlias = System.getenv("KEY_ALIAS") ?: "myblocker"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "myblocker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Lint findings must never break the CI build; run ./gradlew lint to review them.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "MyBlocker-v$fullVersionName-$gitSha-${variant.buildType.name}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Intentionally framework-only: no AndroidX, no third-party runtime deps.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")
}
