import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("com.google.devtools.ksp")
}

// Load secrets and environment from local.properties or environment variables
val localProps = Properties().also { props ->
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        lp.inputStream().use { props.load(it) }
    }
}

fun prop(name: String, default: String = ""): String {
    return (localProps.getProperty(name) ?: System.getenv(name) ?: default)
}

// Exposed BuildConfig values (DO NOT store service_role key in app)
// Set these in local.properties:
// SUPABASE_URL=https://xxxx.supabase.co
// SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9... (anon/public key)
// ORG_ID=00000000-0000-0000-0000-000000000000
// DEFAULT_REGION=IR
val SUPABASE_URL = prop("SUPABASE_URL")
val SUPABASE_ANON_KEY = prop("SUPABASE_ANON_KEY")
val ORG_ID = prop("ORG_ID")
val DEFAULT_REGION = prop("DEFAULT_REGION", "IR")

android {
    namespace = "com.sharedcrm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sharedcrm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // BuildConfig fields to be used in Kotlin code
        buildConfigField("String", "SUPABASE_URL", "\"${SUPABASE_URL}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${SUPABASE_ANON_KEY}\"")
        buildConfigField("String", "ORG_ID", "\"${ORG_ID}\"")
        buildConfigField("String", "DEFAULT_REGION", "\"${DEFAULT_REGION}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Enable extra logs if needed
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjvm-default=all"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose Compiler is configured via the Kotlin Compose Compiler Gradle plugin
    // (org.jetbrains.kotlin.plugin.compose). No composeOptions block is required with Kotlin 2.0+.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room (KSP)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Phone number normalization
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.40")

    // Supabase Kotlin client (Postgrest, Auth, Realtime)
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.5.7")
    implementation("io.github.jan-tennert.supabase:auth-kt:2.5.7")
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.5.7")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coil (optional images/avatars)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}