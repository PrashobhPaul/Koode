import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------
// Cloud backend (Supabase) is optional at build time. Fill in
// apps/android/supabase.properties (committed — the anon key is public by
// design; access control lives in supabase/schema.sql) or export
// SUPABASE_URL / SUPABASE_ANON_KEY. Without values the app builds and runs in
// LOCAL mode (full driver-side functionality, no cross-device sync).
// ---------------------------------------------------------------------------
val supaProps = Properties().apply {
    val f = rootProject.file("supabase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl: String = (System.getenv("SUPABASE_URL")
    ?: supaProps.getProperty("SUPABASE_URL") ?: "").trim()
val supabaseAnonKey: String = (System.getenv("SUPABASE_ANON_KEY")
    ?: supaProps.getProperty("SUPABASE_ANON_KEY") ?: "").trim()

android {
    namespace = "com.trippulse.app"
    compileSdk = 35

    defaultConfig {
        // Koode's permanent Play Store identity. (The internal code namespace
        // stays com.trippulse.app; only the public application id changed.)
        applicationId = "app.koode"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "4.2.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing is configured via a keystore outside source control.
            // See docs/RELEASE.md. Debug signing is used if none is provided so
            // internal test builds always assemble.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // --- Compose ---
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- AndroidX core ---
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // --- Room (local-first event log) ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // --- Location / Activity Recognition ---
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- Map rendering: osmdroid + OpenStreetMap tiles (free, no API key) ---
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // --- HTTP client: OSRM routing + Supabase (PostgREST) transport ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Unit tests (pure-JVM domain tests) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
