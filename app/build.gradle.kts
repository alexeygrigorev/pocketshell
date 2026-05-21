plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pocketshell.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketshell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            // sshj pulls in BouncyCastle (bcpkix-jdk18on, bcprov-jdk18on,
            // bcutil-jdk18on) and jspecify, all of which ship a copy of
            // `META-INF/versions/9/OSGI-INF/MANIFEST.MF`. AGP's default
            // packaging merger refuses duplicates here — picking any one is
            // safe since OSGI manifests are descriptive, not behavioural,
            // for our (non-OSGI) use. Same goes for any duplicate
            // `META-INF/INDEX.LIST` entries that BC sometimes carries.
            excludes += listOf(
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Phase 0 proof-of-life (issue #9): pull in the SSH layer and the
    // vendored Termux surface so the app can render a real remote shell.
    // `core-ssh` exposes its `sshj` dependency transitively (`api`); we
    // call `client.startSession().startShell()` on the underlying
    // `SSHClient` directly from `ProofOfLifeScreen`, so we also need its
    // types on the compile classpath.
    implementation(project(":shared:core-ssh"))
    implementation(project(":shared:core-terminal"))

    // ProofPipelineTest connects to the `pocketshell-test:ssh` Docker
    // container the same way `core-ssh`'s integration test does, so the
    // app needs the same Testcontainers + JUnit + coroutines-test stack.
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
}
