plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

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
    namespace = "com.pocketshell.core.ssh"
    compileSdk = 36

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

    // Issue #41: integration tests (Testcontainers, Docker-driven) live in
    // a sibling directory to the pure unit tests so they can be split out
    // into a separate Gradle task. We attach the directory to the `test`
    // source set here so AGP compiles them together against the same
    // classpath, but the `integrationTest` task below restricts execution
    // to the *IntegrationTest classes, and the standard unit-test tasks
    // exclude them.
    sourceSets {
        getByName("test") {
            java.srcDir("src/integrationTest/java")
        }
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

                // Issue #41: keep the standard unit-test tasks fast and
                // Docker-free by excluding the Testcontainers-backed
                // integration tests. They run under the dedicated
                // `integrationTest` task instead.
                test.exclude("**/*IntegrationTest.class")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.packaging?.resources?.excludes?.addAll(
            duplicateJavaResourceExcludes
        )
    }
}

dependencies {
    // sshj is the SSH client (replaces JSch per D3). It requires an slf4j
    // backend at runtime; slf4j-nop is the no-op binding so we don't pull in
    // logback or log4j on Android.
    api(libs.sshj)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    runtimeOnly(libs.slf4j.nop)

    // Coroutines are part of the public surface (SshSession.tail returns
    // kotlinx.coroutines.Job). `api` so downstream modules don't need to
    // re-declare it.
    api(libs.kotlinx.coroutines.core)

    // Unit + integration tests run on the host JVM via the standard
    // `testImplementation` configuration. The integration-test source set
    // is attached to `test` above, so the same configuration covers both.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    // sshj needs a logger at test time too; reuse the nop binding.
    testRuntimeOnly(libs.slf4j.nop)
}

// Issue #41: a dedicated task for the Docker-driven integration tests.
// Reuses the compiled output and classpath of the `testReleaseUnitTest`
// task — same JVM, same dependencies — but only runs *IntegrationTest
// classes and is *not* wired into `check`, so contributors without Docker
// can still run `./gradlew check` locally. CI runs it explicitly via the
// `integration` job in `.github/workflows/tests.yml`.
project.afterEvaluate {
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs Testcontainers-backed integration tests (requires Docker)."

        // Reuse the same compiled test classes + classpath that AGP wires
        // up for the release unit-test variant. We register the task
        // inside `afterEvaluate` so the `testReleaseUnitTest` task exists
        // at the point we read its `testClassesDirs` / `classpath` —
        // AGP creates the variant Test tasks during its own
        // afterEvaluate hook. Reading those FileCollections carries
        // dependencies on the compile / process tasks (not on the
        // `testReleaseUnitTest` task itself), so running
        // `integrationTest` does NOT re-run the unit tests.
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
