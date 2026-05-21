// Root build script for PocketShell.
//
// Modules apply the plugins they need; declare them here with `apply false` so
// the versions are resolved once at the root and shared across modules.
//
// Compose / Hilt / KSP plugins are intentionally NOT declared here yet — they
// land alongside the modules that need them (issues #2 and #3).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
