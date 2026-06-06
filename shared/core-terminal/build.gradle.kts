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
// commit pin, refresh procedure, and the per-Compose-adapter (#8) note about
// the native `libtermux.so`.
//
// Source files keep their upstream package names (`com.termux.terminal.*` and
// `com.termux.view.*`) per the issue's "do not refactor" non-goal. This module
// only re-exports them; downstream callers depend on the Termux APIs directly.
android {
    // Namespace is set to upstream Termux's `terminal-view` namespace
    // (`com.termux.view`) — NOT to `com.pocketshell.core.terminal`. Reason: the
    // vendored sources import `com.termux.view.R` for drawables/strings, and
    // the R class is generated under whatever namespace the module declares.
    // Picking the upstream namespace keeps the vendored source byte-identical
    // to upstream, which is the goal of issue #7. See VENDORED.md.
    namespace = "com.termux.view"
    compileSdk = 35

    defaultConfig {
        // PocketShell's min SDK (26) is higher than upstream Termux's, so all
        // upstream Android API calls work unchanged.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Issue #9: a stub `libtermux.so` ships in the AAR so the vendored
        // `com.termux.terminal.JNI` static initializer (`System.loadLibrary`)
        // does not throw `UnsatisfiedLinkError`. Limit the ABIs to the
        // emulator-friendly set plus arm64; the stub is tiny (<10 KB per ABI)
        // so the APK overhead is negligible. See `src/main/cpp/CMakeLists.txt`
        // for the rationale.
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    // Wire CMake to build the stub `libtermux.so` from `src/main/cpp/`. The
    // upstream `src/main/jni/` C source is left untouched on disk (refresh
    // parity, see VENDORED.md) — we deliberately do NOT compile it. The
    // PocketShell stub at `src/main/cpp/pocketshell_termux_stub.c` provides
    // safe no-ops for the four `JNI.*` native methods so that
    // `TerminalSession.updateSize` (which calls `JNI.setPtyWindowSize` on
    // every layout change once an emulator is attached) does not crash.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        // Exclude the upstream JNI C sources from the build. The upstream code
        // at `src/main/jni/termux.c` spawns a *local* PTY subprocess — not
        // what PocketShell needs (we render remote SSH streams). Issue #9
        // replaces it with a stub `libtermux.so` built from
        // `src/main/cpp/pocketshell_termux_stub.c` via `externalNativeBuild`
        // (see the cmake block above and `src/main/cpp/CMakeLists.txt`).
        //
        // The upstream `src/main/jni/` tree is retained on disk only as a
        // refresh-tracking copy of upstream (VENDORED.md "Refresh procedure")
        // — `externalNativeBuild` points at the stub CMake project directly,
        // so the deprecated `jni` source-set DSL is intentionally unused here.
        // `jniLibs { srcDirs() }` is cleared so AGP only picks up the `.so`
        // files produced by the cmake build, not any stray pre-built libraries
        // that might be dropped under `src/main/jniLibs/`.
        named("main") {
            jniLibs {
                srcDirs()
            }
        }
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
