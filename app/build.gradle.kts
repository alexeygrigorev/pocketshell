plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// sshj pulls in BouncyCastle (bcpkix-jdk18on, bcprov-jdk18on,
// bcutil-jdk18on) and jspecify, all of which ship duplicate Java
// resources. The app APK and androidTest APK are packaged separately, so both
// components need the same exclusions.
val duplicateJavaResourceExcludes = listOf(
    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
    "META-INF/INDEX.LIST",
    "META-INF/DEPENDENCIES",
    "META-INF/LICENSE",
    "META-INF/LICENSE.txt",
    "META-INF/license.txt",
    "META-INF/NOTICE",
    "META-INF/NOTICE.txt",
    "META-INF/notice.txt",
)

android {
    namespace = "com.pocketshell.app"
    compileSdk = 35

    // Issue #42: pin both debug and release APKs to a single committed
    // keystore so upgrading an existing install never trips the
    // "signatures do not match" / "uninstall first" path. The password
    // is the public Android debug password — the file has no real
    // security value, it just gives every machine (laptop, CI, release
    // tag build) the same signing identity. Mirrors
    // `ssh-auto-forward-android`'s setup.
    signingConfigs {
        create("debugKeystore") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.pocketshell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debugKeystore")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugKeystore")
        }
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

    sourceSets {
        getByName("androidTest") {
            assets.srcDir(rootProject.file("tests/docker"))
        }
    }

    packaging {
        resources {
            // AGP's default packaging merger refuses duplicates here.
            // Excluding them is safe because these metadata files are not
            // used by PocketShell at runtime.
            excludes += duplicateJavaResourceExcludes
        }
    }

    testOptions {
        // ProofPipelineTest runs Testcontainers against the Docker daemon,
        // same as `core-ssh`'s integration test. Mirror that module's
        // configuration: do not pre-bake Android resources into the JVM
        // test classpath, and pin the docker-java client API version so
        // current Docker engines (>= 25) accept the negotiation. See
        // `shared/core-ssh/build.gradle.kts` for the full rationale.
        //
        // `returnDefaultValues = true` mirrors `:shared:core-terminal` —
        // it lets calls to Android framework stubs (Handler, Looper, Log)
        // return Java defaults instead of throwing `RuntimeException: Stub!`.
        // The bridge-append test exercises code that touches `Handler`
        // indirectly via `TerminalSession`'s constructor.
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
            all { test ->
                test.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = true
                }
                val apiVersion = providers.gradleProperty("api.version")
                    .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
                    .get()
                test.systemProperty("api.version", apiVersion)
            }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Issue #18: `hiltViewModel()` for Hilt-injected ViewModels in
    // composables. Catalogised under `libs.versions.toml` per follow-up
    // #38 — bumps now live alongside the rest of the AndroidX pins.
    implementation(libs.androidx.hilt.navigation.compose)

    // Phase 0 proof-of-life (issue #9): pull in the SSH layer and the
    // vendored Termux surface so the app can render a real remote shell.
    // `core-ssh` exposes its `sshj` dependency transitively (`api`); we
    // call `client.startSession().startShell()` on the underlying
    // `SSHClient` directly from `ProofOfLifeScreen`, so we also need its
    // types on the compile classpath.
    implementation(project(":shared:core-ssh"))
    implementation(project(":shared:core-portfwd"))
    implementation(project(":shared:core-terminal"))
    implementation(project(":shared:core-agents"))

    // Issue #11: the design language (colour scheme, typography, shapes) lives
    // in `:shared:ui-kit` so future Phase 1 modules consume the same source
    // of truth. `MainActivity` consumes it via `PocketShellTheme`.
    implementation(project(":shared:ui-kit"))

    // Issue #18: host management screens persist hosts and SSH keys via
    // Room. `core-storage` already declares Room + coroutines `api`-style,
    // so this single dependency brings the DAOs + entities + AppDatabase
    // onto the classpath.
    implementation(project(":shared:core-storage"))

    // Issue #15: the voice prompt composer needs WhisperClient,
    // AudioRecorder, and ApiKeyStorage from the core-voice module. The
    // module already exposes OkHttp + coroutines transitively via `api`,
    // so no extra catalog wiring is needed here.
    implementation(project(":shared:core-voice"))

    // Issue #45: tmux control-mode client (TmuxClient + TmuxClientFactory)
    // for the per-pane Compose surface introduced under
    // `com.pocketshell.app.tmux`. core-tmux declares its core-ssh dep as
    // `implementation`; the app module already brings core-ssh on its own
    // (above) so the SshSession the ViewModel hands to TmuxClientFactory
    // resolves on the same classpath.
    implementation(project(":shared:core-tmux"))

    // Issue #24: usage panel parses normalized server-side usage JSON.
    // The app never stores provider credentials; it only renders records
    // returned by SSH-executed commands such as `heru usage --json`.
    implementation(project(":shared:core-usage"))

    // ProofPipelineTest connects to the `pocketshell-test:ssh` Docker
    // container the same way `core-ssh`'s integration test does, so the
    // app needs the same Testcontainers + JUnit + coroutines-test stack.
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testRuntimeOnly(libs.slf4j.nop)

    // Robolectric provides a Looper / Handler / Context implementation so
    // `SshTerminalBridge`'s `TerminalSession` (which spins up a
    // `MainThreadHandler` in its constructor) can be exercised on the
    // host JVM. The version pin lives in `gradle/libs.versions.toml`,
    // already declared for `:shared:core-storage`'s Room tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // Issue #18: ViewModel tests stand up an in-memory Room database to
    // exercise the host / key DAOs without an emulator. Mirrors
    // `:shared:core-storage`'s own DAO tests.
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestRuntimeOnly(libs.slf4j.nop)
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.packaging?.resources?.excludes?.addAll(
            duplicateJavaResourceExcludes
        )
    }
}
