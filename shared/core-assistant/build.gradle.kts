plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.core.assistant"
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
        // AssistantConfigStore uses EncryptedSharedPreferences which needs an
        // Android Context + KeyStore. Robolectric provides both on the host
        // JVM (it needs merged Android resources to do so). The LLM client
        // tests also use MockWebServer (host JVM only) — both coexist here,
        // mirroring the :shared:core-voice setup.
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
    // `OkHttpClient` into a client (e.g. to share a connection pool), so it
    // sits on the `api` configuration to match :shared:core-voice.
    api(libs.okhttp)

    // Coroutines are part of the public surface — `complete()` is `suspend`.
    api(libs.kotlinx.coroutines.core)

    // JSON request/response shaping for both the Anthropic Messages API and
    // the OpenAI chat-completions API. org.json matches the parser dependency
    // already used by core-voice's CommandPlanner.
    implementation("org.json:json:20240303")

    // EncryptedSharedPreferences is an implementation detail of
    // AndroidKeystoreAssistantConfigStore; callers only see the store's small
    // load/save/clear surface, so this stays on `implementation`.
    implementation(libs.androidx.security.crypto)

    // Unit tests. MockWebServer mocks the Anthropic / OpenAI endpoints;
    // Robolectric supplies an Android Context + KeyStore so
    // EncryptedSharedPreferences round-trips on the host JVM without an
    // emulator.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
