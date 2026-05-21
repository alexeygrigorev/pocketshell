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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        // Exclude the upstream JNI C sources from the build — we do not yet
        // ship `libtermux.so` (see VENDORED.md "JNI handling"). The files are
        // retained on disk under `src/main/jni/` only as a refresh-tracking
        // copy of upstream; they are NOT compiled into the AAR. Issue #9
        // owns the eventual JNI build wiring.
        named("main") {
            jni {
                srcDirs()
            }
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
}
