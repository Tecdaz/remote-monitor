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
        minSdk = 33
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
            // Polar H10 device-id filter for the GATT + ECG providers.
            // Defaults to the strap name so the app works without build
            // flags; override with:
            //   ./gradlew :app:assembleDebug -PpolarDeviceId=H10_ABC123
            // Empty = collection disabled (UI-only build).
            val polarDeviceId =
                (project.findProperty("polarDeviceId") as String?) ?: "Polar H10"
            buildConfigField("String", "POLAR_DEVICE_ID", "\"$polarDeviceId\"")
        }
        release {
            isMinifyEnabled = false
            // T-WATCH-22: production URL is a placeholder. Override at
            // build time per environment:
            //   ./gradlew :app:assembleRelease -PapiBaseUrl=https://api.real-host.example/
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "https://api.example.com/"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            val polarDeviceId =
                (project.findProperty("polarDeviceId") as String?) ?: "Polar H10"
            buildConfigField("String", "POLAR_DEVICE_ID", "\"$polarDeviceId\"")
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

// T-WATCH-36: wire the merged debug manifest into the unit-test JVM
// so Robolectric can resolve the ComponentActivity host
// (`createAndroidComposeRule<ComponentActivity>` launches a LAUNCHER
// intent and looks up the activity in the manifest). Without this the
// test runner falls back to the empty default manifest with package
// `org.robolectric.default`, and the intent can't be resolved.
android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.systemProperty(
                "robolectric.manifest",
                "$projectDir/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
            )
            test.systemProperty("robolectric.application", "com.remotemonitor.watch.WatchApplication")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // Sync + data layer.
    implementation(libs.datastore.preferences)
    implementation(libs.polar.ble.sdk)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    // wear-ui-guidelines PR-2 (D1): swap mobile `navigation-compose` for the
    // Wear variant. Pulls in `navigation-runtime` transitively, keeping
    // `NavHostController` + `rememberNavController` resolvable.
    implementation(libs.wear.compose.navigation)
    // wear-ui-guidelines PR-4 (D2): pair the FGS notification with a Wear
    // OngoingActivity so the watch-face shows a heart icon + sync status
    // while the watch is syncing (spec #448 cap 5). The
    // `OngoingActivity.Builder` ctor requires `NotificationCompat.Builder`,
    // so the FGS builder is migrated to the AndroidX variant in
    // `SyncForegroundService.buildNotification`.
    implementation(libs.wear.ongoing)

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
    // T-WATCH-36: Compose UI test infra. Pure-JVM via Robolectric +
    // createComposeRule() (no instrumentation device required).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}