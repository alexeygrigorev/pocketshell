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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugKeystore")
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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Shared modules app2 builds on (plan §A.2). core-hostapi is intentionally
    // still absent — nothing in app2 speaks the host CLI yet (that arrives with
    // the U-3 tree slice).
    implementation(project(":shared:ui-kit"))
    implementation(project(":shared:core-storage"))
    implementation(project(":shared:core-terminal"))
    // Task M-3: the connections registry / Room trust store / secret resolver
    // implement core-transport's TrustStore + AuthSecretResolver seams and hand
    // back its HostConnection.
    implementation(project(":shared:core-transport"))

    // The nav graph is exercised as a real composition on the host JVM
    // (Robolectric + createComposeRule), the same way :shared:ui-kit tests its
    // primitives — a route pattern that `NavHost` rejects, or an argument that
    // does not survive encoding, fails in seconds without an emulator.
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
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
