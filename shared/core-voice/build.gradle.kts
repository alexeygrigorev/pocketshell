plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.core.voice"
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
        // ApiKeyStorage uses EncryptedSharedPreferences which needs an Android
        // Context + KeyStore. Robolectric provides both on the host JVM, but
        // it needs merged Android resources to do so. The Whisper client tests
        // also use MockWebServer (host JVM only) — both happily coexist here.
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
    // OkHttp is the public HTTP transport: callers may pass their own
    // `OkHttpClient` into `OkHttpWhisperClient` (e.g. to share a connection
    // pool with the rest of the app), so it sits on the `api` configuration.
    api(libs.okhttp)

    // Coroutines are part of the public surface — `transcribe()` is `suspend`.
    api(libs.kotlinx.coroutines.core)

    implementation("org.json:json:20240303")

    // EncryptedSharedPreferences is an implementation detail of
    // AndroidKeystoreApiKeyStorage; callers only see save/load/clear, so this
    // can stay on `implementation`.
    implementation(libs.androidx.security.crypto)

    // Unit tests. MockWebServer mocks the OpenAI endpoint; Robolectric gives
    // us an Android Context + KeyStore so EncryptedSharedPreferences can
    // round-trip on the host JVM without an emulator.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
