plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Issue #8: the Compose adapter (TerminalSurface) lives in this module
    // alongside the vendored Java sources. Enabling the Kotlin Compose
    // compiler plugin here lets us host TerminalView via AndroidView interop
    // without spawning a separate module just for the wrapper.
    alias(libs.plugins.kotlin.compose)
}

// This module vendors Termux's `terminal-emulator` + `terminal-view` libraries
// (Apache-2.0 per upstream LICENSE.md — these two libs are explicitly carved
// out from termux-app's GPLv3 umbrella). See VENDORED.md for the upstream
// commit pin and refresh procedure, and PATCHES.md for every local deviation.
//
// Source files keep their upstream package names (`com.termux.terminal.*` and
// `com.termux.view.*`) per the issue's "do not refactor" non-goal. This module
// only re-exports them; downstream callers depend on the Termux APIs directly.
//
// `com.termux.terminal.TerminalSession` is the ONE exception: it is
// PocketShell's own remote-only class (issue #2566), not vendored code, and is
// never refreshed from upstream. Replacing it removed the local-pty machinery
// this module used to carry — `ByteQueue`, `JNI`, the upstream `src/main/jni/`
// C sources and the stub `libtermux.so` built from `src/main/cpp/` — so there
// is no `externalNativeBuild` here any more and the module needs no NDK or
// CMake to build.
android {
    // Namespace is set to upstream Termux's `terminal-view` namespace
    // (`com.termux.view`) — NOT to `com.pocketshell.core.terminal`. Reason: the
    // vendored sources import `com.termux.view.R` for drawables/strings, and
    // the R class is generated under whatever namespace the module declares.
    // Picking the upstream namespace keeps the vendored source byte-identical
    // to upstream, which is the goal of issue #7. See VENDORED.md.
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        // PocketShell's min SDK (26) is higher than upstream Termux's, so all
        // upstream Android API calls work unchanged.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Gate hardening (v0.4.19 release-gate hang): AndroidJUnitRunner applies
        // `timeout_msec` as a PER-TEST timeout, so a single wedged connected test
        // FAILS FAST instead of consuming the whole stage budget. The v0.4.19
        // pre-release confidence gate stage [12] (this module's
        // connectedDebugAndroidTest) wedged the entire 3h ceiling THREE times when
        // one test deadlocked at ~9/45 — none of the heavy proof tests here carry
        // their own @Test(timeout), so there was no backstop. 180 s is far above
        // the slowest legitimate test in this suite (the #796 keyboard-up burst is
        // a 6 s burst + settle; ~tens of seconds worst case on a contended
        // swiftshader emulator) yet turns a future hang into a ~3 min failure
        // instead of a 3 h gate wedge.
        testInstrumentationRunnerArguments["timeout_msec"] = "180000"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Upstream Termux relies on default Android stub return values for
        // unit tests (e.g. `Log.i` returns 0). Mirror that behaviour so the
        // vendored unit tests can run on the host JVM.
        unitTests.isReturnDefaultValues = true
    }

    // Issue #8: the Compose adapter under `com.pocketshell.core.terminal.ui`
    // needs the Compose compiler. The vendored Java sources do not use
    // Compose; only the Kotlin adapter does.
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Upstream Termux declares this in both subprojects. Matches the version
    // pinned at the recorded upstream commit (see VENDORED.md). Kept on `api`
    // because the vendored sources use `@NonNull` / `@Nullable` annotations on
    // public signatures.
    api(libs.androidx.annotation)

    // Compose adapter (issue #8). The BOM keeps the rest of the Compose
    // artifacts aligned with the version chosen in the version catalog. The
    // `compose-ui` dependency is `api` because `TerminalSurface` exposes
    // `androidx.compose.ui.Modifier` on its public signature — downstream
    // callers must see it on the classpath.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // TerminalSurfaceState exposes `kotlinx.coroutines.flow.SharedFlow` on
    // its public surface; declare the dep as `api` so downstream collectors
    // get the type on their classpath without re-declaring it. Compose runs
    // on coroutines so the artifact is present transitively, but we declare
    // it explicitly because we rely on it as a public API.
    api(libs.kotlinx.coroutines.core)

    // Vendored unit tests from Termux's `terminal-emulator/src/test`.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Issue #796: virtual-clock contract test for the render-frame coalescer
    // (RenderFrameCoalescerTest) — runTest + advanceTimeBy, the same harness
    // LayoutChangeCoalescerTest uses in core-tmux.
    testImplementation(libs.kotlinx.coroutines.test)
    // Issue #1048: the ONE audited shared de-flake settle-pump
    // (`drainMainLooperUntil`) the SshTerminalBridge flood pump converges on.
    // Issue #2206: the same module also hosts `captureViewToBitmap`, the
    // hard-fail 0x0 viewport capture the leftover core-terminal androidTests
    // now share (they cannot import the app-module #2135 helper).
    testImplementation(project(":shared:test-support"))
    androidTestImplementation(project(":shared:test-support"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    // Issue #175 — the round-2 connected coverage closes the reviewer's
    // AC2/AC5/AC6/AC7 gaps. We host TerminalSurface on a real
    // ComponentActivity via createAndroidComposeRule (compose-ui-test-junit4)
    // so the DisposableEffect that wires the system ClipboardManager actually
    // runs, and we assert the URL-tap path fires Intent.ACTION_VIEW via
    // espresso-intents instead of a recording callback. compose-ui-test
    // requires the manifest companion (so the implicit empty Activity used
    // by `createAndroidComposeRule` is registered).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.activity)
}
