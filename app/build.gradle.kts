plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.clu.motion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clu.motion"
        // API 28: display-cutout aware overlays and a smaller injection test matrix.
        // GestureDescription.StrokeDescription#continueStroke itself needs API 26.
        minSdk = 28
        targetSdk = 36
        // Commit count: every CI or local build of a newer commit installs as an update.
        versionCode = gitCommitCount
        versionName = "1.1.0"
    }

    signingConfigs {
        // Public, committed test key: CI and local builds can update each other on a test phone
        // without uninstalling (which would reset the accessibility toggle and profiles).
        // Never ship it to a store; set CLU_KEYSTORE_* environment variables for real releases.
        create("test") {
            storeFile = file("clu-test.keystore")
            storePassword = "clutest"
            keyAlias = "clu-test"
            keyPassword = "clutest"
        }
        System.getenv("CLU_KEYSTORE_PATH")?.let { path ->
            create("upload") {
                storeFile = file(path)
                storePassword = System.getenv("CLU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLU_KEY_ALIAS")
                keyPassword = System.getenv("CLU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release = optimised, non-debuggable: the realistic build for latency testing.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
        // Robolectric layout tests (overlay sizes) need the app's resources.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // aapt2 only links <adaptive-icon> from a -v26 folder, whatever minSdk says.
        disable += "ObsoleteSdkInt"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
