plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
}
