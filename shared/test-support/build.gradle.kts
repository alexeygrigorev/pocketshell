plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Test-only support module (issue #1048). It is consumed exclusively as a
// `testImplementation` dependency by other modules' unit-test source sets, so
// nothing here ever ships into the app APK. It holds the ONE audited de-flake
// settle-pump (`drainMainLooperUntil`) shared across modules so the historically
// drifting hand-rolled wall-clock pumps converge on a single, reviewed boundary.
//
// The pump itself is pure JVM (Long deadlines + lambdas) — it deliberately does
// NOT depend on Robolectric or kotlinx-coroutines-test. The per-tick drain
// (looper idle / `runCurrent()`) is injected by each caller, which already has
// those libraries on its own test classpath. This keeps the genuinely different
// drains (issue #1110 must-NOT-idle vs issue #803 must-idle vs the non-`runTest`
// bridge test) honest while sharing the bounded-deadline core.
android {
    namespace = "com.pocketshell.testsupport"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
