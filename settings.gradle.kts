pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocketshell"

// Module layout mirrors docs/architecture.md.
//
// Approach for not-yet-existing modules: keep them commented out. Each later
// issue uncomments its own line as part of creating the module directory.
// Rationale: an `include()` for a missing directory is a hard Gradle error,
// and an explicit comment list is more discoverable than a conditional
// `file().exists()` filter (no silent skips, no magic).
//
// Rewrite in progress (docs/_scratch/simplification-implementation-plan-2026-09-02.md).
// app/, core-ssh, core-tmux, core-connection, core-agents were deleted in the
// "stable" branch hard cut. New modules (core-transport, core-hostapi, app2)
// are uncommented here as their scaffolding tasks land.
//
// core-portfwd depends on core-ssh (api(project(":shared:core-ssh"))) and is
// shelved until task P-4 rewires it onto core-transport.

// include(":shared:core-portfwd")
include(":shared:core-transport")
include(":shared:core-terminal")
include(":shared:core-usage")
include(":shared:core-storage")
include(":shared:core-voice")
include(":shared:core-assistant")
include(":shared:ui-kit")

// Test-only support module (issue #1048): the ONE audited shared de-flake
// settle-pump, consumed via `testImplementation` only — never ships in the APK.
include(":shared:test-support")
