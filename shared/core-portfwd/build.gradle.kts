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
    namespace = "com.pocketshell.core.portfwd"
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
    // exclude them. Same shape as core-ssh.
    sourceSets {
        getByName("test") {
            java.srcDir("src/integrationTest/java")
        }
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
    // Public API is built on top of SshSession / SshPortForward from core-ssh.
    api(project(":shared:core-ssh"))

    // Coroutines drive the periodic scan loop and the public Flow<TunnelInfo>
    // surface. Already exposed transitively via core-ssh's `api` dep, but
    // declared explicitly so this module's intent is self-documenting.
    implementation(libs.kotlinx.coroutines.core)

    // Host-JVM unit + integration tests. The integration-test source set is
    // attached to `test` above, so the same configuration covers both.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    // sshj needs a logger at test time when we drive a real session through
    // the container; reuse the nop binding from core-ssh.
    testRuntimeOnly(libs.slf4j.nop)
}

// Issue #41: dedicated task for the Docker-driven integration tests. Mirrors
// the core-ssh setup — reuses the compiled output and classpath of
// `testReleaseUnitTest`, filters to *IntegrationTest classes, and is *not*
// wired into `check` so contributors without Docker can still run
// `./gradlew check` locally.
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

        val apiVersion = providers.gradleProperty("api.version")
            .orElse(System.getenv("DOCKER_API_VERSION") ?: "1.45")
            .get()
        systemProperty("api.version", apiVersion)
    }
}
