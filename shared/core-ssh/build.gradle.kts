plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.core.ssh"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Testcontainers + JUnit live in src/test (host JVM), not in
        // androidTest. We keep the standard test runner declared for parity
        // with the other shared modules.
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
        // Unit tests run as real host-JVM tests (not Robolectric). They use
        // Testcontainers, which talks to the local Docker daemon, so they
        // need access to the actual JRE filesystem + networking — not the
        // mocked Android stubs AGP gives by default.
        unitTests {
            isIncludeAndroidResources = false
            all { test ->
                test.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = true
                }
                // Pin the Docker API version negotiated by docker-java to
                // something modern. The bundled docker-java in
                // Testcontainers 1.21.x defaults to API 1.32, which the
                // daemon (Docker 25+) refuses — minimum is 1.44.
                // docker-java reads the `api.version` system property (NOT
                // the DOCKER_API_VERSION env var, despite the name); we
                // set it explicitly so tests work out-of-the-box on
                // current Docker installs. Override via -Papi.version=...
                // on the command line if needed.
                val apiVersion = providers.gradleProperty("api.version")
                    .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
                    .get()
                test.systemProperty("api.version", apiVersion)
            }
        }
    }
}

dependencies {
    // sshj is the SSH client (replaces JSch per D3). It requires an slf4j
    // backend at runtime; slf4j-nop is the no-op binding so we don't pull in
    // logback or log4j on Android.
    api(libs.sshj)
    runtimeOnly(libs.slf4j.nop)

    // Coroutines are part of the public surface (SshSession.tail returns
    // kotlinx.coroutines.Job). `api` so downstream modules don't need to
    // re-declare it.
    api(libs.kotlinx.coroutines.core)

    // Unit + integration tests run on the host JVM via the standard
    // `testImplementation` configuration.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    // sshj needs a logger at test time too; reuse the nop binding.
    testRuntimeOnly(libs.slf4j.nop)
}
