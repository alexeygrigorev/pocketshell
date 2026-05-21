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
}
