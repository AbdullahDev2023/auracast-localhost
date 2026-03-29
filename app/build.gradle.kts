import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// ── Keystore helpers (CI-friendly: env vars take priority over local file) ───
val keystoreProperties = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) keystoreProperties.load(keystoreFile.inputStream())

fun prop(envKey: String, fileKey: String, default: String = ""): String =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(fileKey, default)

android {
    namespace  = "com.akdevelopers.auracast"
    compileSdk = 35

    defaultConfig {
        applicationId  = "com.akdevelopers.auracast"
        minSdk         = 26
        targetSdk      = 35
        versionCode    = 12          // bump on every release
        versionName    = "2.0.0"     // semantic version

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Make key config available to Kotlin via BuildConfig
        buildConfigField("String", "DEFAULT_SERVER_URL",
            "\"wss://nonmanifestly-smudgeless-lamonica.ngrok-free.dev/stream\"")
        buildConfigField("String", "FIREBASE_DB_URL",
            "\"https://auracast-df815-default-rtdb.asia-southeast1.firebasedatabase.app\"")
    }

    // ── Signing ────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            storeFile   = prop("KEYSTORE_PATH",   "storeFile").let { p ->
                if (p.isNotEmpty()) file(p) else null
            }
            storePassword = prop("KEYSTORE_PASS", "storePassword")
            keyAlias      = prop("KEY_ALIAS",     "keyAlias")
            keyPassword   = prop("KEY_PASS",      "keyPassword")
        }
    }

    // ── Build types ─────────────────────────────────────────────────────────
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            isDebuggable        = true
            isMinifyEnabled     = false
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigningConfig = signingConfigs.getByName("release")
            if (releaseSigningConfig.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    // ── Java / Kotlin compile targets ────────────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { jvmToolchain(11) }

    // ── Feature flags ───────────────────────────────────────────────────
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // ── Test options ─────────────────────────────────────────────────────
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues     = true

        }
    }

    // ── Lint ─────────────────────────────────────────────────────────────
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "Deprecated",
            "GradleDependency",
            "MissingPermission",
            "OldTargetApi",
            "WrongConstant"
        )
        htmlReport = true
        htmlOutput = file("build/reports/lint/lint-report.html")
        xmlReport  = true
        xmlOutput  = file("build/reports/lint/lint-report.xml")
    }

    // ── Packaging ────────────────────────────────────────────────────────
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

// ── Test JVM tuning ──────────────────────────────────────────────────────
tasks.withType<Test> {
    maxHeapSize = "512m"
    maxParallelForks = 1
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:platform"))
    implementation(project(":core:observability"))
    implementation(project(":domain:streaming"))
    implementation(project(":data:streaming"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:stream"))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)

    // Networking
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Background work
    implementation(libs.workmanager)

    // Local AAR (Opus JNI codec)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
