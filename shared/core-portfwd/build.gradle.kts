plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.core.portfwd"
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
        // Same shape as core-ssh: integration tests run on the host JVM via
        // Testcontainers, so we need access to the real JRE filesystem +
        // networking instead of the mocked Android stubs AGP gives by
        // default. See core-ssh/build.gradle.kts for the rationale on the
        // Docker API version pin.
        unitTests {
            isIncludeAndroidResources = false
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
    // Public API is built on top of SshSession / SshPortForward from core-ssh.
    api(project(":shared:core-ssh"))

    // Coroutines drive the periodic scan loop and the public Flow<TunnelInfo>
    // surface. Already exposed transitively via core-ssh's `api` dep, but
    // declared explicitly so this module's intent is self-documenting.
    implementation(libs.kotlinx.coroutines.core)

    // Host-JVM unit + integration tests.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    // sshj needs a logger at test time when we drive a real session through
    // the container; reuse the nop binding from core-ssh.
    testRuntimeOnly(libs.slf4j.nop)
}
