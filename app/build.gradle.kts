plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Firebase Cloud Messaging (issue #690). The `google-services` plugin is
// applied ONLY when `app/google-services.json` exists. Without a configured
// Firebase project that file is absent, and the app still builds + ships the
// in-app reset banner fallback — the FCM messaging code compiles regardless
// (the SDK is on the classpath), it just won't actually receive pushes until
// the maintainer creates a Firebase project and drops in google-services.json.
// See the issue #690 status comment for the one-time setup step.
//
// It is also skipped for per-worktree isolation builds (issue #737): when
// `pocketshellAppIdSuffix` is set (the #672 scheme), the applicationId becomes
// `com.pocketshell.app.<suffix>`, which can never match the single
// `package_name` ("com.pocketshell.app") in google-services.json, so
// processGoogleServices would fail configuration. Isolation builds don't need
// real FCM, so we just don't apply the plugin for them.
if (file("google-services.json").exists() &&
    project.findProperty("pocketshellAppIdSuffix") == null) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
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
        versionCode = 83
        versionName = "0.4.36"
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

            // Issue #672: let each parallel worktree install its DEBUG apk
            // under a distinct applicationId (e.g. `com.pocketshell.app.i672`)
            // so multiple test apps coexist on ONE emulator without
            // uninstalling each other. The suffix comes from a gradle
            // property and DEFAULTS EMPTY — so `./gradlew :app:assembleDebug`
            // with no property is byte-for-byte the old `com.pocketshell.app`
            // build, and the release build type is never touched.
            //
            // Only `[A-Za-z0-9._]` is allowed (a package-segment token) so a
            // stray value can't produce an invalid/ambiguous applicationId.
            val rawSuffix = (project.findProperty("pocketshellAppIdSuffix") as String?)
                ?.trim()
                .orEmpty()
            if (rawSuffix.isNotEmpty()) {
                require(rawSuffix.matches(Regex("[A-Za-z0-9._]+"))) {
                    "pocketshellAppIdSuffix must match [A-Za-z0-9._]+ (got: '$rawSuffix')"
                }
                // applicationIdSuffix is appended verbatim, so prepend the dot
                // separator ourselves (`i672` -> `com.pocketshell.app.i672`).
                applicationIdSuffix = ".$rawSuffix"
            }
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
        getByName("test") {
            java.srcDir("src/integrationTest/java")
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

                // #1518: the Robolectric render-heal sibling suite
                // (*StaleRender* / *RenderHeal* / *Watchdog*, plus the heavy
                // TmuxSessionViewModel Robolectric classes the glob pulls in)
                // loads a large amount of per-class state. Gradle's default
                // `forkEvery = 0` keeps EVERY app test class in ONE long-lived
                // worker JVM, so that heap/metaspace piles up across the whole
                // run and OOMs the fork when the render-heal glob (or the whole
                // module) runs together — narrow single-class runs stayed fine.
                // Bound it two ways, both well under the 7 GB hosted runner
                // (daemon -Xmx2048m + this fork ≈ 3.5 GB peak):
                //   - an explicit, CI-budget-safe worker heap, and
                //   - a finite `forkEvery` so the worker JVM is recycled every
                //     N test classes and per-class Robolectric metaspace can't
                //     accumulate unbounded. forkEvery=100 survives the exact
                //     metaspace level that OOMs the default single fork while
                //     costing only a few worker restarts (~+25s on this module).
                // Both stay overridable for local repro/tuning.
                test.maxHeapSize = providers.gradleProperty("pocketshell.test.maxHeap")
                    .orElse("1536m").get()
                test.setForkEvery(
                    providers.gradleProperty("pocketshell.test.forkEvery")
                        .orElse("100").get().toLong()
                )

                val apiVersion = providers.gradleProperty("api.version")
                    .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
                    .get()
                test.systemProperty("api.version", apiVersion)

                // Real provider calls are local, opt-in evidence only.
                // They live in src/test for access to app-internal
                // assistant classes, but the standard unit-test suite must
                // never execute them. Use :app:realLlmTest explicitly.
                test.exclude("**/*RealLlmTest.class")

                // Docker-backed proof tests are compiled with the same
                // source set but run under :app:integrationTest, matching
                // the shared modules' Testcontainers split. Keeping them
                // out of ./gradlew test stabilizes the JVM unit workflow
                // without dropping coverage.
                test.exclude("**/*IntegrationTest.class")
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
    // Issue #129: live camera scanner for QR-import. Brings a CameraX-
    // based scanner Activity + a Compose-friendly `DecoratedBarcodeView`
    // on top of the existing `com.google.zxing:core` decoder.
    implementation(libs.zxing.android.embedded)

    // Firebase Cloud Messaging (issue #690). Google Play Services holds the
    // push connection, so this is the D21-safe (no app-side background work)
    // push-delivery path. The SDK compiles + the messaging service is on the
    // classpath whether or not `google-services.json` is present; an actual
    // push only flows once the maintainer configures a Firebase project.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Issue #714: real YAML serializer for the file-viewer review export
    // (`pocketshell_review` schema). YAML, not hand-rolled string concat —
    // comment text + code lines contain colons/quotes/`#`/newlines, and
    // multi-line `text` must emit as a block scalar. snakeyaml is pure Java
    // with no transitive surface.
    implementation(libs.snakeyaml)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    // Issue #161: `ProcessLifecycleOwner` for the no-background-work
    // principle — `UsageScheduler` pauses its polling loop when the
    // whole process is in `STOPPED`.
    implementation(libs.lifecycle.process)

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

    // Issue #265: provider-agnostic LLM client layer for the in-app action
    // assistant (#266). Exposes AssistantLlmClient + AnthropicLlmClient /
    // OpenAiLlmClient, the factory, and the KeyStore-backed config store.
    // OkHttp + coroutines come transitively via the module's `api` config.
    implementation(project(":shared:core-assistant"))

    // Issue #45: tmux control-mode client (TmuxClient + TmuxClientFactory)
    // for the per-pane Compose surface introduced under
    // `com.pocketshell.app.tmux`. core-tmux declares its core-ssh dep as
    // `implementation`; the app module already brings core-ssh on its own
    // (above) so the SshSession the ViewModel hands to TmuxClientFactory
    // resolves on the same classpath.
    implementation(project(":shared:core-tmux"))

    // EPIC #687 (Phase-2, slice 1c): the pure-JVM connection-lifecycle state
    // machine (`ConnectionController` + the `Clock`/`TransportPort`/`TmuxPort`
    // ports + the `ConnectionState`→indicator projection). The VM mints the
    // controller from its existing `SshLeaseManager` + `TmuxClient`
    // collaborators via the production adapters in
    // `com.pocketshell.app.tmux.connection`. core-connection is pure Kotlin
    // (coroutines only, no `android.*`), so there is no transitive surface to
    // manage.
    implementation(project(":shared:core-connection"))

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
    // Issue #1048: the ONE audited shared de-flake settle-pump
    // (`drainMainLooperUntil`) the tmux/composer wall-clock pumps converge on.
    testImplementation(project(":shared:test-support"))
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
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestRuntimeOnly(libs.slf4j.nop)
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.packaging?.resources?.excludes?.addAll(
            duplicateJavaResourceExcludes
        )
    }
}

project.afterEvaluate {
    tasks.register<Test>("realLlmTest") {
        group = "verification"
        description = "Runs opt-in real LLM assistant action-planning tests (requires repo .env credentials)."

        val unitTest = tasks.named<Test>("testDebugUnitTest").get()
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath

        useJUnit()
        include("**/*RealLlmTest.class")
        shouldRunAfter(unitTest)
        systemProperty("pocketshell.realLlm.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs app Testcontainers-backed integration tests (requires Docker)."

        val unitTest = tasks.named<Test>("testReleaseUnitTest").get()
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath

        useJUnit()
        include("**/*IntegrationTest.class")

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }

        val apiVersion = providers.gradleProperty("api.version")
            .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
            .get()
        systemProperty("api.version", apiVersion)
    }
}
