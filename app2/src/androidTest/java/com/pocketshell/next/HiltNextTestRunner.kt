package com.pocketshell.next

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner for app2 (rewrite task U-2).
 *
 * Substitutes [HiltTestApplication] for the production [App]. That substitution
 * is what lets a `@dagger.hilt.EntryPoint` declared in the androidTest source
 * set be part of the singleton component the test can actually cast to — with
 * the production `@HiltAndroidApp` application, the component is generated from
 * the MAIN compilation only and knows nothing about a test-declared entry point
 * (observed as `ClassCastException: Cannot cast DaggerApp_HiltComponents...`).
 *
 * Nothing under test is lost by the swap: `App` is empty beyond its
 * `@HiltAndroidApp` annotation (plan §M-1 explicitly forbids eager init there),
 * and every `@InstallIn(SingletonComponent::class)` module — including the real
 * [com.pocketshell.next.di.AppModule], with the real Room database and the real
 * sshj-backed connection factory — is installed exactly as in production.
 */
class HiltNextTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
