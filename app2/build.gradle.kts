// app2 — the rewrite's application module (task M-1 of
// docs/_scratch/simplification-implementation-plan-2026-09-02.md).
//
// Deliberately small: this is the composition root for the new client, and the
// plan's whole premise is that it stays thin glue over the shared modules.
// Anything the empty scaffold does not need is NOT declared here — later
// U/P tasks add dependencies alongside the code that consumes them, the same
// convention the version catalog documents.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// :shared:core-transport brings sshj, which brings BouncyCastle
// (bcpkix/bcprov/bcutil-jdk18on) and jspecify — all of which ship the same
// Java resource files, and AGP's merger refuses duplicates. Same list the
// shipping app module and :shared:core-portfwd carry; none of these metadata
// files are read at runtime. The androidTest APK is packaged separately, so it
// needs the same exclusions (applied in the variant block below).
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
    namespace = "com.pocketshell.next"
    compileSdk = 36

    // Same rationale as the old app module (issue #42): pin every build of
    // this module to the one committed debug keystore so a laptop build, a CI
    // build, and a release build all share a signing identity and upgrading an
    // existing install never trips "signatures do not match". The password is
    // the public Android debug password — the file has no security value.
    signingConfigs {
        create("debugKeystore") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        // Distinct from the old client's `com.pocketshell.app` so both can be
        // installed side by side during the rewrite. X-4 renames this to
        // `com.pocketshell.app` at cutover.
        applicationId = "com.pocketshell.next"
        minSdk = 26
        targetSdk = 35
        // Placeholder version. The tag-derived versioning (issue #2356) stays
        // with the shipping module until app2 becomes primary at cutover.
        versionCode = 1
        versionName = "0.0.0-dev"
        // Task U-2: the journey resolves the app's own Hilt singletons through
        // an `@EntryPoint` declared in androidTest. That entry point is only
        // part of a component the test APK can cast to when the app under test
        // runs `HiltTestApplication` — which is exactly what this runner
        // substitutes. The production `App` it replaces is empty beyond
        // `@HiltAndroidApp`, so nothing under test is lost.
        testInstrumentationRunner = "com.pocketshell.next.HiltNextTestRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugKeystore")

            // Issue #672 scheme, adopted for app2 by task U-2: let each parallel
            // worktree install its DEBUG apk under a distinct applicationId
            // (e.g. `com.pocketshell.next.iapp2`) so two app2 worktrees running
            // connected tests on the ONE shared emulator do not uninstall each
            // other. `scripts/connected-test.sh --suffix <token>` passes the
            // property; with no property this is byte-for-byte the plain
            // `com.pocketshell.next` build, and the release type is untouched.
            //
            // Only `[A-Za-z0-9._]` is accepted (a package-segment token) so a
            // stray value cannot produce an invalid applicationId.
            val rawSuffix = (project.findProperty("pocketshellAppIdSuffix") as String?)
                ?.trim()
                .orEmpty()
            if (rawSuffix.isNotEmpty()) {
                require(rawSuffix.matches(Regex("[A-Za-z0-9._]+"))) {
                    "pocketshellAppIdSuffix must match [A-Za-z0-9._]+ (got: '$rawSuffix')"
                }
                applicationIdSuffix = ".$rawSuffix"
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debugKeystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("androidTest") {
            // Task U-2: the J01 journey authenticates against the Docker SSH
            // fixture with `tests/docker/test_key`, so the key is packaged as an
            // androidTest asset and read via
            // `InstrumentationRegistry.getInstrumentation().context.assets`.
            // Same mechanism the pre-rewrite app module used; the key is a
            // disposable fixture credential committed to the repo, never a real
            // one.
            assets.srcDir(rootProject.file("tests/docker"))
        }
    }

    packaging {
        resources {
            excludes += duplicateJavaResourceExcludes
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest + resources to run the real
            // composition (and the real `PocketShellTheme`) on the host JVM.
            // Mirrors :shared:ui-kit's setup.
            isIncludeAndroidResources = true
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
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // The real NavHost — see the catalog entry for why app2 does not repeat
    // the old module's hand-rolled navigator.
    implementation(libs.androidx.navigation.compose)

    // `LifecycleEventEffect(ON_START)` — the session tree's refresh trigger
    // (task U-3). hilt-navigation-compose already brings
    // lifecycle-viewmodel-compose, but NOT lifecycle-runtime-compose, which is
    // where the lifecycle-event effects live.
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // `hiltViewModel()` inside a `composable {}` destination (task U-1). Also
    // supplies lifecycle-viewmodel-compose, so screens never construct a
    // ViewModel by hand.
    implementation(libs.androidx.hilt.navigation.compose)

    // Shared modules app2 builds on (plan §A.2).
    implementation(project(":shared:ui-kit"))
    implementation(project(":shared:core-storage"))
    implementation(project(":shared:core-terminal"))
    // Task M-3: the connections registry / Room trust store / secret resolver
    // implement core-transport's TrustStore + AuthSecretResolver seams and hand
    // back its HostConnection.
    implementation(project(":shared:core-transport"))
    // Task U-3: the session tree speaks the host CLI. `HostCliClient` runs over
    // a `RemoteExec` the app adapts from a `HostConnection`, which is why this
    // module and core-transport stay independent of each other.
    implementation(project(":shared:core-hostapi"))
    // Task P-4: the port-forwarding engine (scan loop, tunnel map, reconnect
    // supervisor). It hands back `core-transport` types, so it is an `api`
    // dependency there and arrives on this classpath with core-transport.
    implementation(project(":shared:core-portfwd"))

    // The nav graph is exercised as a real composition on the host JVM
    // (Robolectric + createComposeRule), the same way :shared:ui-kit tests its
    // primitives — a route pattern that `NavHost` rejects, or an argument that
    // does not survive encoding, fails in seconds without an emulator.
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    // The host-list ViewModel is exercised against a real in-memory Room
    // database (task U-1) — `runTest` + a deterministic dispatcher, no mocks.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    // Supplies the empty host Activity `createComposeRule()` launches.
    debugImplementation(libs.compose.ui.test.manifest)

    // Task M-3: the connections registry is driven on the host JVM against the
    // scripted FakeHostConnection (no sshj, no network) plus an in-memory Room
    // database, the same pattern :shared:core-storage uses for its DAO tests.
    testImplementation(testFixtures(project(":shared:core-transport")))
    testImplementation(libs.kotlinx.coroutines.test)

    // Task U-2: app2's first INSTRUMENTED tests (journey J01). The androidTest
    // component already sees the main variant's `implementation` dependencies
    // (Room, core-transport/sshj, Hilt, Compose), so only the test-only
    // artifacts are declared here — the same short list the pre-rewrite app
    // module carried, minus everything J01 does not use.
    // The journey reaches the app's OWN Hilt-provided DAOs and connections
    // registry through an `@EntryPoint` declared in androidTest, which needs
    // Hilt's processor over that source set. Seeding through the app's single
    // database instance (rather than a second Room instance over the same file)
    // is what keeps the seed and the running screen looking at one connection
    // pool; closing the app's registry between tests is what stops a cached
    // connection from making a later test pass without dialling.
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // The journey's INDEPENDENT host-key oracle dials the fixture with sshj
    // directly (sshj itself is `api` on core-transport, so it is already on this
    // classpath). BouncyCastle is only an `implementation` detail there, so the
    // androidTest source set needs its own compile-time handle to install the
    // full provider before that probe — Android ships a stripped "BC" that can
    // miss algorithms an OpenSSH server negotiates. Same pinned version the
    // transport uses; nothing new enters the version catalog.
    androidTestImplementation(libs.bouncycastle.bcprov)
    // sshj logs through slf4j. Without a binding on the androidTest classpath
    // every dial prints a "failed to load class StaticLoggerBinder" banner into
    // the instrumentation output; the no-op binding keeps the run readable.
    androidTestRuntimeOnly(libs.slf4j.nop)
}

// The androidTest APK is packaged separately from the app APK, so it needs the
// same BouncyCastle/jspecify duplicate-resource exclusions. Declared now (with
// the dependency that introduces them) rather than when the first journey test
// lands, so U-2 does not have to rediscover the failure.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.packaging?.resources?.excludes?.addAll(
            duplicateJavaResourceExcludes,
        )
    }
}
