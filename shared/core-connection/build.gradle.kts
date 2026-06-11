plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// EPIC #687 Phase-2, slice 1 (module + state machine, NOT yet wired to the VM).
//
// This module holds ONLY pure-Kotlin connection-lifecycle logic: the
// `ConnectionController` reducer, the `ConnectionEvent`/`ConnectionState`/`Seed`/
// `RevealDecision` seam types, and the `TransportPort`/`TmuxPort`/`Clock` port
// interfaces the ViewModel will later adapt to the existing `SshLeaseManager` +
// `TmuxClient`. There is intentionally NO `android.*` import, NO Compose, and NO
// IO here — time is injected via `Clock` so the within-grace / beyond-grace /
// heal decisions are unit-testable on the host JVM with a virtual clock.
//
// It is an android-library module purely to match the other `:shared:core-*`
// modules' plugin set so `./gradlew :shared:core-connection:test` gates per-push
// CI the same way the merged #687 Phase-1 core-ssh characterization suite does
// (via the `tests.yml` `./gradlew test` line). The module is NOT yet a
// dependency of `:app` — it stands alone with its tests until the #661-gated
// extraction slice wires it into `TmuxSessionViewModel`.
android {
    namespace = "com.pocketshell.core.connection"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            all { test ->
                test.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}

dependencies {
    // Coroutines are part of the public surface: the controller exposes
    // StateFlow/Flow outputs and the ports declare suspend functions. `api`
    // so the VM module (later) doesn't need to re-declare it.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
