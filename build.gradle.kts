// Root build script for PocketShell. Plugins used by any module are declared
// here with `apply false` per Gradle convention, so versions resolve once at
// the root and modules apply what they need. Mirrors
// ssh-auto-forward-android's setup.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Rewrite task K-1: shared/core-hostapi is a pure-JVM module (no Android
    // SDK), so it applies kotlin("jvm") + the serialization compiler plugin
    // rather than the Android library pair above.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // Roborazzi (issue #555): fast JVM screenshot rendering for design iteration.
    alias(libs.plugins.roborazzi) apply false
    // Firebase Cloud Messaging (issue #690): declared here so the version
    // resolves once; `app/build.gradle.kts` applies it only when a
    // `google-services.json` is present (so the build passes without a
    // configured Firebase project).
    alias(libs.plugins.google.services) apply false
}

// Issue #2172: sshj 0.40.0 → bcpkix-jdk18on:1.80 declares
// bcutil-jdk18on:[1.80,1.81). A dynamic range cannot be satisfied from a
// local cache alone — Gradle HEADs maven-metadata.xml on every resolve,
// which is the traffic pattern that 429s Maven Central before any test
// runs.
//
// Constraints pin every BC sibling to the exact version resolution
// selects today, applied on every Android module so the range cannot
// sneak in through a second classpath (app / portfwd / tmux / tests).
// failOnDynamicVersions() is the reproduce-first / stay-red gate: if
// that range (or any sibling range) still participates, resolution
// fails closed instead of rolling the network dice.
fun Project.pinBouncyCastleTransitives() {
    val pins = listOf(
        libs.bouncycastle.bcutil,
        libs.bouncycastle.bcpkix,
        libs.bouncycastle.bcprov,
    )
    val configNames = listOf(
        "implementation",
        "api",
        "testImplementation",
        "androidTestImplementation",
    ).filter { configurations.findByName(it) != null }
    dependencies {
        constraints {
            configNames.forEach { configName ->
                pins.forEach { pin ->
                    add(configName, pin)
                }
            }
        }
    }
}

subprojects {
    configurations.configureEach {
        val isClasspath = name.endsWith("RuntimeClasspath") || name.endsWith("CompileClasspath")
        if (isClasspath) {
            resolutionStrategy.failOnDynamicVersions()
        }
    }
    pluginManager.withPlugin("com.android.library") {
        pinBouncyCastleTransitives()
    }
    pluginManager.withPlugin("com.android.application") {
        pinBouncyCastleTransitives()
    }
}

// Exact versions resolution selected on #2172. A catalog bump that is
// not also reflected here is an upgrade smuggled in as a pin.
val expectedBouncyCastlePins = mapOf(
    "org.bouncycastle:bcutil-jdk18on" to "1.80.2",
    "org.bouncycastle:bcpkix-jdk18on" to "1.80",
    "org.bouncycastle:bcprov-jdk18on" to "1.80.2",
)

// app/, core-ssh, core-tmux were deleted and core-portfwd shelved in the
// "stable" rewrite branch (docs/_scratch/simplification-implementation-plan-2026-09-02.md).
// sshj now lives in shared/core-transport (task T-1), so the #2172 resolution
// assertion has a real graph to inspect again; app2 / core-portfwd rejoin this
// list as they are rewired onto core-transport.
val sshjClasspathGraphs = listOf(
    ":shared:core-transport" to "releaseRuntimeClasspath",
    ":shared:core-transport" to "releaseUnitTestRuntimeClasspath",
)

tasks.register("assertNoDynamicDependencyVersions") {
    group = "verification"
    description =
        "Fails if a dynamic version still participates in sshj/BouncyCastle resolution (#2172)."
    doLast {
        val errors = mutableListOf<String>()

        val catalogBcutil = libs.versions.bouncycastle.bcutil.get()
        val catalogBcpkix = libs.versions.bouncycastle.bcpkix.get()
        val catalogBcprov = libs.versions.bouncycastle.bcprov.get()
        if (catalogBcutil != expectedBouncyCastlePins.getValue("org.bouncycastle:bcutil-jdk18on")) {
            errors += "catalog bouncycastle-bcutil=$catalogBcutil, expected ${expectedBouncyCastlePins["org.bouncycastle:bcutil-jdk18on"]}"
        }
        if (catalogBcpkix != expectedBouncyCastlePins.getValue("org.bouncycastle:bcpkix-jdk18on")) {
            errors += "catalog bouncycastle-bcpkix=$catalogBcpkix, expected ${expectedBouncyCastlePins["org.bouncycastle:bcpkix-jdk18on"]}"
        }
        if (catalogBcprov != expectedBouncyCastlePins.getValue("org.bouncycastle:bcprov-jdk18on")) {
            errors += "catalog bouncycastle-bcprov=$catalogBcprov, expected ${expectedBouncyCastlePins["org.bouncycastle:bcprov-jdk18on"]}"
        }

        val testsYml = rootProject.file(".github/workflows/tests.yml").readText()
        if (!testsYml.contains("Assert tests actually executed (issue #1646)")) {
            errors += ".github/workflows/tests.yml no longer has the #1646 executed-test guard"
        }

        // Without these, a resolve from a warm cache still selects 1.80.2
        // and this task would stay green over a range that 429s on a cold
        // runner. Deleting the gate or the pin must redden the assertion.
        val rootScript = rootProject.buildFile.readText()
        if (!rootScript.contains("failOnDynamicVersions()")) {
            errors += "root build.gradle.kts no longer calls failOnDynamicVersions()"
        }
        if (!rootScript.contains("pinBouncyCastleTransitives")) {
            errors += "root build.gradle.kts no longer applies pinBouncyCastleTransitives"
        }
        // shared/core-ssh was deleted in the "stable" rewrite branch; sshj's pin
        // site moves to shared/core-transport (task T-1/T-2). Check it there
        // once it exists, skip until then rather than fail on a missing file.
        val coreTransportBuildFile = rootProject.file("shared/core-transport/build.gradle.kts")
        if (coreTransportBuildFile.exists() &&
            !coreTransportBuildFile.readText().contains("libs.bouncycastle.bcutil")
        ) {
            errors += "shared/core-transport/build.gradle.kts no longer constrains bcutil"
        }

        sshjClasspathGraphs.forEach { (path, configName) ->
            val cfg = project(path).configurations.findByName(configName)
            if (cfg == null) {
                errors += "$path:$configName does not exist"
                return@forEach
            }
            val result = cfg.incoming.resolutionResult
            result.allDependencies.forEach { dep ->
                if (dep is org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                    errors += "$path:$configName unresolved ${dep.requested}: ${dep.failure.message}"
                }
            }
            val selected = result.allComponents
                .map { it.id }
                .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                .associate { "${it.group}:${it.module}" to it.version }
            expectedBouncyCastlePins.forEach { (coord, expected) ->
                val actual = selected[coord]
                if (actual == null) {
                    errors += "$path:$configName missing $coord"
                } else if (actual != expected) {
                    errors += "$path:$configName $coord selected $actual, expected $expected (no-upgrade pin)"
                }
            }
        }

        check(errors.isEmpty()) {
            "Issue #2172: dynamic version still participates in resolution, or the pin drifted:\n" +
                errors.joinToString("\n")
        }
        logger.lifecycle(
            "assertNoDynamicDependencyVersions: OK — BC pins $expectedBouncyCastlePins " +
                "and no dynamic versions on inspected classpaths",
        )
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        dependsOn(rootProject.tasks.named("assertNoDynamicDependencyVersions"))
    }
}
