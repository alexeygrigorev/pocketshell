// Root build script for PocketShell. Plugins used by any module are declared
// here with `apply false` per Gradle convention, so versions resolve once at
// the root and modules apply what they need. Mirrors
// ssh-auto-forward-android's setup.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
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
