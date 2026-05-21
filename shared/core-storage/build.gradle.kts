plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pocketshell.core.storage"
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
        // Room requires an Android Context to build a database. Robolectric
        // gives the host JVM a real Android runtime without booting an
        // emulator, which is what `:test` needs. `isIncludeAndroidResources`
        // must be on so Robolectric can find the merged manifest.
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = true
                }
            }
        }
    }
}

dependencies {
    // Room is the only public surface today — downstream modules consume the
    // entities + DAOs directly. `api` so they don't have to re-declare it.
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    // DAOs return Flow / suspend — coroutines are part of the public surface.
    api(libs.kotlinx.coroutines.core)

    // Unit tests build an in-memory Room DB on the host JVM via Robolectric.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
