// app2 — the rewrite's application module (task M-1 of
// docs/_scratch/simplification-implementation-plan-2026-09-02.md).
//
// Deliberately small: this is the composition root for the new client, and the
// plan's whole premise is that it stays thin glue over the shared modules.
// Anything the empty scaffold does not need is NOT declared here — later
// U/P tasks add dependencies alongside the code that consumes them, the same
// convention the version catalog documents.
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // Task P-6: the fast design-render loop (issue #555) for app2's own screens.
    // `scripts/render.sh` only knows about :shared:ui-kit's DesignRenders, and it
    // cannot grow to cover these — ui-kit is a dependency OF app2, so it can
    // never see an app2 composable. The same Roborazzi harness lives here
    // instead, rendering to the same `build/renders/` convention.
    alias(libs.plugins.roborazzi)
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

// Issue #2356 (Phase 4 of epic #2350), inherited by app2 when the rewrite's
// hard cut removed the old `app` module: versionCode/versionName are DERIVED
// from the git tag being built rather than hand-maintained literals, so a
// release requires no version-bump commit — the tag pushed by
// scripts/push-release-tag.sh IS the version declaration. The single source of
// truth for the derivation is scripts/derive-version.sh (app-only since
// issue #2643: the host CLI moved to PocketShell-io/pocketshell-cli and
// versions itself from that repo's own tags — the app version may drift
// freely; no cross-repo coupling guard remains).
//
// MUST NEVER fail/hang the build: any error (script missing, git missing,
// shallow/tagless checkout, timeout) falls back to a safe placeholder
// (versionCode=1, versionName="0.0.0-dev") rather than throwing. This is
// exercised by every ordinary local `scripts/assemble-debug.sh` run and every
// per-PR CI job, none of which check out full tag history.
data class PocketshellDerivedVersion(val code: Int, val name: String)

fun derivePocketshellVersion(): PocketshellDerivedVersion {
    val fallback = PocketshellDerivedVersion(1, "0.0.0-dev")
    return try {
        val script = rootProject.file(
            providers.gradleProperty("pocketshellVersionDeriveScript")
                .orElse("scripts/derive-version.sh")
                .get()
        )
        if (!script.exists()) return fallback
        val process = ProcessBuilder("bash", script.absolutePath, "both")
            .directory(rootProject.projectDir)
            // Derivation diagnostics are never build output. Discarding stderr
            // means even a noisy/wedged child cannot fill a pipe and deadlock.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val stdoutCapture = ByteArrayOutputStream()
        val stdoutDrainer = Thread({
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    // Keep only the small protocol prefix while continuing to
                    // drain everything, so stdout is bounded and cannot block.
                    val remaining = 64 * 1024 - stdoutCapture.size()
                    if (remaining > 0) {
                        stdoutCapture.write(buffer, 0, minOf(count, remaining))
                    }
                }
            } catch (_: Exception) {
                // Timeout cleanup closes the stream to release this drainer.
            }
        }, "pocketshell-version-stdout").apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.inputStream.close()
            process.waitFor(1, TimeUnit.SECONDS)
            stdoutDrainer.join(1000)
            return fallback
        }
        if (process.exitValue() != 0) return fallback
        stdoutDrainer.join(5000)
        if (stdoutDrainer.isAlive) {
            process.inputStream.close()
            return fallback
        }
        val stdout = stdoutCapture.toString(Charsets.UTF_8)

        var code = fallback.code
        var name = fallback.name
        var sawCode = false
        var sawName = false
        stdout.lineSequence().forEach { line ->
            when {
                line.startsWith("VERSION_CODE=") ->
                    line.removePrefix("VERSION_CODE=").trim().toIntOrNull()?.let {
                        code = it
                        sawCode = true
                    }
                line.startsWith("VERSION_NAME=") -> {
                    val value = line.removePrefix("VERSION_NAME=").trim()
                    if (value.isNotEmpty()) {
                        name = value
                        sawName = true
                    }
                }
            }
        }
        if (!sawCode || !sawName) return fallback
        PocketshellDerivedVersion(code, name)
    } catch (e: Exception) {
        fallback
    }
}

val pocketshellDerivedVersion = derivePocketshellVersion()

// Issue #2638: release APKs sign with a DEDICATED release identity, never the
// committed debug.keystore. Material resolves from exactly one of two places,
// in this order:
//
//   1. a gitignored `keystore.properties` in the repository root (local
//      builds; schema: storeFile / storePassword / keyAlias / keyPassword,
//      see docs/release.md "Signing"), or
//   2. the four CI environment variables ANDROID_RELEASE_KEYSTORE_BASE64
//      (the PKCS12 keystore, base64-encoded), ANDROID_RELEASE_STORE_PASSWORD,
//      ANDROID_RELEASE_KEY_ALIAS and ANDROID_RELEASE_KEY_PASSWORD
//      (the GitHub secrets .github/workflows/build.yml exports).
//
// There is deliberately NO third fallback (D22): with neither source present
// the project still configures and builds assembleDebug — the pre-release
// confidence gate compiles the release variant (kspReleaseKotlin /
// hiltJavaCompileRelease) on machines that hold no signing material — but any
// task that would PACKAGE a release APK fails loudly instead of silently
// emitting an unsigned APK (see the release build type below).
data class PocketshellReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun resolvePocketshellReleaseSigning(): PocketshellReleaseSigning? {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        val props = Properties().apply {
            propsFile.inputStream().use { load(it) }
        }
        fun required(key: String): String =
            props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GradleException(
                    "keystore.properties is missing '${key}'. Expected schema: " +
                        "storeFile / storePassword / keyAlias / keyPassword " +
                        "(docs/release.md, \"Signing\")."
                )
        val store = rootProject.file(required("storeFile"))
        if (!store.isFile) {
            throw GradleException(
                "keystore.properties points at a missing keystore file: $store. " +
                    "Fix the storeFile path or delete keystore.properties (docs/release.md, \"Signing\")."
            )
        }
        return PocketshellReleaseSigning(
            storeFile = store,
            storePassword = required("storePassword"),
            keyAlias = required("keyAlias"),
            keyPassword = required("keyPassword"),
        )
    }

    val keystoreB64 = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_BASE64").orNull
    val storePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
    val keyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
    val keyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
    if (keystoreB64 == null && storePassword == null && keyAlias == null && keyPassword == null) {
        return null
    }
    // Some but not all of the four variables is a broken CI configuration, not
    // a reason to quietly fall back to anything else.
    val envNames = mapOf(
        "ANDROID_RELEASE_KEYSTORE_BASE64" to keystoreB64,
        "ANDROID_RELEASE_STORE_PASSWORD" to storePassword,
        "ANDROID_RELEASE_KEY_ALIAS" to keyAlias,
        "ANDROID_RELEASE_KEY_PASSWORD" to keyPassword,
    )
    val missing = envNames.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) {
        "Release signing env vars are only partially set; missing: $missing. " +
            "Set all four ANDROID_RELEASE_* variables or none of them."
    }
    val decoded = layout.buildDirectory
        .dir("release-signing")
        .get()
        .file("pocketshell-release.keystore")
        .asFile
    decoded.parentFile.mkdirs()
    decoded.writeBytes(Base64.getDecoder().decode(keystoreB64))
    return PocketshellReleaseSigning(
        storeFile = decoded,
        storePassword = storePassword!!.trim(),
        keyAlias = keyAlias!!.trim(),
        keyPassword = keyPassword!!.trim(),
    )
}

val pocketshellReleaseSigning = resolvePocketshellReleaseSigning()

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
        // Issue #2638: only registered when material actually resolved. The
        // release build type below reacts to the absence by refusing to
        // package, never by signing with the debug identity.
        if (pocketshellReleaseSigning != null) {
            create("releaseKeystore") {
                storeFile = pocketshellReleaseSigning.storeFile
                storePassword = pocketshellReleaseSigning.storePassword
                keyAlias = pocketshellReleaseSigning.keyAlias
                keyPassword = pocketshellReleaseSigning.keyPassword
            }
        }
    }

    defaultConfig {
        // Same install identity as v0.4.x so an update replaces the existing
        // PocketShell (same signature: debug.keystore). Kotlin namespace stays
        // `com.pocketshell.next`; only the Play/package id is `com.pocketshell.app`.
        applicationId = "com.pocketshell.app"
        minSdk = 26
        targetSdk = 35
        // Issue #2356: tag-derived, see derivePocketshellVersion() above. The
        // old `app` module that used to own this wiring is gone, so app2 IS the
        // shipping module and must derive the version the release path stamps.
        versionCode = pocketshellDerivedVersion.code
        versionName = pocketshellDerivedVersion.name
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
            // (e.g. `com.pocketshell.app.iapp2`) so two app2 worktrees running
            // connected tests on the ONE shared emulator do not uninstall each
            // other. `scripts/connected-test.sh --suffix <token>` passes the
            // property; with no property this is byte-for-byte the plain
            // `com.pocketshell.app` build, and the release type is untouched.
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
            // Issue #2638: distinct install identity. The release APK signs
            // with a different key than the daily-driver debug install, and
            // Android refuses to co-install two differently-signed packages
            // under one applicationId — so release lives beside it under
            // com.pocketshell.app.release, with its own launcher label
            // (app2/src/release/res/values/strings.xml). The DEBUG identity
            // above stays byte-for-byte what it was.
            applicationIdSuffix = ".release"

            if (pocketshellReleaseSigning != null) {
                signingConfig = signingConfigs.getByName("releaseKeystore")
            } else {
                // No signing material resolved: compilation lanes that do not
                // package a release APK (the confidence gate's
                // kspReleaseKotlin / hiltJavaCompileRelease, unit tests) stay
                // green, but packaging fails LOUDLY instead of producing an
                // unsigned APK. A dependency (not a doFirst) so the guard runs
                // even when packageRelease would otherwise be up-to-date.
                val requireReleaseSigning = tasks.register("requireReleaseSigningMaterial") {
                    group = "verification"
                    description = "Fails unless release signing material resolved (issue #2638)."
                    doLast {
                        throw GradleException(
                            "Cannot build a release APK: no release signing material. " +
                                "Provide EITHER a gitignored keystore.properties in the repository root " +
                                "(schema: storeFile / storePassword / keyAlias / keyPassword) " +
                                "OR the four CI variables ANDROID_RELEASE_KEYSTORE_BASE64, " +
                                "ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS, " +
                                "ANDROID_RELEASE_KEY_PASSWORD. " +
                                "See docs/release.md, \"Signing\". " +
                                "Refusing to produce an unsigned release APK."
                        )
                    }
                }
                tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }
                    .configureEach {
                        dependsOn(requireReleaseSigning)
                    }
            }
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
    // Native device-unlock handoff for SSH-key management and the encrypted-key
    // detail flow. MainActivity extends FragmentActivity for BiometricPrompt.
    implementation(libs.androidx.biometric)

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

    // Task U-7: `ProcessLifecycleOwner` — the process-wide foreground/background
    // signal the reconnect ladder is gated on (D21: no dialling from behind the
    // launcher). It lives in its own artifact, which also ships the
    // androidx.startup initializer that makes the owner dispatch at all.
    implementation(libs.lifecycle.process)

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
    // Task P-2: the Whisper dictation arm's client, audio recorder, price
    // catalogue and encrypted API-key storage — reused unchanged from the old
    // client's `:shared:core-voice`.
    implementation(project(":shared:core-voice"))
    // Task P-5: the usage/quota panel's provider-record model + strict NDJSON
    // parser. Reused as-is (plan: "shared/core-usage is UNCHANGED") — this is
    // its first app2 consumer.
    implementation(project(":shared:core-usage"))

    // SshKeyMaterial derives the public half through sshj and registers the
    // same full provider used by core-transport on real Android devices.
    implementation(libs.bouncycastle.bcprov)

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
    // Supplies the empty host Activity `createComposeRule()` launches, for the
    // DEBUG variant only (its AAR manifest declares the activity
    // `android:exported="true"`, which must never reach a shipped APK). The
    // release variant gets the same activity from app2/src/release/
    // AndroidManifest.xml instead — see the comment in that file for why a
    // `testImplementation` cannot do this job in an application module.
    debugImplementation(libs.compose.ui.test.manifest)

    // Task P-6: Roborazzi renders app2's own screens to PNG on the host JVM, so
    // a design change to the host form / key manager screens can be looked
    // at in seconds. Same versions :shared:ui-kit uses; nothing new in the
    // catalog.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

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
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Issue #2549: the journey harness bounds `waitForIdle()` with Espresso's
    // idling policies plus an IdlingResource tripwire, so Espresso has to be a
    // COMPILE dependency here, not just a runtime transitive of
    // compose-ui-test-junit4. This also BUMPS the resolved espresso version for
    // this source set from 3.5.0 (what ui-test-android 1.8.1 requires) to 3.6.1
    // — see the catalog entry for why that is deliberate.
    androidTestImplementation(libs.androidx.espresso.core)
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

// Issue #2356: lets scripts/check-version-coupling.sh assert that Gradle's
// resolved versionCode/versionName (via the exec wiring above) agrees with a
// DIRECT invocation of scripts/derive-version.sh — a structural check that the
// exec wiring hasn't drifted from the shared derivation, since there is no
// longer a static literal to compare against.
tasks.register("printPocketshellVersion") {
    group = "help"
    description = "Prints VERSION_CODE=/VERSION_NAME= as resolved by defaultConfig (issue #2356)."
    doLast {
        println("VERSION_CODE=${pocketshellDerivedVersion.code}")
        println("VERSION_NAME=${pocketshellDerivedVersion.name}")
    }
}
