plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pocketshell.core.tmux"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Testcontainers + JUnit live in src/test (host JVM), not in
        // androidTest. We keep the standard test runner declared for
        // parity with the other shared modules.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Issue #41 (pattern established in core-ssh / core-portfwd): the
    // Testcontainers-backed integration tests live in a sibling directory
    // to the pure unit tests so they can be split out into a separate
    // Gradle task. Attach the directory to the `test` source set so AGP
    // compiles them together against the same classpath, but the
    // `integrationTest` task below restricts execution to the
    // *IntegrationTest classes, and the standard unit-test tasks exclude
    // them.
    sourceSets {
        getByName("test") {
            java.srcDir("src/integrationTest/java")
        }
    }

    testOptions {
        // Unit tests run as real host-JVM tests (not Robolectric). The
        // integration tests use Testcontainers, which talks to the local
        // Docker daemon, so they need access to the actual JRE filesystem
        // + networking — not the mocked Android stubs AGP gives by
        // default. See core-ssh/build.gradle.kts for the rationale on the
        // Docker API version pin.
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
                // daemon (Docker 25+) refuses — minimum is 1.44. We set
                // 1.45 to match the rest of the shared modules. Override
                // via -Papi.version=... on the command line if needed.
                val apiVersion = providers.gradleProperty("api.version")
                    .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
                    .get()
                test.systemProperty("api.version", apiVersion)

                // Keep the standard unit-test tasks fast and Docker-free
                // by excluding the Testcontainers-backed integration
                // tests. They run under the dedicated `integrationTest`
                // task instead.
                test.exclude("**/*IntegrationTest.class")
            }
        }
    }
}

dependencies {
    // The TmuxClient API consumes an SshSession from core-ssh and writes
    // bytes through its shell channel. `implementation` (not `api`)
    // because SshSession is on the public surface of TmuxClientFactory,
    // not on the TmuxClient interface itself — downstream modules that
    // *use* a TmuxClient don't need core-ssh on their classpath, but the
    // ones that *construct* one do, and they'll declare core-ssh
    // themselves.
    //
    // Per issue #44 acceptance criteria the wiring spec is
    // `implementation(project(":shared:core-ssh"))`.
    implementation(project(":shared:core-ssh"))

    // Coroutines power `ControlEventStream` (the parser stream) and the
    // public `Flow<ControlEvent>` surface on TmuxClient. Exposed via
    // `api` because `Flow` types appear on the module's public surface.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    // sshj needs an slf4j backend at test time when the integration test
    // drives a real session through the container; reuse the nop binding
    // from core-ssh.
    testRuntimeOnly(libs.slf4j.nop)
}

// Issue #41 pattern: dedicated task for the Docker-driven integration
// tests. Mirrors the core-ssh / core-portfwd setup — reuses the compiled
// output and classpath of `testReleaseUnitTest`, filters to
// *IntegrationTest classes, and is NOT wired into `check` so contributors
// without Docker can still run `./gradlew check` locally.
project.afterEvaluate {
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs Testcontainers-backed integration tests (requires Docker)."

        // Reuse the same compiled test classes + classpath that AGP wires
        // up for the release unit-test variant. We register the task
        // inside `afterEvaluate` so the `testReleaseUnitTest` task exists
        // at the point we read its `testClassesDirs` / `classpath` — AGP
        // creates the variant Test tasks during its own afterEvaluate
        // hook. Reading those FileCollections carries dependencies on the
        // compile / process tasks (not on the `testReleaseUnitTest` task
        // itself), so running `integrationTest` does NOT re-run the unit
        // tests.
        val unitTest = tasks.named<Test>("testReleaseUnitTest").get()
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath

        useJUnit()
        include("**/*IntegrationTest.class")

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }

        // Same Docker API pin as the unit-test config above — the
        // integration tests are the actual consumer of this knob.
        val apiVersion = providers.gradleProperty("api.version")
            .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
            .get()
        systemProperty("api.version", apiVersion)
    }
}
