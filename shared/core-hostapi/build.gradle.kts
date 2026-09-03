plugins {
    // The FIRST pure-JVM module in this repo (rewrite plan
    // docs/_scratch/simplification-implementation-plan-2026-09-02.md §C.2,
    // task K-1). Every other `shared/core-*` module is an Android library;
    // this one deliberately is not — it holds the host-CLI wire contract
    // (JSON shapes emitted by `pocketshell ... --json`), which has no Android
    // surface at all. Keeping it off the Android plugin makes the constraint
    // structural rather than a code-review convention: an `androidx` import
    // here would not even compile.
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    // Java 17 matches every Android module's `compileOptions` in this repo.
    // Deliberately NOT `kotlin { jvmToolchain(17) }`: the only JDK installed
    // on the build machines is 21, so a toolchain request would force Gradle
    // to provision/download a second JDK. Source/target 17 gets the same
    // bytecode level from the JDK already in use.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The only production dependency: the JSON parser. No coroutines artifact
    // is needed — `RemoteExec.exec` is a `suspend fun`, and suspension itself
    // lives in kotlin-stdlib (`kotlin.coroutines.Continuation`); nothing in
    // this module touches a Flow/Deferred/Dispatcher. A later task that
    // actually needs those adds the dependency then.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
