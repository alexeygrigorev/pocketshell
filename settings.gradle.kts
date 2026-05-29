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
// Issue #2 will uncomment :app.
// Issue #3 will uncomment the :shared:core-* modules and :shared:ui-kit.

include(":app")

include(":shared:core-ssh")
include(":shared:core-portfwd")
include(":shared:core-tmux")
include(":shared:core-terminal")
include(":shared:core-agents")
include(":shared:core-usage")
include(":shared:core-storage")
include(":shared:core-voice")
include(":shared:core-assistant")
include(":shared:ui-kit")
