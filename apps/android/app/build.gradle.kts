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
        versionCode = 13
        versionName = "6.3.1"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        create("distribution") {
            // Two ways in, checked in order:
            //
            //  1. CI secrets (KEYSTORE_BASE64 + passwords) — the private key
            //     for a real, Play-Store-grade release. Preferred whenever set.
            //  2. keystore/koode-open.jks — a keystore committed to the repo
            //     on purpose. Its password is public and it protects nothing;
            //     its only job is to sign every build with the SAME key so
            //     Android accepts an in-place update. That data-preserving
            //     update is a hard requirement for a safety app (a family must
            //     never lose a live journey to an upgrade), and it outweighs
            //     key confidentiality in a sideload-from-GitHub threat model —
            //     the same trade-off F-Droid makes. Rotate to (1) before any
            //     wider distribution; see docs/RELEASE.md.
            val secretStore = System.getenv("KEYSTORE_BASE64")
            if (!secretStore.isNullOrBlank()) {
                val decoded = java.io.File.createTempFile("koode-release", ".jks")
                decoded.writeBytes(java.util.Base64.getDecoder().decode(secretStore.trim()))
                storeFile = decoded
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("../keystore/koode-open.jks")
                storePassword = System.getenv("OPEN_KEYSTORE_PASSWORD") ?: "koode-open"
                keyAlias = "koode"
                keyPassword = System.getenv("OPEN_KEYSTORE_PASSWORD") ?: "koode-open"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Minification is deliberately OFF. It would need a full set of
            // keep rules for Room, Compose and the reflection this app uses,
            // and a wrong rule is a crash that only shows on a real install —
            // exactly what a family cannot afford. Size is not the constraint
            // for a sideloaded safety app; not crashing is.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("distribution")
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
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- AndroidX core ---
    implementation("androidx.core:core-ktx:1.15.0")
    // Platform splash screen with a compat path back to API 26.
    implementation("androidx.core:core-splashscreen:1.0.1")
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
