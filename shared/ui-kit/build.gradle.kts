plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Roborazzi (issue #555): adds `recordRoborazziDebug` / `verifyRoborazziDebug`
    // to render the ui-kit composables under `PocketShellTheme` to PNGs on the
    // host JVM in seconds, no emulator. Drives the fast design-iteration loop
    // (see `scripts/render.sh`). Additive to the emulator screenshot tests.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.pocketshell.uikit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Robolectric (and therefore Roborazzi) needs the merged Android resources
    // on the unit-test classpath so the real `PocketShellTheme` resolves during
    // host-JVM rendering. (#555)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Compose pulled from the same BOM as `:app`. Everything `PocketShellTheme`
    // and the palette `@Preview`s need: ui (Composable runtime + graphics),
    // material3 (MaterialTheme / ColorScheme / Typography / Shapes), and
    // tooling-preview (the `@Preview` annotation). `compose-ui-tooling` is
    // debug-only because it pulls in the inspector / preview renderer that
    // we never want shipped in release.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Compose instrumentation tests for the shared primitives (#480). The
    // `ui-test-manifest` (debug-only) supplies the empty activity that
    // `createComposeRule()` launches; `ui-test-junit4` is the test harness.
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)

    // Roborazzi fast-render harness (#555). Robolectric runs the real
    // composition on the host JVM; `roborazzi-compose` supplies the
    // `captureRoboImage(filePath) { … }` overload that launches its own headless
    // ComponentActivity and snapshots it; `ui-test-manifest` provides that empty
    // host activity. All test-only — none of this ships in an APK.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.manifest)
    // `createComposeRule` for JVM (Robolectric) behaviour tests of the shared
    // components — content / slot / callback assertions on the host JVM
    // (`:shared:ui-kit:testDebugUnitTest`), no emulator needed (#756).
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
}
