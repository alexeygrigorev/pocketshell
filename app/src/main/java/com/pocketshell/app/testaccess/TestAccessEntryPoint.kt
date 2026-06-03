package com.pocketshell.app.testaccess

import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.SshKeyDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for connected-test access to a small set of
 * application singletons.
 *
 * **Why this lives in `main`** — the androidTest source set does not
 * own the Hilt code-gen scope of the production app's
 * [dagger.hilt.components.SingletonComponent]; declaring `@EntryPoint`
 * inside `androidTest/` produces a class that the Hilt processor never
 * folds into `DaggerApp_HiltComponents_SingletonC`, so
 * `EntryPointAccessors.fromApplication(...)` throws a
 * `ClassCastException` at runtime. Defining the interface in `main` is
 * the documented pattern (see for example `BootCompletedReceiverEntryPoint`
 * in `systemsurfaces/BootCompletedReceiver.kt`).
 *
 * **Scope** — this entry point intentionally exposes the *minimum*
 * surface that connected tests need to drive end-to-end user journeys
 * without going around the Hilt graph:
 *
 *  - `appDatabase()` — the singleton Room database. Tests use this to
 *    seed first-launch state (e.g. an SSH key for the cold-install
 *    journey in [com.pocketshell.app.proof.ColdInstallE2eTest]) so the
 *    reactive `Flow`s observed by view models see the write through
 *    the same Room `InvalidationTracker`. Going through a standalone
 *    `Room.databaseBuilder()` opens a parallel SQLite connection whose
 *    tracker is invisible to the app's running flows.
 *  - `sshKeyDao()` — convenience accessor over `appDatabase().sshKeyDao()`
 *    so tests don't reach into `AppDatabase` for one DAO.
 *
 * The interface is `internal` so it is only visible to other modules
 * in this `:app` Gradle module — connected tests in `app/src/androidTest`
 * can use it, third-party code or libraries cannot.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TestAccessEntryPoint {
    fun appDatabase(): AppDatabase
    fun sshKeyDao(): SshKeyDao

    /**
     * Issue #446: the singleton [ForwardingController] so the indicator
     * connected test ([com.pocketshell.app.portfwd.ForwardingIndicatorE2eTest])
     * can register/unregister an active host against the same instance the
     * production app-bar indicator observes — without standing up a real
     * SSH forward.
     */
    fun forwardingController(): ForwardingController
}
