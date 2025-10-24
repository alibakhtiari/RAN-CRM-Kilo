import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
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

val SUPABASE_URL = prop("SUPABASE_URL")
val SUPABASE_ANON_KEY = prop("SUPABASE_ANON_KEY")
val ORG_ID = prop("ORG_ID")
val DEFAULT_REGION = prop("DEFAULT_REGION", "IR")

android {
    namespace = "com.sharedcrm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sharedcrm"
        minSdk = 24
        targetSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.6")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room (KSP)
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Phone number normalization
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.41")


    // Import the Supabase Bill of Materials (BOM)
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.0"))

    // Now implement the modules you need (the BOM will control the version)
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")


    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Ktor HTTP engine (required by supabase-kt)
    implementation("io.ktor:ktor-client-android")

    // Coil (optional images/avatars)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
