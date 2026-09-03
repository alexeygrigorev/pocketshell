plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The ONE module that knows sshj (rewrite plan
// docs/_scratch/simplification-implementation-plan-2026-09-02.md §A.3). Task
// T-1 lands only the contract — value types, interfaces, and a scripted fake
// for consumers' tests. The sshj-backed implementation (RealHostConnection /
// RealHostConnectionFactory / PtyChannelImpl / SftpChannelImpl) and the
// Docker-sshd `integrationTest` source set arrive in T-2..T-5; the sshj
// dependency is declared here now so the module's dependency graph (and the
// #2172 BouncyCastle pin below) is in place from the start.
android {
    namespace = "com.pocketshell.core.transport"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // Unit tests live in src/test (host JVM). The runner is declared for
        // parity with the other shared modules.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AGP 8.9 syntax for test fixtures on an Android library. Publishes a
    // `testFixtures` component so other modules can consume the scripted
    // FakeHostConnection via `testImplementation(testFixtures(project(":shared:core-transport")))`
    // without it ever reaching a shipped APK.
    testFixtures {
        enable = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
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
    // sshj is the SSH client (D3). It needs an slf4j backend at runtime;
    // slf4j-nop is the no-op binding so we don't pull logback/log4j onto
    // Android. `api` because the T-2 implementation surfaces sshj types to
    // the integration tests in this module.
    api(libs.sshj)
    implementation(libs.bouncycastle.bcprov)
    // Issue #2172: sshj 0.40.0 → bcpkix-jdk18on:1.80 declares
    // bcutil-jdk18on:[1.80,1.81). Pin every BC sibling to the exact version
    // resolution selects today so a normal resolve needs no
    // maven-metadata.xml fetch. The root `subprojects` block repeats the same
    // constraints on every Android module so a second path cannot revive the
    // range; keeping them here too means this module is self-contained if the
    // root helper ever changes.
    constraints {
        api(libs.bouncycastle.bcutil)
        api(libs.bouncycastle.bcpkix)
        api(libs.bouncycastle.bcprov)
        testImplementation(libs.bouncycastle.bcutil)
        testImplementation(libs.bouncycastle.bcpkix)
        testImplementation(libs.bouncycastle.bcprov)
    }
    runtimeOnly(libs.slf4j.nop)

    // Coroutines are part of the public surface (HostConnection.state is a
    // StateFlow, PtyChannel.output is a Flow, PtyChannel.exit is a Deferred).
    // `api` so downstream modules don't re-declare it.
    api(libs.kotlinx.coroutines.core)

    // The scripted fake is a testFixtures-only artifact; it uses the same
    // coroutines primitives as the main source set.
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // sshj wants a logger at test time too; reuse the nop binding.
    testRuntimeOnly(libs.slf4j.nop)
}
