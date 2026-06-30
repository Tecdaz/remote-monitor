plugins {
    alias(libs.plugins.android.application)
    // T-WATCH-18: explicit kotlin-android (paired with android.builtInKotlin=false
    // in gradle.properties) is required by KSP for the source-set pipeline.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // T-WATCH-18: KSP for Room compiler. Version pinned in libs.versions.toml
    // (the documented exception to the "no version constraints" rule).
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.remotemonitor.watch"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.remotemonitor.watch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

    }

    buildTypes {
        debug {
            // T-WATCH-22: default to the Android emulator host loopback
            // (10.0.2.2 = host). Override at build time for a real device:
            //   ./gradlew :app:assembleDebug -PapiBaseUrl=http://192.168.1.42:8000/
            // The IP must be reachable from the watch's network (same WiFi).
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "http://10.0.2.2:8000/"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            // T-WATCH-22: production URL is a placeholder. Override at
            // build time per environment:
            //   ./gradlew :app:assembleRelease -PapiBaseUrl=https://api.real-host.example/
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "https://api.example.com/"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        // T-WATCH-22: BuildConfig.API_BASE_URL is consumed by ApiClient.
        // AGP 8+ requires buildConfig = true to emit BuildConfig.java.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

// T-WATCH-18: Room schema export location. The Room compiler writes the
// schema JSON files for the entities under app/schemas/. The directory
// is git-ignored except for committed versions; the actual generation
// happens at build time (never hand-written per project rules).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // Samsung Health Sensor SDK AAR (REQ-WATCH-12).
    // The real AAR lives at watch/libs/samsung-health-tracking.aar; it must be
    // downloaded manually from https://developer.samsung.com/health/sensor.
    // The line below is commented out because the placeholder in the repo
    // is not a valid AAR — see watch/libs/README.md for the install steps.
    // TODO(sdd-apply): add AAR when available — uncomment the line below.
    // ⚠️  DO NOT uncomment until the placeholder is replaced with a real AAR
    // (must be a valid ZIP; the placeholder is UTF-8 plaintext and will fail
    // AAR parsing with an obscure error).
    // implementation(files("libs/samsung-health-tracking.aar"))

    // Sync + data layer (T-WATCH-17..24 production code; Room + KSP added in T-WATCH-18)
    implementation(libs.datastore.preferences)
    implementation(libs.health.services.client)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    // Room (T-WATCH-18). The Room artifacts are versioned explicitly in the
    // version catalog (`room = "2.7.2"`) because no `androidx.room:room-bom`
    // exists on Google Maven. The compiler is wired via ksp().
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}